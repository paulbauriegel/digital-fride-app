package com.example.groceriesapp.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.groceriesapp.data.GroceriesRepository
import com.example.groceriesapp.data.BioCompanyRepository
import com.example.groceriesapp.data.ReweRepository
import com.example.groceriesapp.data.household.HouseholdRepository
import com.example.groceriesapp.data.household.Household
import com.example.groceriesapp.data.taxonomy.StorageLocation
import com.example.groceriesapp.data.recipes.Recipe
import com.example.groceriesapp.data.recipes.RecipeMatcher
import com.example.groceriesapp.data.recipes.RecipeSource
import com.example.groceriesapp.data.recipes.RecipesRepository
import com.example.groceriesapp.feature.recipes.RecipeDetailScreen
import com.example.groceriesapp.feature.recipes.RecipeEditorScreen
import com.example.groceriesapp.feature.recipes.RecipesScreen
import com.example.groceriesapp.feature.recipes.decodeOrientedBitmap
import java.time.LocalDate
import com.example.groceriesapp.data.network.ReweAuthRequest
import com.example.groceriesapp.feature.biocompany.BioCompanyReceiptsScreen
import com.example.groceriesapp.feature.rewe.ReweLoginScreen
import com.example.groceriesapp.feature.rewe.ReweReceiptsScreen
import kotlinx.coroutines.launch
import com.example.groceriesapp.feature.insights.InsightsScreen
import com.example.groceriesapp.feature.inventory.InventoryScreen
import com.example.groceriesapp.feature.more.SettingsScreen
import com.example.groceriesapp.feature.overview.OverviewScreen
import com.example.groceriesapp.feature.receipts.ImportItemsScreen
import com.example.groceriesapp.feature.receipts.ReceiptDetailScreen
import com.example.groceriesapp.feature.receipts.ReceiptsScreen
import com.example.groceriesapp.feature.review.ReceiptViewerScreen
import com.example.groceriesapp.feature.scanner.ScanViewModel
import com.example.groceriesapp.feature.scanner.ScanViewModelFactory
import com.example.groceriesapp.feature.scanner.ScannerScreen
import com.example.groceriesapp.vision.editing.ReceiptImageEditor
import com.example.groceriesapp.vision.ocr.OcrEngine
import com.example.groceriesapp.vision.ocr.ReceiptOcrRerunner
import com.example.groceriesapp.vision.ocr.receiptOcrRecognizer
import com.example.groceriesapp.vision.parsing.ItemExtractionEngine
import com.example.groceriesapp.vision.parsing.receiptItemExtractor
import java.io.File
import java.util.UUID

private enum class TopLevelDestination(
    val label: String,
    val icon: ImageVector,
) {
    OVERVIEW("Home", Icons.Default.Home),
    INVENTORY("Inventory", Icons.Default.Inventory2),
    RECIPES("Recipes", Icons.Default.RestaurantMenu),
    RECEIPTS("Receipts", Icons.AutoMirrored.Filled.ReceiptLong),
    INSIGHTS("Insights", Icons.Default.Insights),
}

@Composable
fun GroceriesApp(
    repository: GroceriesRepository,
    householdRepository: HouseholdRepository,
    recipesRepository: RecipesRepository,
    bioCompanyRepository: BioCompanyRepository,
    reweRepository: ReweRepository,
    widthSizeClass: WindowWidthSizeClass,
    userName: String?,
    onUserNameChanged: (String?) -> Unit,
    darkTheme: Boolean,
    ocrEngine: OcrEngine,
    onDarkThemeChanged: (Boolean) -> Unit,
    onOcrEngineChanged: (OcrEngine) -> Unit,
    itemExtractionEngine: ItemExtractionEngine,
    onItemExtractionEngineChanged: (ItemExtractionEngine) -> Unit,
) {
    val receipts by repository.receipts.collectAsStateWithLifecycle()
    val households by householdRepository.households.collectAsStateWithLifecycle()
    val activeHouseholdId by householdRepository.activeHouseholdId.collectAsStateWithLifecycle()
    // Everywhere an item could go other than where it already is.
    val otherHouseholds = remember(households, activeHouseholdId) {
        households.filterNot { it.id == activeHouseholdId }
    }
    // Import records name a household by id; the import screen has to show people a name.
    val householdNames = remember(households) { households.associate { it.id to it.name } }
    val inventory by repository.inventory.collectAsStateWithLifecycle()
    val consumptionEvents by repository.consumptionEvents.collectAsStateWithLifecycle()
    val bioCompanyAccount by bioCompanyRepository.account.collectAsStateWithLifecycle()
    val reweAccount by reweRepository.account.collectAsStateWithLifecycle()
    val userRecipes by recipesRepository.userRecipes.collectAsStateWithLifecycle()
    val generatedRecipes by recipesRepository.generatedRecipes.collectAsStateWithLifecycle()
    val favoriteRecipeIds by recipesRepository.favoriteIds.collectAsStateWithLifecycle()
    val aiRecipeState by recipesRepository.aiState.collectAsStateWithLifecycle()
    val recipeImportDraft by recipesRepository.importDraft.collectAsStateWithLifecycle()
    val lastAdaptedRecipeId by recipesRepository.lastAdaptedRecipeId.collectAsStateWithLifecycle()
    val recipeImageVersion by recipesRepository.imageVersion.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(TopLevelDestination.OVERVIEW) }
    // Overlays below (recipe detail, editor, settings, …) return early and drop the tab subtree.
    // Without this holder every `rememberSaveable` inside a tab — recipe filters, search text,
    // list scroll position — is lost as soon as you open a recipe and come back.
    val topLevelStateHolder = rememberSaveableStateHolder()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var openRecipeId by rememberSaveable { mutableStateOf<String?>(null) }
    var recipeEditorOpen by rememberSaveable { mutableStateOf(false) }
    var editorRecipeId by rememberSaveable { mutableStateOf<String?>(null) }
    var scanKey by remember {
        mutableStateOf(repository.activeScanSessionId())
    }
    // The receipt stack is three stacked overlays: Details underneath, with either the item
    // import flow or the image viewer on top. Checked in top-down order below.
    var receiptDetailId by rememberSaveable { mutableStateOf<String?>(null) }
    var importItemsReceiptId by rememberSaveable { mutableStateOf<String?>(null) }
    var receiptViewerId by rememberSaveable { mutableStateOf<String?>(null) }
    // Home's inventory-summary cards open the Inventory tab pre-filtered to one location.
    var inventoryLocationRequest by remember { mutableStateOf<StorageLocation?>(null) }
    var showBioCompanyImport by remember { mutableStateOf(false) }
    var showReweImport by remember { mutableStateOf(false) }
    var reweAuthRequest by remember { mutableStateOf<ReweAuthRequest?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val expanded = widthSizeClass != WindowWidthSizeClass.Compact
    val ocrRecognizer = remember(context, ocrEngine) {
        receiptOcrRecognizer(context, ocrEngine)
    }
    val itemExtractor = remember(context, itemExtractionEngine) {
        receiptItemExtractor(context, itemExtractionEngine)
    }

    fun beginScan() {
        receiptDetailId = null
        importItemsReceiptId = null
        receiptViewerId = null
        scanKey = UUID.randomUUID().toString()
    }

    val aiEnabled = remember(destination, aiRecipeState) { recipesRepository.aiEnabled() }
    val aiBusy = aiRecipeState is com.example.groceriesapp.data.recipes.AiRecipeState.Loading
    val aiError =
        (aiRecipeState as? com.example.groceriesapp.data.recipes.AiRecipeState.Error)?.message

    // Cookbook-page import: capture or pick a photo, then OCR + AI turn it into an editor draft.
    var recipeImportCameraFile by remember { mutableStateOf<File?>(null) }
    val recipeImportGalleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let { decodeOrientedBitmap(context, it) }?.let(recipesRepository::importRecipePhoto)
    }
    val recipeImportCameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = recipeImportCameraFile
        recipeImportCameraFile = null
        if (success && file != null) {
            decodeOrientedBitmap(file)?.let(recipesRepository::importRecipePhoto)
        }
        file?.delete()
    }

    fun beginRecipePhotoImport() {
        val cameraDir = File(context.cacheDir, "camera").apply(File::mkdirs)
        val file = File(cameraDir, "recipe_import_${UUID.randomUUID()}.jpg")
        recipeImportCameraFile = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        recipeImportCameraLauncher.launch(uri)
    }

    // A finished photo import opens the editor prefilled; a finished adaptation opens its recipe.
    androidx.compose.runtime.LaunchedEffect(recipeImportDraft) {
        if (recipeImportDraft != null) {
            editorRecipeId = null
            recipeEditorOpen = true
        }
    }
    androidx.compose.runtime.LaunchedEffect(lastAdaptedRecipeId) {
        lastAdaptedRecipeId?.let { id ->
            openRecipeId = id
            recipesRepository.clearLastAdaptedRecipeId()
        }
    }

    val activeScanKey = scanKey
    if (activeScanKey != null) {
        val scanOwner = remember(activeScanKey) { ScanViewModelStoreOwner() }
        DisposableEffect(scanOwner) {
            onDispose(scanOwner::clear)
        }
        val scanViewModel: ScanViewModel = viewModel(
            viewModelStoreOwner = scanOwner,
            key = "receipt-scan-$activeScanKey",
            factory = ScanViewModelFactory(repository, activeScanKey, ocrRecognizer, itemExtractor),
        )
        val scanState by scanViewModel.uiState.collectAsStateWithLifecycle()
        ScannerScreen(
            state = scanState,
            viewModel = scanViewModel,
            onClose = { scanKey = null },
            onCompleted = { receiptId ->
                scanKey = null
                // Land in the item review; back from there reveals the receipt details.
                receiptDetailId = receiptId
                importItemsReceiptId = receiptId
            },
        )
        return
    }

    if (showBioCompanyImport) {
        val bioCompanyEntries by bioCompanyRepository.receipts.collectAsStateWithLifecycle()
        BioCompanyReceiptsScreen(
            entries = bioCompanyEntries,
            onBack = { showBioCompanyImport = false },
            groceryRepository = repository,
            fetchDetail = { entry -> bioCompanyRepository.fetchReceiptDetail(entry) },
        )
        return
    }

    val activeAuthRequest = reweAuthRequest
    if (activeAuthRequest != null) {
        ReweLoginScreen(
            authRequest = activeAuthRequest,
            onCancel = {
                reweAuthRequest = null
                reweRepository.resetLoginState()
            },
            onCodeReceived = { callbackUrl, code ->
                reweAuthRequest = null
                scope.launch {
                    reweRepository.completeLogin(
                        callbackUrl = callbackUrl,
                        code = code,
                        codeVerifier = activeAuthRequest.codeVerifier,
                    )
                }
            },
            onError = { message ->
                reweAuthRequest = null
                reweRepository.failLogin(message)
            },
        )
        return
    }

    if (showReweImport) {
        val reweEntries by reweRepository.receipts.collectAsStateWithLifecycle()
        ReweReceiptsScreen(
            entries = reweEntries,
            reweRepository = reweRepository,
            groceryRepository = repository,
            onBack = { showReweImport = false },
        )
        return
    }

    // Item import sits on top of the receipt stack: back returns to Receipt Details.
    val activeImportItemsId = importItemsReceiptId
    if (activeImportItemsId != null) {
        val receipt = receipts.firstOrNull { it.id == activeImportItemsId }
        if (receipt != null) {
            BackHandler { importItemsReceiptId = null }
            ImportItemsScreen(
                receipt = receipt,
                onBack = { importItemsReceiptId = null },
                onAddItemsToInventory = { selections ->
                    repository.addReceiptItemsToInventory(receipt.id, selections)
                },
                suggestLocation = { item ->
                    repository.suggestStorageLocation(
                        foodConceptId = item.foodConceptId,
                        categoryStorageValue = item.category,
                        nameHint = item.name,
                    )
                },
                activeHouseholdId = activeHouseholdId,
                householdName = householdNames::get,
            )
            return
        }
        androidx.compose.runtime.LaunchedEffect(activeImportItemsId) {
            importItemsReceiptId = null
        }
    }

    // The image viewer/editor also stacks on Details ("View receipt").
    val activeViewerId = receiptViewerId
    if (activeViewerId != null) {
        val receipt = receipts.firstOrNull { it.id == activeViewerId }
        if (receipt != null) {
            BackHandler { receiptViewerId = null }
            ReceiptViewerScreen(
                receipt = receipt,
                expanded = expanded,
                onBack = { receiptViewerId = null },
                onShare = { shareReceipt(context, receipt.previewPath) },
                onDelete = {
                    repository.deleteReceipt(receipt.id)
                    receiptViewerId = null
                    receiptDetailId = null
                },
                onRescan = ::beginScan,
                onApplyImageEdit = { edit ->
                    val updatedReceipt = ReceiptImageEditor().apply(receipt, edit)
                    repository.saveReceipt(updatedReceipt)
                },
                onRerunOcr = {
                    val updatedReceipt = ReceiptOcrRerunner(
                        itemExtractor = itemExtractor,
                        recognizer = ocrRecognizer,
                    ).rerun(receipt)
                    repository.saveReceipt(updatedReceipt)
                },
            )
            return
        }
        androidx.compose.runtime.LaunchedEffect(activeViewerId) {
            receiptViewerId = null
        }
    }

    val activeDetailId = receiptDetailId
    if (activeDetailId != null) {
        val receipt = receipts.firstOrNull { it.id == activeDetailId }
        if (receipt != null) {
            BackHandler { receiptDetailId = null }
            ReceiptDetailScreen(
                receipt = receipt,
                onBack = { receiptDetailId = null },
                onViewReceipt = { receiptViewerId = receipt.id },
                onManageItems = { importItemsReceiptId = receipt.id },
                onShare = { shareReceipt(context, receipt.previewPath) },
                onDelete = {
                    repository.deleteReceipt(receipt.id)
                    receiptDetailId = null
                },
            )
            return
        }
        androidx.compose.runtime.LaunchedEffect(activeDetailId) {
            receiptDetailId = null
        }
    }

    // Reached only after the scan block above has returned, so the household switcher can never be
    // on screen while a capture is running. Keep this branch below that one.
    if (showSettings) {
        SettingsScreen(
            householdRepo = householdRepository,
            userName = userName,
            onUserNameChanged = onUserNameChanged,
            darkTheme = darkTheme,
            onDarkThemeChanged = onDarkThemeChanged,
            ocrEngine = ocrEngine,
            onOcrEngineChanged = onOcrEngineChanged,
            itemExtractionEngine = itemExtractionEngine,
            onItemExtractionEngineChanged = onItemExtractionEngineChanged,
            bioCompanyRepo = bioCompanyRepository,
            onOpenBioCompanyReceipts = { showBioCompanyImport = true },
            reweRepo = reweRepository,
            onConnectRewe = { reweAuthRequest = reweRepository.createAuthRequest() },
            onOpenReweReceipts = { showReweImport = true },
            onBack = { showSettings = false },
            // Deleting a household that is not the active one leaves this repository in place, so
            // it has to be told that the shared receipt index changed underneath it. Without this
            // its stale copy would be written back on the next save, undoing the release.
            onHouseholdDeleted = { repository.reloadReceipts() },
        )
        return
    }

    val allRecipes = userRecipes + generatedRecipes + recipesRepository.curatedRecipes

    if (recipeEditorOpen) {
        // Each overlay below returns early, so only its own BackHandler is ever registered.
        BackHandler {
            recipesRepository.clearImportDraft()
            recipeEditorOpen = false
            editorRecipeId = null
        }
        RecipeEditorScreen(
            initial = editorRecipeId?.let { id -> allRecipes.firstOrNull { it.id == id } }
                ?: recipeImportDraft,
            onSave = { recipe ->
                recipesRepository.saveUserRecipe(recipe)
                recipesRepository.clearImportDraft()
                recipeEditorOpen = false
                editorRecipeId = null
                openRecipeId = recipe.id
            },
            onBack = {
                recipesRepository.clearImportDraft()
                recipeEditorOpen = false
                editorRecipeId = null
            },
        )
        return
    }

    val activeRecipeId = openRecipeId
    if (activeRecipeId != null) {
        val recipe = allRecipes.firstOrNull { it.id == activeRecipeId }
        if (recipe != null) {
            BackHandler {
                recipesRepository.clearAiError()
                openRecipeId = null
            }
            val match = remember(recipe, inventory, favoriteRecipeIds) {
                RecipeMatcher().matchOne(
                    recipe = recipe,
                    inventory = inventory,
                    today = LocalDate.now(),
                    favoriteIds = favoriteRecipeIds,
                )
            }
            RecipeDetailScreen(
                match = match,
                favorite = recipe.id in favoriteRecipeIds,
                imagesDir = recipesRepository.imagesDir,
                imageVersion = recipeImageVersion,
                aiEnabled = aiEnabled,
                aiBusy = aiBusy,
                aiError = aiError,
                onBack = {
                    recipesRepository.clearAiError()
                    openRecipeId = null
                },
                onToggleFavorite = { recipesRepository.toggleFavorite(recipe.id) },
                onCooked = { consumptions ->
                    repository.consumeForRecipe(recipe.id, recipe.title, consumptions)
                    openRecipeId = null
                },
                onPhotoPicked = { bitmap ->
                    recipesRepository.attachImage(recipe.id, bitmap)
                },
                onEdit = if (recipe.source == RecipeSource.USER) {
                    {
                        editorRecipeId = recipe.id
                        recipeEditorOpen = true
                    }
                } else {
                    null
                },
                onDelete = if (recipe.source == RecipeSource.USER) {
                    {
                        recipesRepository.deleteUserRecipe(recipe.id)
                        openRecipeId = null
                    }
                } else {
                    null
                },
                onAdaptToInventory = { recipesRepository.adaptRecipe(recipe, inventory) },
                onGeneratePhoto = { recipesRepository.generatePhoto(recipe) },
                onAskQuestion = if (aiEnabled) {
                    { question, history ->
                        recipesRepository.askRecipeQuestion(recipe, inventory, question, history)
                    }
                } else {
                    null
                },
            )
            return
        }
        androidx.compose.runtime.LaunchedEffect(activeRecipeId) {
            openRecipeId = null
        }
    }

    // Back from a secondary tab returns to Home; only Home lets back leave the app.
    BackHandler(enabled = destination != TopLevelDestination.OVERVIEW) {
        destination = TopLevelDestination.OVERVIEW
    }

    // Declared once and used by both shells: the rail and bottom-bar branches previously repeated
    // this whole argument list, which is the kind of duplication that silently drifts.
    val topLevelContent: @Composable (Modifier) -> Unit = { contentModifier ->
        topLevelStateHolder.SaveableStateProvider(destination.name) {
            TopLevelContent(
                destination = destination,
                repository = repository,
                otherHouseholds = otherHouseholds,
                receipts = receipts,
                inventory = inventory,
                consumptionEvents = consumptionEvents,
                userName = userName,
                bioCompanyConnected = bioCompanyAccount != null,
                reweConnected = reweAccount != null,
                onImportRewe = { showReweImport = true },
                onScan = ::beginScan,
                onDestination = { destination = it },
                onOpenInventoryAt = { location ->
                    inventoryLocationRequest = location
                    destination = TopLevelDestination.INVENTORY
                },
                inventoryLocationRequest = inventoryLocationRequest,
                onInventoryLocationRequestConsumed = { inventoryLocationRequest = null },
                onOpenReceipt = { receiptDetailId = it },
                onOpenSettings = { showSettings = true },
                recipesRepository = recipesRepository,
                userRecipes = userRecipes,
                generatedRecipes = generatedRecipes,
                favoriteRecipeIds = favoriteRecipeIds,
                recipeImageVersion = recipeImageVersion,
                aiEnabled = aiEnabled,
                aiBusy = aiBusy,
                aiError = aiError,
                onOpenRecipe = { openRecipeId = it },
                onWriteRecipe = {
                    editorRecipeId = null
                    recipeEditorOpen = true
                },
                onScanRecipeCamera = ::beginRecipePhotoImport,
                onScanRecipeGallery = {
                    recipeImportGalleryLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
                onImportRecipeUrl = recipesRepository::importRecipeFromUrl,
                onOpenBioCompanyReceipts = { showBioCompanyImport = true },
                modifier = contentModifier,
            )
        }
    }

    if (expanded) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail {
                TopLevelDestination.entries.forEach { item ->
                    NavigationRailItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                        alwaysShowLabel = true,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                // No bottom bar here, so the leaf Scaffolds handle the system bars themselves.
                topLevelContent(
                    Modifier
                        .widthIn(max = 1_040.dp)
                        .fillMaxSize(),
                )
            }
        }
    } else {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                NavigationBar {
                    TopLevelDestination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            topLevelContent(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    // Modifier.padding does not consume insets, so without this the leaf Scaffolds
                    // re-report the navigation bar the bottom bar already covered and pad twice.
                    .consumeWindowInsets(innerPadding),
            )
        }
    }
}

private class ScanViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    fun clear() {
        viewModelStore.clear()
    }
}

@Composable
private fun TopLevelContent(
    destination: TopLevelDestination,
    repository: GroceriesRepository,
    otherHouseholds: List<Household>,
    receipts: List<com.example.groceriesapp.data.DigitalReceipt>,
    inventory: List<com.example.groceriesapp.data.InventoryItem>,
    consumptionEvents: List<com.example.groceriesapp.data.ConsumptionEvent>,
    userName: String?,
    bioCompanyConnected: Boolean,
    reweConnected: Boolean,
    onImportRewe: () -> Unit,
    onScan: () -> Unit,
    onDestination: (TopLevelDestination) -> Unit,
    onOpenInventoryAt: (StorageLocation?) -> Unit,
    inventoryLocationRequest: StorageLocation?,
    onInventoryLocationRequestConsumed: () -> Unit,
    onOpenReceipt: (String) -> Unit,
    onOpenSettings: () -> Unit,
    recipesRepository: RecipesRepository,
    userRecipes: List<Recipe>,
    generatedRecipes: List<Recipe>,
    favoriteRecipeIds: Set<String>,
    recipeImageVersion: Int,
    aiEnabled: Boolean,
    aiBusy: Boolean,
    aiError: String?,
    onOpenRecipe: (String) -> Unit,
    onWriteRecipe: () -> Unit,
    onScanRecipeCamera: () -> Unit,
    onScanRecipeGallery: () -> Unit,
    onImportRecipeUrl: (String) -> Unit,
    onOpenBioCompanyReceipts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        when (destination) {
            TopLevelDestination.OVERVIEW -> OverviewScreen(
                userName = userName,
                inventory = inventory,
                receipts = receipts,
                recipes = userRecipes + generatedRecipes + recipesRepository.curatedRecipes,
                favoriteRecipeIds = favoriteRecipeIds,
                imagesDir = recipesRepository.imagesDir,
                imageVersion = recipeImageVersion,
                onScanReceipt = onScan,
                onAddItem = repository::addInventoryItem,
                onOpenInventory = onOpenInventoryAt,
                onOpenRecipes = { onDestination(TopLevelDestination.RECIPES) },
                onOpenRecipe = onOpenRecipe,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.fillMaxSize(),
            )
            TopLevelDestination.INVENTORY -> InventoryScreen(
                inventory = inventory,
                locationRequest = inventoryLocationRequest,
                onLocationRequestConsumed = onInventoryLocationRequestConsumed,
                onAddItem = repository::addInventoryItem,
                onRemoveItem = { id, reason -> repository.removeInventoryItem(id, reason) },
                onMarkOpened = repository::markInventoryItemOpened,
                onSetLocation = { id, location ->
                    repository.setInventoryItemStorageLocation(id, location)
                },
                onSetFillLevel = { id, level ->
                    repository.setInventoryItemFillLevel(id, level)
                },
                onSetConcept = { id, conceptId ->
                    repository.setInventoryItemFoodConcept(id, conceptId)
                },
                onAdjustQuantity = { id, delta ->
                    repository.adjustInventoryItemQuantity(id, delta)
                },
                onUndoRemove = { item -> repository.restoreRemovedInventoryItem(item) },
                otherHouseholds = otherHouseholds,
                onMoveToHousehold = { itemId, householdId ->
                    repository.moveInventoryItemToHousehold(itemId, householdId)
                },
                onOpenSettings = onOpenSettings,
                modifier = Modifier.fillMaxSize(),
            )
            TopLevelDestination.RECIPES -> RecipesScreen(
                inventory = inventory,
                curatedRecipes = recipesRepository.curatedRecipes,
                userRecipes = userRecipes,
                generatedRecipes = generatedRecipes,
                favoriteIds = favoriteRecipeIds,
                imagesDir = recipesRepository.imagesDir,
                aiEnabled = aiEnabled,
                aiBusy = aiBusy,
                aiError = aiError,
                onOpenRecipe = onOpenRecipe,
                onToggleFavorite = recipesRepository::toggleFavorite,
                onWriteRecipe = onWriteRecipe,
                onScanRecipeCamera = onScanRecipeCamera,
                onScanRecipeGallery = onScanRecipeGallery,
                onImportRecipeUrl = onImportRecipeUrl,
                onGenerateIdeas = { recipesRepository.generateIdeas(inventory) },
                onOpenSettings = onOpenSettings,
                modifier = Modifier.fillMaxSize(),
            )
            TopLevelDestination.RECEIPTS -> ReceiptsScreen(
                receipts = receipts,
                bioCompanyConnected = bioCompanyConnected,
                reweConnected = reweConnected,
                onScanReceipt = onScan,
                onOpenReceipt = onOpenReceipt,
                onImportBioCompany = onOpenBioCompanyReceipts,
                onImportRewe = onImportRewe,
                onOpenStoreSettings = onOpenSettings,
                modifier = Modifier.fillMaxSize(),
            )
            TopLevelDestination.INSIGHTS -> InsightsScreen(
                receipts = receipts,
                consumptionEvents = consumptionEvents,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun shareReceipt(context: Context, path: String?) {
    val file = path?.let(::File)?.takeIf(File::isFile) ?: return
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.files",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share digital receipt"))
}
