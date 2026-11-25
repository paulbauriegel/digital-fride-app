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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.BioCompanyRepository
import com.example.groceriesapp.data.EdekaRepository
import com.example.groceriesapp.data.ReweRepository
import com.example.groceriesapp.data.household.HouseholdRepository
import com.example.groceriesapp.data.household.Household
import com.example.groceriesapp.data.taxonomy.StorageLocation
import com.example.groceriesapp.data.recipes.CookEntry
import com.example.groceriesapp.data.recipes.Recipe
import com.example.groceriesapp.data.recipes.RecipeCollection
import com.example.groceriesapp.data.recipes.RecipeGenerationResult
import com.example.groceriesapp.data.recipes.RecipeReviewDraft
import com.example.groceriesapp.data.recipes.RecentSource
import com.example.groceriesapp.data.recipes.RecipeLibrary
import com.example.groceriesapp.data.recipes.RecipeStats
import com.example.groceriesapp.data.recipes.RecipeMatcher
import com.example.groceriesapp.data.recipes.RecipeSource
import com.example.groceriesapp.data.recipes.RecipesRepository
import com.example.groceriesapp.feature.recipes.RecipeDetailScreen
import com.example.groceriesapp.feature.recipes.RecipeEditorScreen
import com.example.groceriesapp.feature.recipes.hub.RecipesHub
import com.example.groceriesapp.feature.recipes.hub.RecipesHubActions
import com.example.groceriesapp.feature.recipes.hub.rememberRecipesHubState
import com.example.groceriesapp.feature.recipes.decodeOrientedBitmap
import java.time.LocalDate
import com.example.groceriesapp.data.network.OpenFoodFactsProduct
import com.example.groceriesapp.data.network.EdekaAuthRequest
import com.example.groceriesapp.data.network.ReweAuthRequest
import com.example.groceriesapp.data.products.ProductLookupRepository
import com.example.groceriesapp.data.products.ProductLookupState
import com.example.groceriesapp.feature.biocompany.BioCompanyReceiptsScreen
import com.example.groceriesapp.feature.edeka.EdekaLoginScreen
import com.example.groceriesapp.feature.edeka.EdekaReceiptsScreen
import com.example.groceriesapp.feature.rewe.ReweLoginScreen
import com.example.groceriesapp.feature.rewe.ReweReceiptsScreen
import kotlinx.coroutines.launch
import com.example.groceriesapp.feature.insights.InsightsScreen
import com.example.groceriesapp.feature.inventory.AddInventoryItemDialog
import com.example.groceriesapp.feature.inventory.BarcodeScannerScreen
import com.example.groceriesapp.feature.inventory.EditInventoryItemScreen
import com.example.groceriesapp.feature.inventory.InventoryItemPrefill
import com.example.groceriesapp.feature.inventory.InventoryScreen
import com.example.groceriesapp.feature.inventory.ScannedProductDialog
import com.example.groceriesapp.feature.more.SettingsScreen
import com.example.groceriesapp.feature.overview.OverviewScreen
import com.example.groceriesapp.feature.receipts.ImportItemsScreen
import com.example.groceriesapp.feature.receipts.ReceiptDetailScreen
import com.example.groceriesapp.feature.receipts.ReceiptSyncState
import com.example.groceriesapp.feature.receipts.ReceiptsScreen
import com.example.groceriesapp.feature.receipts.storeReceiptSyncer
import com.example.groceriesapp.feature.review.ReceiptViewerScreen
import com.example.groceriesapp.feature.scanner.ReceiptPhotoSource
import com.example.groceriesapp.feature.scanner.ScanSource
import com.example.groceriesapp.feature.scanner.ScanViewModel
import com.example.groceriesapp.feature.scanner.ScanViewModelFactory
import com.example.groceriesapp.feature.scanner.ScannerScreen
import com.example.groceriesapp.feature.scanner.writeReceiptPhotoFrame
import com.example.groceriesapp.vision.editing.ReceiptImageEditor
import com.example.groceriesapp.vision.ocr.OcrEngine
import com.example.groceriesapp.vision.ocr.ReceiptOcrRerunner
import com.example.groceriesapp.vision.ocr.receiptOcrRecognizer
import com.example.groceriesapp.vision.parsing.ItemExtractionEngine
import com.example.groceriesapp.vision.parsing.receiptItemExtractor
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

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
    edekaRepository: EdekaRepository,
    incomingEdekaLink: StateFlow<String?>,
    onEdekaLinkConsumed: () -> Unit,
    productLookupRepository: ProductLookupRepository,
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
    val edekaAccount by edekaRepository.account.collectAsStateWithLifecycle()
    val edekaLink by incomingEdekaLink.collectAsStateWithLifecycle()
    val userRecipes by recipesRepository.userRecipes.collectAsStateWithLifecycle()
    val generatedRecipes by recipesRepository.generatedRecipes.collectAsStateWithLifecycle()
    val favoriteRecipeIds by recipesRepository.favoriteIds.collectAsStateWithLifecycle()
    val aiRecipeState by recipesRepository.aiState.collectAsStateWithLifecycle()
    val recipeReviewDraft by recipesRepository.reviewDraft.collectAsStateWithLifecycle()
    val recipeRecentSources by recipesRepository.recentSources.collectAsStateWithLifecycle()
    val recipeGenerationHistory by recipesRepository.generationHistory.collectAsStateWithLifecycle()
    val lastGenerationId by recipesRepository.lastGenerationId.collectAsStateWithLifecycle()
    val lastAiRecipeId by recipesRepository.lastAiRecipeId.collectAsStateWithLifecycle()
    val recipeImageVersion by recipesRepository.imageVersion.collectAsStateWithLifecycle()
    val recipeCookLog by recipesRepository.cookLog.collectAsStateWithLifecycle()
    val recipeCollections by recipesRepository.collections.collectAsStateWithLifecycle()
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
    var editItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var inventoryMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var importItemsReceiptId by rememberSaveable { mutableStateOf<String?>(null) }
    var receiptViewerId by rememberSaveable { mutableStateOf<String?>(null) }
    // Home's inventory-summary cards open the Inventory tab pre-filtered to one location.
    var inventoryLocationRequest by remember { mutableStateOf<StorageLocation?>(null) }
    var barcodeScanOpen by rememberSaveable { mutableStateOf(false) }
    // Set the moment a barcode is read; the confirm dialog stays up until the item is saved or
    // dropped, whatever the lookup came back with.
    var scannedBarcode by rememberSaveable { mutableStateOf<String?>(null) }
    // Where the scan came from, and so where its result goes: null is the add-an-item flow, an id
    // is the item open in the editor, which offers the product's details as edits instead.
    var barcodeScanTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val productLookup by productLookupRepository.state.collectAsStateWithLifecycle()
    var showBioCompanyImport by remember { mutableStateOf(false) }
    var showReweImport by remember { mutableStateOf(false) }
    var showEdekaImport by remember { mutableStateOf(false) }
    var reweAuthRequest by remember { mutableStateOf<ReweAuthRequest?>(null) }
    var edekaAuthRequest by remember { mutableStateOf<EdekaAuthRequest?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val receiptSyncer = remember(repository, bioCompanyRepository, reweRepository, edekaRepository) {
        storeReceiptSyncer(
            context.applicationContext,
            repository,
            bioCompanyRepository,
            reweRepository,
            edekaRepository,
        )
    }
    val receiptSyncState by receiptSyncer.state.collectAsStateWithLifecycle()
    val expanded = widthSizeClass != WindowWidthSizeClass.Compact
    val ocrRecognizer = remember(context, ocrEngine) {
        receiptOcrRecognizer(context, ocrEngine)
    }
    val itemExtractor = remember(context, itemExtractionEngine) {
        receiptItemExtractor(context, itemExtractionEngine)
    }

    androidx.compose.runtime.LaunchedEffect(edekaLink) {
        if (edekaLink != null && edekaAuthRequest == null) {
            edekaAuthRequest = edekaRepository.pendingAuthRequest()
            if (edekaAuthRequest == null) {
                edekaRepository.failLogin("The EDEKA sign-in session expired. Start again.")
                onEdekaLinkConsumed()
            }
        }
    }

    fun beginScan() {
        receiptDetailId = null
        importItemsReceiptId = null
        receiptViewerId = null
        scanKey = UUID.randomUUID().toString()
    }

    // A photographed receipt takes the same path as a recording, with the photo standing in for the
    // keyframes. It is handed to the scan session the moment that session opens.
    var pendingReceiptPhoto by remember { mutableStateOf<ReceiptPhotoSource?>(null) }
    // Saved, not just remembered: the camera app can push this process out of memory while it is in
    // front, and the capture would otherwise come back with nowhere to go.
    var receiptCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    // Which scan session is a photo import. Saved for the same reason: the session itself is
    // recovered from disk after a rebuild, and it must not reopen as a live recording.
    var photoScanKey by rememberSaveable { mutableStateOf<String?>(null) }

    fun beginPhotoScan(photo: ReceiptPhotoSource) {
        receiptDetailId = null
        importItemsReceiptId = null
        receiptViewerId = null
        val key = UUID.randomUUID().toString()
        pendingReceiptPhoto = photo
        photoScanKey = key
        scanKey = key
    }

    val receiptPhotoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        // A cancelled picker returns null: nothing opens, and no empty scan session is left behind.
        uri?.let { beginPhotoScan(ReceiptPhotoSource.Picked(it)) }
    }
    val receiptCameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = receiptCameraPath?.let(::File)
        receiptCameraPath = null
        if (success && file != null) {
            beginPhotoScan(ReceiptPhotoSource.Captured(file))
        } else {
            file?.delete()
        }
    }

    fun takeReceiptPhoto() {
        val cameraDir = File(context.cacheDir, "camera").apply(File::mkdirs)
        val file = File(cameraDir, "receipt_${UUID.randomUUID()}.jpg")
        receiptCameraPath = file.absolutePath
        receiptCameraLauncher.launch(
            FileProvider.getUriForFile(context, "${context.packageName}.files", file),
        )
    }

    // Key presence is a one-shot prefs read, not a flow, so it has to be re-read whenever it could
    // have changed. `showSettings` is load-bearing: Settings is an overlay, not a destination, so
    // without it a key pasted there stays invisible until the tab changes or the app restarts.
    val aiEnabled = remember(destination, aiRecipeState, showSettings) {
        recipesRepository.aiEnabled()
    }
    val aiBusy = aiRecipeState is com.example.groceriesapp.data.recipes.AiRecipeState.Loading
    val aiError =
        (aiRecipeState as? com.example.groceriesapp.data.recipes.AiRecipeState.Error)?.message

    // Cookbook-page import: capture or pick a photo, then OCR + AI turn it into an editor draft.
    var recipeImportCameraFile by remember { mutableStateOf<File?>(null) }
    val recipeImportGalleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let { decodeOrientedBitmap(context, it) }
            ?.let { recipesRepository.scanRecipePhoto(it, ocrRecognizer) }
    }
    val recipeImportCameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = recipeImportCameraFile
        recipeImportCameraFile = null
        if (success && file != null) {
            decodeOrientedBitmap(file)?.let { recipesRepository.scanRecipePhoto(it, ocrRecognizer) }
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

    // Cook history predates the cook log on older installs: rebuild it once per household from
    // the consumption events that "I cooked this" has always written.
    androidx.compose.runtime.LaunchedEffect(activeHouseholdId, consumptionEvents) {
        activeHouseholdId?.let { recipesRepository.backfillCookLog(it, consumptionEvents) }
    }

    // A finished scan or link import is reviewed inside the Recipes hub; an adapted or requested
    // recipe opens straight into its detail screen.
    androidx.compose.runtime.LaunchedEffect(lastAiRecipeId) {
        lastAiRecipeId?.let { id ->
            openRecipeId = id
            recipesRepository.clearLastAiRecipeId()
        }
    }

    val activeScanKey = scanKey
    if (activeScanKey != null) {
        val scanOwner = remember(activeScanKey) { ScanViewModelStoreOwner() }
        DisposableEffect(scanOwner) {
            onDispose(scanOwner::clear)
        }
        val scanSource = if (activeScanKey == photoScanKey) ScanSource.PHOTO else ScanSource.LIVE
        val scanViewModel: ScanViewModel = viewModel(
            viewModelStoreOwner = scanOwner,
            key = "receipt-scan-$activeScanKey",
            factory = ScanViewModelFactory(
                repository = repository,
                sessionId = activeScanKey,
                ocrRecognizer = ocrRecognizer,
                itemExtractor = itemExtractor,
                source = scanSource,
            ),
        )
        val scanState by scanViewModel.uiState.collectAsStateWithLifecycle()
        androidx.compose.runtime.LaunchedEffect(activeScanKey) {
            if (scanSource != ScanSource.PHOTO) return@LaunchedEffect
            val photo = pendingReceiptPhoto
            pendingReceiptPhoto = null
            // A session whose frame is already written keeps going on its own; one whose photo did
            // not survive a rebuild has to fail, rather than wait for an import that cannot arrive.
            scanViewModel.importPhoto { frameFile ->
                photo != null && writeReceiptPhotoFrame(context, photo, frameFile).also {
                    // The session keeps its own copy of the frame, so the camera cache file is done.
                    (photo as? ReceiptPhotoSource.Captured)?.file?.delete()
                }
            }
        }
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

    // Below the receipt scanner deliberately: that branch returns early, so ARCore and CameraX can
    // never hold the camera at the same time.
    if (barcodeScanOpen) {
        BackHandler { barcodeScanOpen = false }
        BarcodeScannerScreen(
            onBarcode = { code ->
                barcodeScanOpen = false
                scannedBarcode = code
                productLookupRepository.lookup(code)
            },
            onClose = {
                barcodeScanOpen = false
                barcodeScanTargetId = null
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
            bioCompanyRepository = bioCompanyRepository,
        )
        return
    }

    val activeEdekaAuthRequest = edekaAuthRequest
    if (activeEdekaAuthRequest != null) {
        EdekaLoginScreen(
            authRequest = activeEdekaAuthRequest,
            incomingLink = edekaLink,
            continuationUrl = edekaRepository::continuationUrl,
            onLinkConsumed = onEdekaLinkConsumed,
            onCancel = {
                edekaAuthRequest = null
                edekaRepository.cancelLogin()
            },
            onCallback = { callbackUrl ->
                edekaAuthRequest = null
                scope.launch { edekaRepository.completeLogin(callbackUrl) }
            },
            onError = { message ->
                edekaAuthRequest = null
                edekaRepository.failLogin(message)
            },
        )
        return
    }

    if (showEdekaImport) {
        val edekaEntries by edekaRepository.receipts.collectAsStateWithLifecycle()
        EdekaReceiptsScreen(
            entries = edekaEntries,
            edekaRepository = edekaRepository,
            groceryRepository = repository,
            onBack = { showEdekaImport = false },
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

    // Store names already in use, most-used first: correcting a misread name should snap to a
    // spelling the library already has instead of adding another near-duplicate.
    val merchantSuggestions = remember(receipts) {
        receipts.mapNotNull { it.merchant?.takeIf(String::isNotBlank) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
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
                onRenameMerchant = { merchant ->
                    repository.saveReceipt(receipt.copy(merchant = merchant))
                },
                merchantSuggestions = merchantSuggestions,
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
                onRenameMerchant = { merchant ->
                    repository.saveReceipt(receipt.copy(merchant = merchant))
                },
                onEditSummary = { date, total ->
                    repository.saveReceipt(
                        receipt.copy(dateEpochMillis = date, totalCents = total),
                    )
                },
                onUpdateItem = { repository.updateReceiptItem(receipt.id, it) },
                onAddItem = { repository.addReceiptItem(receipt.id, it) },
                onDeleteItem = { repository.deleteReceiptItem(receipt.id, it) },
                merchantSuggestions = merchantSuggestions,
            )
            return
        }
        androidx.compose.runtime.LaunchedEffect(activeDetailId) {
            receiptDetailId = null
        }
    }

    // Same shape as the receipt-detail overlay above: hold only the id, resolve the item from the
    // live list each recomposition, and self-heal when it disappears — removed, consumed, or moved
    // to another household — so the editor can never outlive its subject.
    val activeEditItemId = editItemId
    if (activeEditItemId != null) {
        val editItem = inventory.firstOrNull { it.id == activeEditItemId }
        if (editItem != null) {
            BackHandler { editItemId = null }
            EditInventoryItemScreen(
                item = editItem,
                otherHouseholds = otherHouseholds,
                onBack = { editItemId = null },
                onRename = { repository.renameInventoryItem(editItem.id, it) },
                onSetCategory = { repository.setInventoryItemCategory(editItem.id, it) },
                onSetConcept = { repository.setInventoryItemFoodConcept(editItem.id, it) },
                onSetLocation = { repository.setInventoryItemStorageLocation(editItem.id, it) },
                onSetExpiryDate = { repository.setInventoryItemExpiryDate(editItem.id, it) },
                onSetAmount = { repository.setInventoryItemAmount(editItem.id, it) },
                onSetPackageSize = { repository.setInventoryItemPackageSize(editItem.id, it) },
                onSetHidden = { repository.setInventoryItemHidden(editItem.id, it) },
                onMarkOpened = { repository.markInventoryItemOpened(editItem.id) },
                onScanBarcode = {
                    barcodeScanTargetId = editItem.id
                    barcodeScanOpen = true
                },
                // Follow the split-off carton: it is the one the user just opened and will want
                // to set the amount on.
                onOpenOne = {
                    repository.openOneInventoryItem(editItem.id)?.let { opened ->
                        editItemId = opened.id
                    }
                },
                onRemove = { reason ->
                    repository.removeInventoryItem(editItem.id, reason)
                    editItemId = null
                },
                onMoveToHousehold = { householdId ->
                    repository.moveInventoryItemToHousehold(editItem.id, householdId)
                    val name = otherHouseholds.firstOrNull { it.id == householdId }?.name
                    inventoryMessage =
                        "${editItem.name} moved to ${name ?: "the other household"}"
                    editItemId = null
                },
            )

            // A scan started from this screen resolves on top of it, so the item stays visible
            // behind the answer and nothing is applied until the user says which parts to take.
            val editBarcode = scannedBarcode?.takeIf { barcodeScanTargetId == editItem.id }
            if (editBarcode != null) {
                val dismissScan = {
                    scannedBarcode = null
                    barcodeScanTargetId = null
                    productLookupRepository.clear()
                }
                val lookup = productLookup
                if (lookup is ProductLookupState.Loading) {
                    BarcodeLookupProgressDialog(barcode = editBarcode, onCancel = dismissScan)
                } else {
                    ScannedProductDialog(
                        item = editItem,
                        barcode = editBarcode,
                        product = (lookup as? ProductLookupState.Found)?.product,
                        notice = when (lookup) {
                            is ProductLookupState.NotFound ->
                                "Open Food Facts does not know this barcode. Linking it still " +
                                    "makes the next scan of this pack find this item."
                            is ProductLookupState.Error -> lookup.message
                            else -> null
                        },
                        onApply = { useName, usePackSize ->
                            val product = (lookup as? ProductLookupState.Found)?.product
                            repository.setInventoryItemBarcode(editItem.id, editBarcode)
                            if (useName) {
                                product?.name?.takeIf(String::isNotBlank)?.let {
                                    repository.renameInventoryItem(editItem.id, it)
                                }
                            }
                            if (usePackSize) {
                                product?.quantity?.let { size ->
                                    repository.setInventoryItemPackageSize(
                                        editItem.id,
                                        size,
                                        product.unit,
                                    )
                                    // Nothing is converted when the unit changes, so a remaining
                                    // "1 pc" would come out as 1 g of a 500 g pack. A pack that
                                    // has only just learned its size starts out full instead.
                                    val relabelled = product.unit != null &&
                                        !product.unit.equals(editItem.unit, ignoreCase = true)
                                    if (relabelled) {
                                        repository.setInventoryItemAmount(editItem.id, size)
                                    }
                                }
                            }
                            dismissScan()
                        },
                        onDismiss = dismissScan,
                    )
                }
            }
            return
        }
        androidx.compose.runtime.LaunchedEffect(activeEditItemId) {
            editItemId = null
            // The editor's own scan cannot outlive the item it was started from; without this its
            // result would sit in state and never be shown again.
            if (barcodeScanTargetId == activeEditItemId) {
                scannedBarcode = null
                barcodeScanTargetId = null
                productLookupRepository.clear()
            }
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
            edekaRepo = edekaRepository,
            onConnectEdeka = { edekaAuthRequest = edekaRepository.createAuthRequest() },
            onOpenEdekaReceipts = { showEdekaImport = true },
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
            recipeEditorOpen = false
            editorRecipeId = null
        }
        RecipeEditorScreen(
            initial = editorRecipeId?.let { id -> allRecipes.firstOrNull { it.id == id } },
            onSave = { recipe ->
                recipesRepository.saveUserRecipe(recipe)
                recipeEditorOpen = false
                editorRecipeId = null
                openRecipeId = recipe.id
            },
            onBack = {
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
            val recipeStats = remember(recipeCookLog, recipe.id) {
                RecipeLibrary().statsFor(recipeCookLog)[recipe.id] ?: RecipeStats.NONE
            }
            RecipeDetailScreen(
                match = match,
                favorite = recipe.id in favoriteRecipeIds,
                stats = recipeStats,
                nowEpochMillis = System.currentTimeMillis(),
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
                onCooked = { consumptions, rating ->
                    repository.consumeForRecipe(recipe.id, recipe.title, consumptions)
                    recipesRepository.recordCooked(
                        recipeId = recipe.id,
                        recipeTitle = recipe.title,
                        householdId = activeHouseholdId,
                        rating = rating,
                    )
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
                // Curated recipes ship with the app and would come straight back on the next
                // start, so only the two locally-owned sources offer a delete.
                onDelete = if (recipe.source != RecipeSource.CURATED) {
                    {
                        recipesRepository.deleteRecipe(recipe.id)
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
                collections = recipeCollections,
                onSetInCollection = { collectionId, included ->
                    recipesRepository.setRecipeInCollection(collectionId, recipe.id, included)
                },
                onCreateCollection = { name ->
                    val created = recipesRepository.createCollection(name)
                    recipesRepository.setRecipeInCollection(created.id, recipe.id, included = true)
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
                edekaConnected = edekaAccount != null,
                receiptSyncState = receiptSyncState,
                onSyncReceipts = { scope.launch { receiptSyncer.syncAll() } },
                onReceiptSyncResultShown = receiptSyncer::acknowledge,
                onImportRewe = { showReweImport = true },
                onImportEdeka = { showEdekaImport = true },
                onTakeReceiptPhoto = ::takeReceiptPhoto,
                onPickReceiptPhoto = {
                    receiptPhotoPickerLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
                onScanLongReceipt = ::beginScan,
                onScanBarcode = {
                    barcodeScanTargetId = null
                    barcodeScanOpen = true
                },
                onDestination = { destination = it },
                onOpenInventoryAt = { location ->
                    inventoryLocationRequest = location
                    destination = TopLevelDestination.INVENTORY
                },
                inventoryLocationRequest = inventoryLocationRequest,
                onInventoryLocationRequestConsumed = { inventoryLocationRequest = null },
                onOpenReceipt = { receiptDetailId = it },
                onEditItem = { editItemId = it },
                inventoryMessage = inventoryMessage,
                onInventoryMessageConsumed = { inventoryMessage = null },
                onOpenSettings = { showSettings = true },
                recipesRepository = recipesRepository,
                userRecipes = userRecipes,
                generatedRecipes = generatedRecipes,
                favoriteRecipeIds = favoriteRecipeIds,
                recipeCookLog = recipeCookLog,
                recipeCollections = recipeCollections,
                recipeReviewDraft = recipeReviewDraft,
                recipeRecentSources = recipeRecentSources,
                recipeGenerationHistory = recipeGenerationHistory,
                lastGenerationId = lastGenerationId,
                recipeScanSlow = ocrEngine != OcrEngine.ML_KIT,
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

    // A dialog rather than another early-return overlay: the scan can start from Home or from
    // Inventory, and confirming it should leave that tab where it was underneath.
    val pendingBarcode = scannedBarcode?.takeIf { barcodeScanTargetId == null }
    if (pendingBarcode != null) {
        val dismiss = {
            scannedBarcode = null
            productLookupRepository.clear()
        }
        val lookup = productLookup
        if (lookup is ProductLookupState.Loading) {
            BarcodeLookupProgressDialog(barcode = pendingBarcode, onCancel = dismiss)
        } else {
            AddInventoryItemDialog(
                prefill = inventoryItemPrefill(
                    barcode = pendingBarcode,
                    // A pack already in the inventory outranks Open Food Facts: whatever the user
                    // called it last time is what they will recognise in the list.
                    known = repository.inventoryItemByBarcode(pendingBarcode),
                    product = (lookup as? ProductLookupState.Found)?.product,
                ),
                notice = when (lookup) {
                    is ProductLookupState.NotFound ->
                        "Open Food Facts does not know this barcode — add the details yourself."
                    is ProductLookupState.Error -> lookup.message
                    else -> null
                },
                onDismiss = dismiss,
                onSave = { item ->
                    repository.addInventoryItem(item)
                    dismiss()
                },
            )
        }
    }
}

/** What the confirm dialog opens with. Never empty — the barcode alone is still worth keeping. */
private fun inventoryItemPrefill(
    barcode: String,
    known: InventoryItem?,
    product: OpenFoodFactsProduct?,
): InventoryItemPrefill = InventoryItemPrefill(
    barcode = barcode,
    name = known?.name ?: product?.name.orEmpty(),
    quantity = known?.packageSize ?: product?.quantity,
    unit = known?.unit ?: product?.unit,
)

@Composable
private fun BarcodeLookupProgressDialog(
    barcode: String,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { CircularProgressIndicator() },
        title = { Text("Looking up the product") },
        text = { Text("Barcode $barcode") },
        confirmButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
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
    edekaConnected: Boolean,
    receiptSyncState: ReceiptSyncState,
    onSyncReceipts: () -> Unit,
    onReceiptSyncResultShown: () -> Unit,
    onImportRewe: () -> Unit,
    onImportEdeka: () -> Unit,
    onTakeReceiptPhoto: () -> Unit,
    onPickReceiptPhoto: () -> Unit,
    onScanLongReceipt: () -> Unit,
    onScanBarcode: () -> Unit,
    onDestination: (TopLevelDestination) -> Unit,
    onOpenInventoryAt: (StorageLocation?) -> Unit,
    inventoryLocationRequest: StorageLocation?,
    onInventoryLocationRequestConsumed: () -> Unit,
    onOpenReceipt: (String) -> Unit,
    onEditItem: (String) -> Unit,
    inventoryMessage: String?,
    onInventoryMessageConsumed: () -> Unit,
    onOpenSettings: () -> Unit,
    recipesRepository: RecipesRepository,
    userRecipes: List<Recipe>,
    generatedRecipes: List<Recipe>,
    favoriteRecipeIds: Set<String>,
    recipeCookLog: List<CookEntry>,
    recipeCollections: List<RecipeCollection>,
    recipeReviewDraft: RecipeReviewDraft?,
    recipeRecentSources: List<RecentSource>,
    recipeGenerationHistory: List<RecipeGenerationResult>,
    lastGenerationId: String?,
    recipeScanSlow: Boolean,
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
                onTakeReceiptPhoto = onTakeReceiptPhoto,
                onPickReceiptPhoto = onPickReceiptPhoto,
                onScanLongReceipt = onScanLongReceipt,
                onAddItem = repository::addInventoryItem,
                onScanBarcode = onScanBarcode,
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
                onScanBarcode = onScanBarcode,
                onRemoveItem = { id, reason -> repository.removeInventoryItem(id, reason) },
                onEditItem = onEditItem,
                pendingMessage = inventoryMessage,
                onPendingMessageConsumed = onInventoryMessageConsumed,
                onAdjustQuantity = { id, delta ->
                    repository.adjustInventoryItemQuantity(id, delta)
                },
                onUndoRemove = { item -> repository.restoreRemovedInventoryItem(item) },
                onOpenSettings = onOpenSettings,
                modifier = Modifier.fillMaxSize(),
            )
            TopLevelDestination.RECIPES -> RecipesHub(
                state = rememberRecipesHubState(),
                inventory = inventory,
                recipes = userRecipes + generatedRecipes + recipesRepository.curatedRecipes,
                favoriteIds = favoriteRecipeIds,
                cookLog = recipeCookLog,
                collections = recipeCollections,
                reviewDraft = recipeReviewDraft,
                recentSources = recipeRecentSources,
                generationHistory = recipeGenerationHistory,
                lastGenerationId = lastGenerationId,
                imagesDir = recipesRepository.imagesDir,
                imageVersion = recipeImageVersion,
                aiEnabled = aiEnabled,
                aiBusy = aiBusy,
                aiError = aiError,
                slowOcrEngine = recipeScanSlow,
                actions = RecipesHubActions(
                    onOpenRecipe = onOpenRecipe,
                    onToggleFavorite = recipesRepository::toggleFavorite,
                    onWriteRecipe = onWriteRecipe,
                    onScanRecipeCamera = onScanRecipeCamera,
                    onScanRecipeGallery = onScanRecipeGallery,
                    onImportRecipeUrl = onImportRecipeUrl,
                    onGenerateIdeas = { recipesRepository.generateIdeas(inventory) },
                    onGenerateRecipe = { request, photo ->
                        recipesRepository.generateRecipeFromRequest(request, photo, inventory)
                    },
                    onGenerateRecipes = { request -> recipesRepository.generateRecipes(request, inventory) },
                    onClearLastGenerationId = recipesRepository::clearLastGenerationId,
                    onSaveReviewDraft = { recipe ->
                        val saved = recipesRepository.saveReviewedRecipe(recipe)
                        onOpenRecipe(saved.id)
                    },
                    onDiscardReviewDraft = recipesRepository::discardReviewDraft,
                    onCreateCollection = { recipesRepository.createCollection(it) },
                    onRenameCollection = recipesRepository::renameCollection,
                    onDeleteCollection = recipesRepository::deleteCollection,
                    onOpenSettings = onOpenSettings,
                ),
                modifier = Modifier.fillMaxSize(),
            )
            TopLevelDestination.RECEIPTS -> ReceiptsScreen(
                receipts = receipts,
                bioCompanyConnected = bioCompanyConnected,
                reweConnected = reweConnected,
                edekaConnected = edekaConnected,
                syncState = receiptSyncState,
                onSync = onSyncReceipts,
                onSyncResultShown = onReceiptSyncResultShown,
                onTakeReceiptPhoto = onTakeReceiptPhoto,
                onPickReceiptPhoto = onPickReceiptPhoto,
                onScanLongReceipt = onScanLongReceipt,
                onOpenReceipt = onOpenReceipt,
                onImportBioCompany = onOpenBioCompanyReceipts,
                onImportRewe = onImportRewe,
                onImportEdeka = onImportEdeka,
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
