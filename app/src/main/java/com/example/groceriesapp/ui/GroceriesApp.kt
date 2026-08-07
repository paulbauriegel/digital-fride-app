package com.example.groceriesapp.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.example.groceriesapp.data.network.BioCompanyReceiptDetail
import com.example.groceriesapp.data.network.BioCompanyReceiptEntry
import com.example.groceriesapp.data.network.ReweAuthRequest
import com.example.groceriesapp.feature.biocompany.BioCompanyReceiptsScreen
import com.example.groceriesapp.feature.rewe.ReweLoginScreen
import com.example.groceriesapp.feature.rewe.ReweReceiptsScreen
import kotlinx.coroutines.launch
import com.example.groceriesapp.feature.inventory.InventoryScreen
import com.example.groceriesapp.feature.more.MoreScreen
import com.example.groceriesapp.feature.overview.OverviewScreen
import com.example.groceriesapp.feature.receipts.ReceiptsScreen
import com.example.groceriesapp.feature.review.ReceiptReviewScreen
import com.example.groceriesapp.feature.scanner.ScanViewModel
import com.example.groceriesapp.feature.scanner.ScanViewModelFactory
import com.example.groceriesapp.feature.scanner.ScannerScreen
import com.example.groceriesapp.vision.editing.ReceiptImageEditor
import com.example.groceriesapp.vision.ocr.OcrEngine
import com.example.groceriesapp.vision.ocr.ReceiptOcrRerunner
import com.example.groceriesapp.vision.ocr.receiptOcrRecognizer
import java.io.File
import java.util.UUID

private enum class TopLevelDestination(
    val label: String,
    val icon: ImageVector,
) {
    OVERVIEW("Home", Icons.Default.Home),
    INVENTORY("Inventory", Icons.Default.Inventory2),
    RECEIPTS("Receipts", Icons.AutoMirrored.Filled.ReceiptLong),
    MORE("Settings", Icons.Default.Menu),
}

@Composable
fun GroceriesApp(
    repository: GroceriesRepository,
    bioCompanyRepository: BioCompanyRepository,
    reweRepository: ReweRepository,
    widthSizeClass: WindowWidthSizeClass,
    darkTheme: Boolean,
    ocrEngine: OcrEngine,
    onDarkThemeChanged: (Boolean) -> Unit,
    onOcrEngineChanged: (OcrEngine) -> Unit,
) {
    val receipts by repository.receipts.collectAsStateWithLifecycle()
    val inventory by repository.inventory.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(TopLevelDestination.OVERVIEW) }
    var scanKey by remember {
        mutableStateOf(repository.activeScanSessionId())
    }
    var reviewReceiptId by rememberSaveable { mutableStateOf<String?>(null) }
    var showBioCompanyImport by remember { mutableStateOf(false) }
    var showReweImport by remember { mutableStateOf(false) }
    var reweAuthRequest by remember { mutableStateOf<ReweAuthRequest?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val expanded = widthSizeClass != WindowWidthSizeClass.Compact
    val ocrRecognizer = remember(context, ocrEngine) {
        receiptOcrRecognizer(context, ocrEngine)
    }

    fun beginScan() {
        reviewReceiptId = null
        scanKey = UUID.randomUUID().toString()
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
            factory = ScanViewModelFactory(repository, activeScanKey, ocrRecognizer),
        )
        val scanState by scanViewModel.uiState.collectAsStateWithLifecycle()
        ScannerScreen(
            state = scanState,
            viewModel = scanViewModel,
            onClose = { scanKey = null },
            onCompleted = { receiptId ->
                scanKey = null
                reviewReceiptId = receiptId
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

    val activeReviewId = reviewReceiptId
    if (activeReviewId != null) {
        val receipt = receipts.firstOrNull { it.id == activeReviewId }
        if (receipt != null) {
            ReceiptReviewScreen(
                receipt = receipt,
                expanded = expanded,
                onBack = { reviewReceiptId = null },
                onShare = { shareReceipt(context, receipt.previewPath) },
                onDelete = {
                    repository.deleteReceipt(receipt.id)
                    reviewReceiptId = null
                },
                onRescan = ::beginScan,
                onApplyImageEdit = { edit ->
                    val updatedReceipt = ReceiptImageEditor().apply(receipt, edit)
                    repository.saveReceipt(updatedReceipt)
                },
                onRerunOcr = {
                    val updatedReceipt = ReceiptOcrRerunner(
                        recognizer = ocrRecognizer,
                    ).rerun(receipt)
                    repository.saveReceipt(updatedReceipt)
                },
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
            )
            return
        }
        androidx.compose.runtime.LaunchedEffect(activeReviewId) {
            reviewReceiptId = null
        }
    }

    if (expanded) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(
                header = {
                    FloatingActionButton(
                        onClick = ::beginScan,
                        modifier = Modifier.padding(vertical = 12.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Scan receipt")
                    }
                },
            ) {
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
                TopLevelContent(
                    destination = destination,
                    repository = repository,
                    bioCompanyRepository = bioCompanyRepository,
                    receipts = receipts,

                    inventory = inventory,
                    darkTheme = darkTheme,
                    ocrEngine = ocrEngine,
                    onDarkThemeChanged = onDarkThemeChanged,
                    onOcrEngineChanged = onOcrEngineChanged,
                    onScan = ::beginScan,
                    onDestination = { destination = it },
                    onOpenReceipt = { reviewReceiptId = it },
                    onOpenBioCompanyReceipts = { showBioCompanyImport = true },
                    reweRepository = reweRepository,
                    onConnectRewe = { reweAuthRequest = reweRepository.createAuthRequest() },
                    onOpenReweReceipts = { showReweImport = true },
                    modifier = Modifier
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
                    TopLevelDestination.entries.forEachIndexed { index, item ->
                        if (index == 2) {
                            Box(
                                modifier = Modifier.size(72.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                FloatingActionButton(
                                    onClick = ::beginScan,
                                    shape = CircleShape,
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    elevation = FloatingActionButtonDefaults.elevation(
                                        defaultElevation = 0.dp,
                                    ),
                                    modifier = Modifier.size(52.dp),
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Scan receipt")
                                }
                            }
                        }
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
                TopLevelContent(
                    destination = destination,
                    repository = repository,
                    bioCompanyRepository = bioCompanyRepository,
                    receipts = receipts,

                    inventory = inventory,
                    darkTheme = darkTheme,
                    ocrEngine = ocrEngine,
                    onDarkThemeChanged = onDarkThemeChanged,
                    onOcrEngineChanged = onOcrEngineChanged,
                    onScan = ::beginScan,
                    onDestination = { destination = it },
                    onOpenReceipt = { reviewReceiptId = it },
                    onOpenBioCompanyReceipts = { showBioCompanyImport = true },
                    reweRepository = reweRepository,
                    onConnectRewe = { reweAuthRequest = reweRepository.createAuthRequest() },
                    onOpenReweReceipts = { showReweImport = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
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
    bioCompanyRepository: BioCompanyRepository,
    receipts: List<com.example.groceriesapp.data.DigitalReceipt>,
    inventory: List<com.example.groceriesapp.data.InventoryItem>,
    darkTheme: Boolean,
    ocrEngine: OcrEngine,
    onDarkThemeChanged: (Boolean) -> Unit,
    onOcrEngineChanged: (OcrEngine) -> Unit,
    onScan: () -> Unit,
    onDestination: (TopLevelDestination) -> Unit,
    onOpenReceipt: (String) -> Unit,
    onOpenBioCompanyReceipts: () -> Unit,
    reweRepository: ReweRepository,
    onConnectRewe: () -> Unit,
    onOpenReweReceipts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        when (destination) {
            TopLevelDestination.OVERVIEW -> OverviewScreen(
                inventory = inventory,
                receipts = receipts,
                onScanReceipt = onScan,
                onOpenInventory = { onDestination(TopLevelDestination.INVENTORY) },
                onOpenReceipts = { onDestination(TopLevelDestination.RECEIPTS) },
                onOpenReceipt = onOpenReceipt,
                modifier = Modifier.fillMaxSize(),
            )
            TopLevelDestination.INVENTORY -> InventoryScreen(
                inventory = inventory,
                onAddItem = repository::addInventoryItem,
                onRemoveItem = { id, reason -> repository.removeInventoryItem(id, reason) },
                onMarkOpened = repository::markInventoryItemOpened,
                onSetLocation = { id, location ->
                    repository.setInventoryItemStorageLocation(id, location)
                },
                onSetFillLevel = { id, level ->
                    repository.setInventoryItemFillLevel(id, level)
                },
                onAdjustQuantity = { id, delta ->
                    repository.adjustInventoryItemQuantity(id, delta)
                },
                modifier = Modifier.fillMaxSize(),
            )
            TopLevelDestination.RECEIPTS -> ReceiptsScreen(
                receipts = receipts,
                onScanReceipt = onScan,
                onOpenReceipt = onOpenReceipt,
                onImportReceipt = onOpenBioCompanyReceipts,
                modifier = Modifier.fillMaxSize(),
            )
            TopLevelDestination.MORE -> MoreScreen(
                darkTheme = darkTheme,
                onDarkThemeChanged = onDarkThemeChanged,
                ocrEngine = ocrEngine,
                onOcrEngineChanged = onOcrEngineChanged,
                bioCompanyRepo = bioCompanyRepository,
                onOpenBioCompanyReceipts = onOpenBioCompanyReceipts,
                reweRepo = reweRepository,
                onConnectRewe = onConnectRewe,
                onOpenReweReceipts = onOpenReweReceipts,
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
