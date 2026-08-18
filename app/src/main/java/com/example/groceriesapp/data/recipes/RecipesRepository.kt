package com.example.groceriesapp.data.recipes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.OpenAiKeyStore
import com.example.groceriesapp.data.network.OpenAiChatClient
import com.example.groceriesapp.data.network.OpenAiImageClient
import com.example.groceriesapp.data.network.RecipeUrlClient
import com.example.groceriesapp.vision.ocr.awaitTerminalResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** UI-facing state of the current AI request; one request runs at a time. */
sealed class AiRecipeState {
    data object Idle : AiRecipeState()
    data object Loading : AiRecipeState()
    data class Error(val message: String) : AiRecipeState()
}

/** One answered question in a recipe Q&A conversation; held in UI state only, never persisted. */
data class RecipeQaTurn(val question: String, val answer: String)

/**
 * Owns everything recipe-shaped: the read-only curated catalogue from assets, user-created (and
 * photo-imported) recipes, AI-generated ideas, favorites, and locally stored recipe images.
 * Same conventions as [com.example.groceriesapp.data.GroceriesRepository]: org.json files under
 * `filesDir/groceries/recipes/`, atomic writes, blocking mutators guarded by a single lock,
 * state exposed as hot [StateFlow]s.
 */
class RecipesRepository(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Any()

    private val baseDirectory = File(appContext.filesDir, "groceries/recipes")
    private val userFile = File(baseDirectory, "user.json")
    private val generatedFile = File(baseDirectory, "generated.json")
    private val favoritesFile = File(baseDirectory, "favorites.json")
    val imagesDir: File = File(baseDirectory, "images")

    val curatedRecipes: List<Recipe> = RecipeCatalog.fromAssets(appContext)

    private val userRecipesState = MutableStateFlow(loadRecipes(userFile, RecipeSource.USER))
    val userRecipes: StateFlow<List<Recipe>> = userRecipesState.asStateFlow()

    private val generatedRecipesState = MutableStateFlow(loadRecipes(generatedFile, RecipeSource.AI))
    val generatedRecipes: StateFlow<List<Recipe>> = generatedRecipesState.asStateFlow()

    private val favoriteIdsState = MutableStateFlow(loadFavorites())
    val favoriteIds: StateFlow<Set<String>> = favoriteIdsState.asStateFlow()

    // --- AI layer ------------------------------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyStore by lazy { OpenAiKeyStore(appContext) }
    private val chatClient by lazy {
        OpenAiChatClient(endpoint = "${keyStore.apiBase()}/chat/completions")
    }
    private val imageClient by lazy {
        OpenAiImageClient(endpoint = "${keyStore.apiBase()}/images/generations")
    }
    private val urlClient by lazy { RecipeUrlClient() }

    private val aiStateFlow = MutableStateFlow<AiRecipeState>(AiRecipeState.Idle)
    val aiState: StateFlow<AiRecipeState> = aiStateFlow.asStateFlow()

    /** A photo-imported recipe awaiting user confirmation in the editor; not yet persisted. */
    private val importDraftState = MutableStateFlow<Recipe?>(null)
    val importDraft: StateFlow<Recipe?> = importDraftState.asStateFlow()

    /** Set when an adaptation or a requested recipe lands, so the UI can open it once. */
    private val lastAiRecipeIdState = MutableStateFlow<String?>(null)
    val lastAiRecipeId: StateFlow<String?> = lastAiRecipeIdState.asStateFlow()

    /** Bumped whenever a recipe image file changes, so image composables reload. */
    private val imageVersionState = MutableStateFlow(0)
    val imageVersion: StateFlow<Int> = imageVersionState.asStateFlow()

    /** True when an OpenAI key is stored; the same key powers the receipt extractor. */
    fun aiEnabled(): Boolean = runCatching { keyStore.hasKey() }.getOrDefault(false)

    fun clearAiError() {
        if (aiStateFlow.value is AiRecipeState.Error) aiStateFlow.value = AiRecipeState.Idle
    }

    fun clearImportDraft() {
        importDraftState.value = null
    }

    fun clearLastAiRecipeId() {
        lastAiRecipeIdState.value = null
    }

    /** Asks for [OpenAiRecipePrompts.SUGGESTION_COUNT] fresh ideas from the current inventory. */
    fun generateIdeas(inventory: List<InventoryItem>) {
        runAiRequest { apiKey ->
            val content = chatClient.completeStructured(
                apiKey = apiKey,
                model = keyStore.textModel(),
                systemPrompt = OpenAiRecipePrompts.suggestSystemPrompt(),
                userContent = OpenAiRecipePrompts.suggestUserContent(
                    OpenAiRecipePrompts.inventorySnapshot(inventory, LocalDate.now()),
                ),
                schemaName = "recipe_suggestions",
                schema = OpenAiRecipePrompts.buildSchema(),
            )
            val recipes = OpenAiRecipePrompts.parseRecipes(
                content = content,
                source = RecipeSource.AI,
                createdAtEpochMillis = System.currentTimeMillis(),
            )
            check(recipes.isNotEmpty()) { "The AI answer contained no usable recipes." }
            appendGeneratedRecipes(recipes)
        }
    }

    /**
     * One recipe from a free-text request, a photo, or both — the add menu's "Generate with AI".
     * The photo rides along as a vision input, so this needs a vision-capable text model.
     */
    fun generateRecipeFromRequest(
        request: String,
        photo: Bitmap?,
        inventory: List<InventoryItem>,
    ) {
        runAiRequest { apiKey ->
            val content = chatClient.completeStructured(
                apiKey = apiKey,
                model = keyStore.textModel(),
                systemPrompt = OpenAiRecipePrompts.suggestSystemPrompt(),
                userContent = OpenAiRecipePrompts.requestUserContent(
                    request = request,
                    inventoryPrompt = OpenAiRecipePrompts.inventorySnapshot(
                        inventory,
                        LocalDate.now(),
                    ),
                    hasPhoto = photo != null,
                ),
                schemaName = "recipe_request",
                schema = OpenAiRecipePrompts.buildSchema(),
                imageDataUrls = listOfNotNull(photo?.let(::toJpegDataUrl)),
            )
            val recipe = OpenAiRecipePrompts.parseRecipes(
                content = content,
                source = RecipeSource.AI,
                createdAtEpochMillis = System.currentTimeMillis(),
            ).firstOrNull() ?: error("The AI answer contained no usable recipe.")
            appendGeneratedRecipes(listOf(recipe))
            lastAiRecipeIdState.value = recipe.id
        }
    }

    /** Rewrites [recipe] around the current inventory; the result lands as a new AI recipe. */
    fun adaptRecipe(recipe: Recipe, inventory: List<InventoryItem>) {
        runAiRequest { apiKey ->
            val content = chatClient.completeStructured(
                apiKey = apiKey,
                model = keyStore.textModel(),
                systemPrompt = OpenAiRecipePrompts.suggestSystemPrompt(),
                userContent = OpenAiRecipePrompts.adaptUserContent(
                    recipeJson = recipe.toJson().toString(),
                    inventoryPrompt = OpenAiRecipePrompts.inventorySnapshot(inventory, LocalDate.now()),
                ),
                schemaName = "recipe_adaptation",
                schema = OpenAiRecipePrompts.buildSchema(),
            )
            val adapted = OpenAiRecipePrompts.parseRecipes(
                content = content,
                source = RecipeSource.AI,
                createdAtEpochMillis = System.currentTimeMillis(),
            ).firstOrNull() ?: error("The AI answer contained no usable recipe.")
            appendGeneratedRecipes(listOf(adapted))
            lastAiRecipeIdState.value = adapted.id
        }
    }

    /**
     * Link import: fetch the page, and prefer its `schema.org/Recipe` JSON-LD over the model.
     *
     * The markup path is exact (publisher's own amounts and steps), instant, and needs no API key,
     * so AI is only used when a page carries no usable markup. Unlike the other AI entry points
     * this one is therefore allowed to run without a key — [runAiRequest] would reject it.
     */
    fun importRecipeFromUrl(url: String) {
        if (aiStateFlow.value == AiRecipeState.Loading) return
        aiStateFlow.value = AiRecipeState.Loading
        scope.launch {
            runCatching {
                val html = urlClient.fetchHtml(url)
                val fromMarkup = RecipeJsonLd.parseFromHtml(
                    html = html,
                    createdAtEpochMillis = System.currentTimeMillis(),
                )
                importDraftState.value = fromMarkup ?: importFromPageText(html)
            }
                .onSuccess { aiStateFlow.value = AiRecipeState.Idle }
                .onFailure { error ->
                    aiStateFlow.value = AiRecipeState.Error(
                        error.message ?: "The link could not be imported.",
                    )
                }
        }
    }

    /** AI fallback for pages without recipe markup; reuses the photo import's prompt. */
    private fun importFromPageText(html: String): Recipe {
        val apiKey = runCatching { keyStore.apiKey() }.getOrNull()
        checkNotNull(apiKey) {
            "That page has no recipe data. Add an OpenAI API key in Settings to import it anyway."
        }
        val text = RecipeJsonLd.htmlToPlainText(html)
        check(text.isNotBlank()) { "That page had no readable text." }
        val content = chatClient.completeStructured(
            apiKey = apiKey,
            model = keyStore.textModel(),
            systemPrompt = OpenAiRecipePrompts.suggestSystemPrompt(),
            // The page text reads like messy OCR to the model, so the same instruction applies.
            userContent = OpenAiRecipePrompts.importUserContent(text.take(MAX_PAGE_TEXT_CHARS)),
            schemaName = "recipe_import",
            schema = OpenAiRecipePrompts.buildSchema(),
        )
        return OpenAiRecipePrompts.parseRecipes(
            content = content,
            source = RecipeSource.USER,
            createdAtEpochMillis = System.currentTimeMillis(),
            idPrefix = "user_",
        ).firstOrNull() ?: error("That page did not read as a recipe.")
    }

    /** Cookbook-page import: on-device OCR, then OpenAI structures the text into a draft. */
    fun importRecipePhoto(bitmap: Bitmap) {
        runAiRequest { apiKey ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val ocrText = try {
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .awaitTerminalResult()
                    .text
            } finally {
                recognizer.close()
            }
            check(ocrText.isNotBlank()) { "No text was found on the photo." }
            val content = chatClient.completeStructured(
                apiKey = apiKey,
                model = keyStore.textModel(),
                systemPrompt = OpenAiRecipePrompts.suggestSystemPrompt(),
                userContent = OpenAiRecipePrompts.importUserContent(ocrText),
                schemaName = "recipe_import",
                schema = OpenAiRecipePrompts.buildSchema(),
            )
            val draft = OpenAiRecipePrompts.parseRecipes(
                content = content,
                source = RecipeSource.USER,
                createdAtEpochMillis = System.currentTimeMillis(),
                idPrefix = "user_",
            ).firstOrNull() ?: error("The photo did not read as a recipe.")
            importDraftState.value = draft
        }
    }

    /** Generates a photo for a recipe; the result is stored locally like a user photo. */
    fun generatePhoto(recipe: Recipe) {
        runAiRequest { apiKey ->
            val bytes = imageClient.generate(
                apiKey = apiKey,
                prompt = OpenAiRecipePrompts.imagePrompt(recipe),
                model = keyStore.imageModel(),
            )
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("The generated image could not be decoded.")
            attachImage(recipe.id, bitmap)
        }
    }

    /**
     * Answers a free-form question about [recipe], grounded in its details and the current
     * inventory. Awaited directly by the Q&A sheet rather than going through [runAiRequest]:
     * a conversation needs its answer back per-turn, and running it alongside other AI features
     * (idea generation, adaptation) is fine since it's a separate request.
     */
    suspend fun askRecipeQuestion(
        recipe: Recipe,
        inventory: List<InventoryItem>,
        question: String,
        history: List<RecipeQaTurn>,
    ): String = withContext(Dispatchers.IO) {
        val apiKey = runCatching { keyStore.apiKey() }.getOrNull()
            ?: error("No OpenAI API key is configured in Settings.")
        val content = chatClient.completeStructured(
            apiKey = apiKey,
            model = keyStore.textModel(),
            systemPrompt = OpenAiRecipePrompts.qaSystemPrompt(),
            userContent = OpenAiRecipePrompts.qaUserContent(
                recipe = recipe,
                inventoryPrompt = OpenAiRecipePrompts.inventorySnapshot(inventory, LocalDate.now()),
                history = history,
                question = question,
            ),
            schemaName = "recipe_question_answer",
            schema = OpenAiRecipePrompts.buildQaSchema(),
        )
        OpenAiRecipePrompts.parseAnswer(content) ?: error("The AI answer was empty.")
    }

    private fun runAiRequest(block: suspend (apiKey: String) -> Unit) {
        if (aiStateFlow.value == AiRecipeState.Loading) return
        val apiKey = runCatching { keyStore.apiKey() }.getOrNull()
        if (apiKey == null) {
            aiStateFlow.value = AiRecipeState.Error("No OpenAI API key is configured in Settings.")
            return
        }
        aiStateFlow.value = AiRecipeState.Loading
        scope.launch {
            runCatching { block(apiKey) }
                .onSuccess { aiStateFlow.value = AiRecipeState.Idle }
                .onFailure { error ->
                    aiStateFlow.value = AiRecipeState.Error(
                        error.message ?: "The AI request failed.",
                    )
                }
        }
    }

    fun recipeById(id: String): Recipe? =
        curatedRecipes.firstOrNull { it.id == id }
            ?: userRecipesState.value.firstOrNull { it.id == id }
            ?: generatedRecipesState.value.firstOrNull { it.id == id }

    fun toggleFavorite(id: String) {
        synchronized(lock) {
            val updated = favoriteIdsState.value.let { current ->
                if (id in current) current - id else current + id
            }
            favoriteIdsState.value = updated
            atomicWrite(
                favoritesFile,
                JSONObject()
                    .put(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
                    .put(FAVORITES_KEY, JSONArray(updated.toList()))
                    .toString(),
            )
        }
    }

    /** Adds or, when the id already exists, replaces a user recipe. */
    fun saveUserRecipe(recipe: Recipe) {
        val normalized = recipe.copy(source = RecipeSource.USER)
        synchronized(lock) {
            val current = userRecipesState.value
            val updated = if (current.any { it.id == normalized.id }) {
                current.map { if (it.id == normalized.id) normalized else it }
            } else {
                current + normalized
            }
            userRecipesState.value = updated
            persistRecipes(userFile, updated)
        }
    }

    /**
     * Removes a user-written or AI-generated recipe, its stored photo and its favourite flag.
     * Curated recipes come from the asset catalogue and are reloaded on every start, so they are
     * not deletable — callers should not offer the action for them.
     */
    fun deleteRecipe(id: String) {
        synchronized(lock) {
            val users = userRecipesState.value
            val remainingUsers = users.filterNot { it.id == id }
            val generated = generatedRecipesState.value
            val remainingGenerated = generated.filterNot { it.id == id }
            if (remainingUsers.size == users.size && remainingGenerated.size == generated.size) {
                return
            }
            if (remainingUsers.size != users.size) {
                userRecipesState.value = remainingUsers
                persistRecipes(userFile, remainingUsers)
            }
            if (remainingGenerated.size != generated.size) {
                generatedRecipesState.value = remainingGenerated
                persistRecipes(generatedFile, remainingGenerated)
            }
            if (id in favoriteIdsState.value) toggleFavoriteLocked(id)
            imageFileFor(id).delete()
        }
    }

    /**
     * Downscales and stores a photo for [recipeId], updates the recipe's [Recipe.imageRef], and
     * returns the updated recipe — or null when the recipe no longer exists. Curated recipes keep
     * their catalogue entry untouched; their image override lives only in the picture file, so a
     * curated id resolves through [imageFileFor] at render time.
     */
    fun attachImage(recipeId: String, bitmap: Bitmap): Recipe? {
        synchronized(lock) {
            val recipe = recipeById(recipeId) ?: return null
            ensureDirectory(imagesDir)
            val file = imageFileFor(recipeId)
            writeDownscaledWebp(bitmap, file)
            val updated = recipe.copy(imageRef = "$FILE_PREFIX${file.name}")
            imageVersionState.value++
            when (recipe.source) {
                RecipeSource.USER -> {
                    val list = userRecipesState.value.map { if (it.id == recipeId) updated else it }
                    userRecipesState.value = list
                    persistRecipes(userFile, list)
                }
                RecipeSource.AI -> {
                    val list = generatedRecipesState.value.map { if (it.id == recipeId) updated else it }
                    generatedRecipesState.value = list
                    persistRecipes(generatedFile, list)
                }
                // Curated recipes are asset-backed; the on-disk photo alone overrides their image.
                RecipeSource.CURATED -> Unit
            }
            return updated
        }
    }

    /** The local image file a recipe id maps to; exists only after [attachImage]. */
    fun imageFileFor(recipeId: String): File = File(imagesDir, "$recipeId.webp")

    /** Resolves an [Recipe.imageRef]'s `file:` form to an absolute file. */
    fun resolveImageFile(imageRef: String): File? =
        imageRef.removePrefix(FILE_PREFIX)
            .takeIf { it != imageRef }
            ?.let { File(imagesDir, it) }

    internal fun appendGeneratedRecipes(recipes: List<Recipe>) {
        if (recipes.isEmpty()) return
        synchronized(lock) {
            val updated = (generatedRecipesState.value + recipes)
                .sortedByDescending { it.createdAtEpochMillis ?: 0L }
                .take(GENERATED_CAP)
            generatedRecipesState.value = updated
            persistRecipes(generatedFile, updated)
        }
    }

    private fun toggleFavoriteLocked(id: String) {
        val updated = favoriteIdsState.value - id
        favoriteIdsState.value = updated
        atomicWrite(
            favoritesFile,
            JSONObject()
                .put(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
                .put(FAVORITES_KEY, JSONArray(updated.toList()))
                .toString(),
        )
    }

    private fun loadRecipes(file: File, fallbackSource: RecipeSource): List<Recipe> {
        if (!file.isFile) return emptyList()
        val root = runCatching { JSONObject(file.readText(StandardCharsets.UTF_8)) }.getOrNull()
            ?: return emptyList()
        val array = root.optJSONArray(RECIPES_KEY) ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                entry.toRecipeOrNull(fallbackSource)?.let(::add)
            }
        }
    }

    private fun persistRecipes(file: File, recipes: List<Recipe>) {
        atomicWrite(
            file,
            JSONObject()
                .put(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
                .put(RECIPES_KEY, JSONArray().apply { recipes.forEach { put(it.toJson()) } })
                .toString(),
        )
    }

    private fun loadFavorites(): Set<String> {
        if (!favoritesFile.isFile) return emptySet()
        val root = runCatching { JSONObject(favoritesFile.readText(StandardCharsets.UTF_8)) }
            .getOrNull() ?: return emptySet()
        val array = root.optJSONArray(FAVORITES_KEY) ?: return emptySet()
        return buildSet {
            for (i in 0 until array.length()) {
                array.optString(i).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    /**
     * A base64 JPEG `data:` URL for a vision request, downscaled like a stored photo — the model
     * gains nothing from full sensor resolution and the whole thing travels inside the JSON body.
     */
    private fun toJpegDataUrl(bitmap: Bitmap): String {
        val scaled = downscaledToMaxDimension(bitmap)
        val bytes = ByteArrayOutputStream().use { stream ->
            scaled.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, stream)
            stream.toByteArray()
        }
        if (scaled !== bitmap) scaled.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** Returns [bitmap] itself when it is already small enough, so callers must check identity. */
    private fun downscaledToMaxDimension(bitmap: Bitmap): Bitmap {
        val scale = MAX_IMAGE_DIMENSION.toDouble() / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1.0) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun writeDownscaledWebp(bitmap: Bitmap, target: File) {
        val scaled = downscaledToMaxDimension(bitmap)
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        ensureDirectory(checkNotNull(target.parentFile))
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                scaled.compress(format, IMAGE_QUALITY, output)
                output.fd.sync()
            }
            moveAtomically(temporary, target)
        } finally {
            if (temporary.exists()) temporary.delete()
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private companion object {
        const val SCHEMA_VERSION_KEY = "schemaVersion"
        const val SCHEMA_VERSION = 1
        const val RECIPES_KEY = "recipes"
        const val FAVORITES_KEY = "ids"
        const val FILE_PREFIX = "file:"
        const val GENERATED_CAP = 20
        const val MAX_PAGE_TEXT_CHARS = 24_000
        const val MAX_IMAGE_DIMENSION = 1024
        const val IMAGE_QUALITY = 80
    }
}

private fun ensureDirectory(directory: File) {
    if (!directory.isDirectory) check(directory.mkdirs() || directory.isDirectory) {
        "Could not create directory ${directory.path}"
    }
}

// Local copy of GroceriesRepository's file-private atomic write; small enough to not share.
private fun atomicWrite(target: File, contents: String) {
    ensureDirectory(checkNotNull(target.parentFile))
    val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
    try {
        FileOutputStream(temporary).use { output ->
            val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
            writer.write(contents)
            writer.flush()
            output.fd.sync()
        }
        moveAtomically(temporary, target)
    } finally {
        if (temporary.exists()) temporary.delete()
    }
}

private fun moveAtomically(from: File, to: File) {
    try {
        Files.move(
            from.toPath(),
            to.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(
            from.toPath(),
            to.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
