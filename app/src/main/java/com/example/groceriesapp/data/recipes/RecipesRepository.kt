package com.example.groceriesapp.data.recipes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import com.example.groceriesapp.data.ConsumptionEvent
import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.atomicWrite
import com.example.groceriesapp.data.ensureDirectory
import com.example.groceriesapp.vision.ocr.ReceiptOcrRecognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
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
class RecipesRepository(
    private val baseDirectory: File,
    val curatedRecipes: List<Recipe>,
    private val aiDependencies: RecipeAiDependencies?,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** The production wiring: app-private storage, the bundled catalogue, real OpenAI clients. */
    constructor(context: Context) : this(
        baseDirectory = File(context.applicationContext.filesDir, "groceries/recipes"),
        curatedRecipes = RecipeCatalog.fromAssets(context),
        aiDependencies = RecipeAiDependencies.fromContext(context),
    )

    private val lock = Any()

    private val userFile = File(baseDirectory, "user.json")
    private val generatedFile = File(baseDirectory, "generated.json")
    private val favoritesFile = File(baseDirectory, "favorites.json")
    private val cookLogFile = File(baseDirectory, "cooklog.json")
    private val collectionsFile = File(baseDirectory, "collections.json")
    private val recentSourcesFile = File(baseDirectory, "recent_sources.json")
    private val generationsFile = File(baseDirectory, "generations.json")
    val imagesDir: File = File(baseDirectory, "images")

    private val userRecipesState = MutableStateFlow(loadRecipes(userFile, RecipeSource.USER))
    val userRecipes: StateFlow<List<Recipe>> = userRecipesState.asStateFlow()

    private val generatedRecipesState = MutableStateFlow(loadRecipes(generatedFile, RecipeSource.AI))
    val generatedRecipes: StateFlow<List<Recipe>> = generatedRecipesState.asStateFlow()

    private val favoriteIdsState = MutableStateFlow(loadFavorites())
    val favoriteIds: StateFlow<Set<String>> = favoriteIdsState.asStateFlow()

    /** Every time a recipe was cooked, newest first. */
    private val cookLogState = MutableStateFlow(loadCookLog())
    val cookLog: StateFlow<List<CookEntry>> = cookLogState.asStateFlow()

    private val collectionsState = MutableStateFlow(loadCollections())
    val collections: StateFlow<List<RecipeCollection>> = collectionsState.asStateFlow()

    // --- AI layer ------------------------------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val ai: RecipeAiDependencies
        get() = aiDependencies ?: error("AI features are not available in this build.")
    private val keyStore get() = ai.keyStore
    private val chatClient get() = ai.chatClient
    private val imageClient get() = ai.imageClient
    private val urlClient get() = ai.urlClient

    private val aiStateFlow = MutableStateFlow<AiRecipeState>(AiRecipeState.Idle)
    val aiState: StateFlow<AiRecipeState> = aiStateFlow.asStateFlow()

    /** A scanned or link-imported recipe awaiting review; nothing is persisted until saved. */
    private val reviewDraftState = MutableStateFlow<RecipeReviewDraft?>(null)
    val reviewDraft: StateFlow<RecipeReviewDraft?> = reviewDraftState.asStateFlow()

    /** Past structured generations, newest first; each points at recipes in [generatedRecipes]. */
    private val generationHistoryState = MutableStateFlow(loadGenerations())
    val generationHistory: StateFlow<List<RecipeGenerationResult>> = generationHistoryState.asStateFlow()

    /** Set when a structured generation lands, so the UI can open its results once. */
    private val lastGenerationIdState = MutableStateFlow<String?>(null)
    val lastGenerationId: StateFlow<String?> = lastGenerationIdState.asStateFlow()

    /** Sites the user has imported links from, most recent first. */
    private val recentSourcesState = MutableStateFlow(loadRecentSources())
    val recentSources: StateFlow<List<RecentSource>> = recentSourcesState.asStateFlow()

    /** Set when an adaptation or a requested recipe lands, so the UI can open it once. */
    private val lastAiRecipeIdState = MutableStateFlow<String?>(null)
    val lastAiRecipeId: StateFlow<String?> = lastAiRecipeIdState.asStateFlow()

    /** Bumped whenever a recipe image file changes, so image composables reload. */
    private val imageVersionState = MutableStateFlow(0)
    val imageVersion: StateFlow<Int> = imageVersionState.asStateFlow()

    /** True when an OpenAI key is stored; the same key powers the receipt extractor. */
    fun aiEnabled(): Boolean = aiDependencies?.let { deps -> runCatching { deps.keyStore.hasKey() }.getOrDefault(false) } ?: false

    fun clearAiError() {
        if (aiStateFlow.value is AiRecipeState.Error) aiStateFlow.value = AiRecipeState.Idle
    }

    /** Drops the pending draft and its scratch photo, if any. */
    fun discardReviewDraft() {
        reviewDraftState.value?.imageFile?.delete()
        reviewDraftState.value = null
    }

    /**
     * Persists a reviewed draft as a user recipe. A scan's photo becomes the recipe image, so the
     * page the recipe came from stays visible on its detail screen.
     */
    fun saveReviewedRecipe(recipe: Recipe): Recipe {
        val draft = reviewDraftState.value
        saveUserRecipe(recipe)
        val image = draft?.imageFile
        if (image != null && image.isFile) {
            BitmapFactory.decodeFile(image.path)?.let { bitmap ->
                attachImage(recipe.id, bitmap)
                bitmap.recycle()
            }
        }
        discardReviewDraft()
        return recipeById(recipe.id) ?: recipe
    }

    /**
     * Photographed recipe: the configured OCR engine reads the page, then the AI extractor
     * (when a key exists) or the rules parser structures it. Runs outside [runAiRequest] because
     * it must work keyless; it still reports through [aiState] so the screens show progress.
     */
    fun scanRecipePhoto(bitmap: Bitmap, ocr: ReceiptOcrRecognizer) {
        if (aiStateFlow.value == AiRecipeState.Loading) return
        aiStateFlow.value = AiRecipeState.Loading
        val scansDir = File(baseDirectory, "scans")
        scope.launch {
            runCatching {
                ensureDirectory(scansDir)
                val file = File(scansDir, "scan_${UUID.randomUUID()}.jpg")
                writeDownscaledJpeg(bitmap, file, SCAN_MAX_DIMENSION)
                val primary = aiDependencies
                    ?.takeIf { it.apiKeyOrNull() != null }
                    ?.let(::OpenAiRecipeTextExtractor)
                val scanner = RecipeScanner(ocr, FallbackRecipeTextExtractor(primary))
                val result = scanner.scan(file, clock())
                if (result.rawText.isBlank()) {
                    file.delete()
                    error("No text was found on the photo.")
                }
                reviewDraftState.value = RecipeReviewDraft(
                    recipe = result.recipe,
                    rawText = result.rawText,
                    imageFile = file,
                    sourceLabel = "Scanned photo",
                )
            }
                .onSuccess { aiStateFlow.value = AiRecipeState.Idle }
                .onFailure { error ->
                    aiStateFlow.value = AiRecipeState.Error(
                        error.message ?: "The photo could not be read.",
                    )
                }
        }
    }

    fun clearLastAiRecipeId() {
        lastAiRecipeIdState.value = null
    }

    fun clearLastGenerationId() {
        lastGenerationIdState.value = null
    }

    /**
     * Structured generation from the Generate screen: selected inventory items, preferences and
     * a time budget. The batch is stored like any other AI recipes plus one history entry that
     * remembers which recipes belong together.
     */
    fun generateRecipes(request: RecipeGenerationRequest, inventory: List<InventoryItem>) {
        runAiRequest { apiKey ->
            val today = LocalDate.now()
            val selected = inventory.filter { it.id in request.selectedInventoryItemIds }
            val content = chatClient.completeStructured(
                apiKey = apiKey,
                model = keyStore.textModel(),
                systemPrompt = OpenAiRecipePrompts.suggestSystemPrompt(),
                userContent = OpenAiRecipePrompts.generateUserContent(
                    request = request,
                    selectedPrompt = if (selected.isEmpty()) "" else OpenAiRecipePrompts.inventorySnapshot(selected, today),
                    inventoryPrompt = OpenAiRecipePrompts.inventorySnapshot(inventory, today),
                ),
                schemaName = "recipe_generation",
                schema = OpenAiRecipePrompts.buildSchema(),
            )
            val recipes = OpenAiRecipePrompts.parseRecipes(
                content = content,
                source = RecipeSource.AI,
                createdAtEpochMillis = clock(),
            ).map { recipe ->
                // The request's preferences are facts about the recipe too; keep them as tags
                // so the library's dietary filters see generated dishes.
                val tags = (recipe.tags + request.preferences.map { it.tag }).distinct()
                recipe.copy(tags = tags)
            }
            check(recipes.isNotEmpty()) { "The AI answer contained no usable recipes." }
            appendGeneratedRecipes(recipes)
            val result = RecipeGenerationResult(
                request = request,
                recipeIds = recipes.map { it.id },
                createdAtEpochMillis = clock(),
            )
            synchronized(lock) {
                val updated = (listOf(result) + generationHistoryState.value).take(GENERATIONS_CAP)
                generationHistoryState.value = updated
                persistGenerations(updated)
            }
            lastGenerationIdState.value = result.id
        }
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
                createdAtEpochMillis = clock(),
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
                createdAtEpochMillis = clock(),
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
                createdAtEpochMillis = clock(),
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
                    createdAtEpochMillis = clock(),
                )
                val recipe = fromMarkup ?: importFromPageText(html)
                reviewDraftState.value = RecipeReviewDraft(
                    recipe = recipe,
                    rawText = null,
                    imageFile = null,
                    sourceLabel = RecipeSources.hostOf(url)?.let(RecipeSources::displayName) ?: "Link",
                )
                RecipeSources.hostOf(url)?.let(::recordRecentSource)
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
            createdAtEpochMillis = clock(),
            idPrefix = "user_",
        ).firstOrNull() ?: error("That page did not read as a recipe.")
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

    /** Every recipe the app knows about, in the order the library shows them by default. */
    fun allRecipes(): List<Recipe> =
        userRecipesState.value + generatedRecipesState.value + curatedRecipes

    // --- Cook history ---------------------------------------------------------------------------

    /** Logs one cooking of a recipe, optionally with its star rating; returns the new entry. */
    fun recordCooked(
        recipeId: String,
        recipeTitle: String,
        householdId: String?,
        rating: Int? = null,
        cookedAtEpochMillis: Long = clock(),
    ): CookEntry {
        val entry = CookEntry(
            recipeId = recipeId,
            recipeTitle = recipeTitle,
            cookedAtEpochMillis = cookedAtEpochMillis,
            rating = rating,
            householdId = householdId,
        )
        synchronized(lock) {
            val updated = (listOf(entry) + cookLogState.value)
                .sortedByDescending { it.cookedAtEpochMillis }
            cookLogState.value = updated
            persistCookLog(updated)
        }
        return entry
    }

    /**
     * One-time import of cook history from a household's consumption log, for installs that
     * cooked before the cook log existed. A marker file per household makes it idempotent, and
     * entries are only added for recipes with no history yet, so nothing is ever doubled.
     */
    fun backfillCookLog(householdId: String, events: List<ConsumptionEvent>) {
        val marker = File(baseDirectory, "cooklog.backfilled.$householdId")
        if (marker.isFile) return
        synchronized(lock) {
            if (marker.isFile) return
            val known = cookLogState.value.map { it.recipeId }.toSet()
            val imported = CookLogBackfill.fromEvents(events, householdId).filter { it.recipeId !in known }
            if (imported.isNotEmpty()) {
                val updated = (cookLogState.value + imported).sortedByDescending { it.cookedAtEpochMillis }
                cookLogState.value = updated
                persistCookLog(updated)
            }
            ensureDirectory(baseDirectory)
            marker.writeText(clock().toString())
        }
    }

    fun rateCookEntry(entryId: String, rating: Int?) {
        synchronized(lock) {
            val current = cookLogState.value
            if (current.none { it.id == entryId }) return
            val updated = current.map { if (it.id == entryId) it.copy(rating = rating) else it }
            cookLogState.value = updated
            persistCookLog(updated)
        }
    }

    fun deleteCookEntry(entryId: String) {
        synchronized(lock) {
            val current = cookLogState.value
            val updated = current.filterNot { it.id == entryId }
            if (updated.size == current.size) return
            cookLogState.value = updated
            persistCookLog(updated)
        }
    }

    // --- Collections ----------------------------------------------------------------------------

    fun createCollection(name: String): RecipeCollection {
        val collection = RecipeCollection(name = name.trim(), createdAtEpochMillis = clock())
        synchronized(lock) {
            val updated = collectionsState.value + collection
            collectionsState.value = updated
            persistCollections(updated)
        }
        return collection
    }

    fun renameCollection(collectionId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        updateCollections { list ->
            list.map { if (it.id == collectionId) it.copy(name = trimmed) else it }
        }
    }

    fun deleteCollection(collectionId: String) {
        updateCollections { list -> list.filterNot { it.id == collectionId } }
    }

    fun setRecipeInCollection(collectionId: String, recipeId: String, included: Boolean) {
        updateCollections { list ->
            list.map { collection ->
                if (collection.id != collectionId) return@map collection
                val ids = collection.recipeIds
                when {
                    included && recipeId !in ids -> collection.copy(recipeIds = ids + recipeId)
                    !included && recipeId in ids -> collection.copy(recipeIds = ids - recipeId)
                    else -> collection
                }
            }
        }
    }

    private fun updateCollections(transform: (List<RecipeCollection>) -> List<RecipeCollection>) {
        synchronized(lock) {
            val current = collectionsState.value
            val updated = transform(current)
            if (updated == current) return
            collectionsState.value = updated
            persistCollections(updated)
        }
    }

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
            val collections = collectionsState.value
            val prunedCollections = collections.map { collection ->
                if (id in collection.recipeIds) collection.copy(recipeIds = collection.recipeIds - id) else collection
            }
            if (prunedCollections != collections) {
                collectionsState.value = prunedCollections
                persistCollections(prunedCollections)
            }
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

    private fun loadCookLog(): List<CookEntry> =
        loadArray(cookLogFile, COOK_LOG_KEY) { it.toCookEntryOrNull() }
            .sortedByDescending { it.cookedAtEpochMillis }

    private fun persistCookLog(entries: List<CookEntry>) {
        atomicWrite(
            cookLogFile,
            JSONObject()
                .put(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
                .put(COOK_LOG_KEY, JSONArray().apply { entries.forEach { put(it.toJson()) } })
                .toString(),
        )
    }

    internal fun recordRecentSource(host: String) {
        synchronized(lock) {
            val existing = recentSourcesState.value.firstOrNull { it.host == host }
            val updated = listOf(
                RecentSource(
                    host = host,
                    name = RecipeSources.displayName(host),
                    lastUsedAtEpochMillis = clock(),
                    count = (existing?.count ?: 0) + 1,
                ),
            ) + recentSourcesState.value.filterNot { it.host == host }
            val capped = updated.take(RECENT_SOURCES_CAP)
            recentSourcesState.value = capped
            atomicWrite(
                recentSourcesFile,
                JSONObject()
                    .put(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
                    .put(RECENT_SOURCES_KEY, JSONArray().apply { capped.forEach { put(it.toJson()) } })
                    .toString(),
            )
        }
    }

    private fun loadGenerations(): List<RecipeGenerationResult> =
        loadArray(generationsFile, GENERATIONS_KEY) { it.toRecipeGenerationResultOrNull() }
            .sortedByDescending { it.createdAtEpochMillis }

    private fun persistGenerations(results: List<RecipeGenerationResult>) {
        atomicWrite(
            generationsFile,
            JSONObject()
                .put(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
                .put(GENERATIONS_KEY, JSONArray().apply { results.forEach { put(it.toJson()) } })
                .toString(),
        )
    }

    private fun loadRecentSources(): List<RecentSource> =
        loadArray(recentSourcesFile, RECENT_SOURCES_KEY) { it.toRecentSourceOrNull() }
            .sortedByDescending { it.lastUsedAtEpochMillis }

    private fun loadCollections(): List<RecipeCollection> =
        loadArray(collectionsFile, COLLECTIONS_KEY) { it.toRecipeCollectionOrNull() }

    private fun persistCollections(collections: List<RecipeCollection>) {
        atomicWrite(
            collectionsFile,
            JSONObject()
                .put(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
                .put(COLLECTIONS_KEY, JSONArray().apply { collections.forEach { put(it.toJson()) } })
                .toString(),
        )
    }

    /** Absent file → empty; unreadable file → empty; unreadable entries → skipped. */
    private fun <T> loadArray(file: File, key: String, transform: (JSONObject) -> T?): List<T> {
        if (!file.isFile) return emptyList()
        val root = runCatching { JSONObject(file.readText(StandardCharsets.UTF_8)) }.getOrNull()
            ?: return emptyList()
        val array = root.optJSONArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                transform(entry)?.let(::add)
            }
        }
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

    private fun writeDownscaledJpeg(bitmap: Bitmap, target: File, maxDimension: Int) {
        val scale = maxDimension.toDouble() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale >= 1.0) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }
        try {
            FileOutputStream(target).use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, SCAN_JPEG_QUALITY, output)
                output.fd.sync()
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
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
        const val COOK_LOG_KEY = "entries"
        const val COLLECTIONS_KEY = "collections"
        const val RECENT_SOURCES_KEY = "sources"
        const val RECENT_SOURCES_CAP = 8
        const val GENERATIONS_KEY = "generations"
        const val GENERATIONS_CAP = 10
        /** Scans keep more resolution than stored photos: OCR needs legible small print. */
        const val SCAN_MAX_DIMENSION = 2048
        const val FILE_PREFIX = "file:"
        // Raised from 20: a structured batch is up to ten recipes and history keeps several.
        const val GENERATED_CAP = 60
        const val MAX_PAGE_TEXT_CHARS = 24_000
        const val MAX_IMAGE_DIMENSION = 1024
        const val IMAGE_QUALITY = 80
        const val SCAN_JPEG_QUALITY = 90
    }
}

/**
 * Replace-existing move for freshly written image files. `FileStorage.moveAtomicallyOrPlain`
 * deliberately refuses to overwrite, which is right for household directories and wrong here.
 */
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
