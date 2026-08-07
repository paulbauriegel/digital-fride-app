package com.example.groceriesapp.feature.more

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.ui.components.MerchantBadge
import com.example.groceriesapp.ui.components.SettingsChevron
import com.example.groceriesapp.ui.components.SettingsGroup
import com.example.groceriesapp.ui.components.SettingsRow
import com.example.groceriesapp.ui.components.SettingsRowDivider
import com.example.groceriesapp.ui.components.SettingsSectionHeader
import com.example.groceriesapp.ui.theme.spacing
import com.example.groceriesapp.vision.ocr.GlmOcrModelManager
import com.example.groceriesapp.vision.ocr.GlmOcrModelState
import com.example.groceriesapp.vision.ocr.GlmOcrLiteRtModelManager
import com.example.groceriesapp.vision.ocr.GlmOcrLiteRtModelState
import com.example.groceriesapp.vision.ocr.OcrEngine
import java.util.Locale
import kotlinx.coroutines.launch

/** Sub-screens reachable from the settings list. */
private enum class SettingsDetail(val title: String) {
    BIO_COMPANY("BIO COMPANY"),
    REWE("REWE"),
    TEXT_RECOGNITION("Text recognition"),
    ABOUT("About"),
}

@Composable
fun MoreScreen(
    darkTheme: Boolean,
    onDarkThemeChanged: (Boolean) -> Unit,
    ocrEngine: OcrEngine,
    onOcrEngineChanged: (OcrEngine) -> Unit,
    bioCompanyRepo: com.example.groceriesapp.data.BioCompanyRepository,
    onOpenBioCompanyReceipts: () -> Unit,
    reweRepo: com.example.groceriesapp.data.ReweRepository,
    onConnectRewe: () -> Unit,
    onOpenReweReceipts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val modelManager = remember(context) { GlmOcrModelManager(context) }
    val liteRtModelManager = remember(context) { GlmOcrLiteRtModelManager(context) }
    val modelState = remember { modelManager.state() }
    val liteRtModelState = remember { liteRtModelManager.state() }
    val coroutineScope = rememberCoroutineScope()
    val account by bioCompanyRepo.account.collectAsStateWithLifecycle()
    val loginState by bioCompanyRepo.loginState.collectAsStateWithLifecycle()
    val syncState by bioCompanyRepo.syncState.collectAsStateWithLifecycle()
    val points by bioCompanyRepo.points.collectAsStateWithLifecycle()
    val reweAccount by reweRepo.account.collectAsStateWithLifecycle()
    val reweCredentials by reweRepo.credentials.collectAsStateWithLifecycle()
    val reweLoginState by reweRepo.loginState.collectAsStateWithLifecycle()
    val reweSyncState by reweRepo.syncState.collectAsStateWithLifecycle()
    var reweCredentialError by remember { mutableStateOf<String?>(null) }
    var detail by rememberSaveable { mutableStateOf<SettingsDetail?>(null) }
    val importRewePem: (Uri, (String) -> Result<Unit>) -> Unit = { uri, importer ->
        reweCredentialError = null
        coroutineScope.launch {
            val pem = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }.getOrNull()
            if (pem == null) {
                reweCredentialError = "Could not read the selected file."
            } else {
                importer(pem).onFailure { error ->
                    reweCredentialError = error.message ?: "Could not import the file."
                }
            }
        }
    }
    val reweCertificatePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) importRewePem(uri, reweRepo::importCertificate)
    }
    val rewePrivateKeyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) importRewePem(uri, reweRepo::importPrivateKey)
    }

    val openDetail = detail
    if (openDetail != null) {
        BackHandler { detail = null }
        SettingsDetailScaffold(
            title = openDetail.title,
            onBack = { detail = null },
            modifier = modifier,
        ) {
            when (openDetail) {
                SettingsDetail.BIO_COMPANY -> BioCompanyAccountCard(
                    account = account,
                    points = points,
                    loginState = loginState,
                    syncState = syncState,
                    onLogin = { email, password ->
                        coroutineScope.launch {
                            bioCompanyRepo.login(email, password)
                        }
                    },
                    onLogout = {
                        coroutineScope.launch {
                            bioCompanyRepo.logout()
                        }
                    },
                    onFetchReceipts = {
                        coroutineScope.launch {
                            bioCompanyRepo.fetchReceipts()
                            bioCompanyRepo.fetchPoints()
                        }
                    },
                    onOpenReceipts = onOpenBioCompanyReceipts,
                )
                SettingsDetail.REWE -> ReweAccountCard(
                    account = reweAccount,
                    credentials = reweCredentials,
                    loginState = reweLoginState,
                    syncState = reweSyncState,
                    credentialError = reweCredentialError,
                    onImportCertificate = {
                        reweCertificatePicker.launch(arrayOf("*/*"))
                    },
                    onImportPrivateKey = {
                        rewePrivateKeyPicker.launch(arrayOf("*/*"))
                    },
                    onConnect = onConnectRewe,
                    onDisconnect = {
                        coroutineScope.launch { reweRepo.logout() }
                    },
                    onFetchReceipts = {
                        coroutineScope.launch { reweRepo.fetchReceipts() }
                    },
                    onOpenReceipts = onOpenReweReceipts,
                )
                SettingsDetail.TEXT_RECOGNITION -> TextRecognitionDetail(
                    ocrEngine = ocrEngine,
                    onOcrEngineChanged = onOcrEngineChanged,
                    modelState = modelState,
                    liteRtModelState = liteRtModelState,
                )
                SettingsDetail.ABOUT -> AttributionCard()
            }
        }
        return
    }

    SettingsHome(
        darkTheme = darkTheme,
        onDarkThemeChanged = onDarkThemeChanged,
        ocrEngine = ocrEngine,
        bioCompanyConnected = account != null,
        reweConnected = reweAccount != null,
        onOpenDetail = { detail = it },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHome(
    darkTheme: Boolean,
    onDarkThemeChanged: (Boolean) -> Unit,
    ocrEngine: OcrEngine,
    bioCompanyConnected: Boolean,
    reweConnected: Boolean,
    onOpenDetail: (SettingsDetail) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = MaterialTheme.spacing.medium,
                end = MaterialTheme.spacing.medium,
                bottom = 112.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
        ) {
            item {
                Column {
                    SettingsSectionHeader(
                        title = "Accounts & Integrations",
                        supportingText = "Connect your grocery accounts to import receipts.",
                    )
                    SettingsGroup {
                        AccountRow(
                            merchant = "BIO COMPANY",
                            connected = bioCompanyConnected,
                            onClick = { onOpenDetail(SettingsDetail.BIO_COMPANY) },
                        )
                        SettingsRowDivider(inset = 72.dp)
                        AccountRow(
                            merchant = "REWE",
                            connected = reweConnected,
                            onClick = { onOpenDetail(SettingsDetail.REWE) },
                        )
                    }
                }
            }
            item {
                Column {
                    SettingsSectionHeader("Appearance")
                    SettingsGroup {
                        SettingsRow(
                            headline = "Dark theme",
                            supportingText = "Designed for tracked receipt panoramas",
                            leadingContent = {
                                Icon(
                                    if (darkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                                    contentDescription = null,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = darkTheme,
                                    onCheckedChange = onDarkThemeChanged,
                                )
                            },
                            onClick = { onDarkThemeChanged(!darkTheme) },
                        )
                    }
                }
            }
            item {
                Column {
                    SettingsSectionHeader("Scanning")
                    SettingsGroup {
                        SettingsRow(
                            headline = "Text recognition",
                            supportingText = ocrEngine.displayName,
                            leadingContent = {
                                Icon(Icons.Default.Memory, contentDescription = null)
                            },
                            trailingContent = { SettingsChevron() },
                            onClick = { onOpenDetail(SettingsDetail.TEXT_RECOGNITION) },
                        )
                    }
                }
            }
            item {
                Column {
                    SettingsSectionHeader("App")
                    SettingsGroup {
                        SettingsRow(
                            headline = "About",
                            supportingText = "Icon and shelf-life data attributions",
                            leadingContent = {
                                Icon(Icons.Default.Info, contentDescription = null)
                            },
                            trailingContent = { SettingsChevron() },
                            onClick = { onOpenDetail(SettingsDetail.ABOUT) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    merchant: String,
    connected: Boolean,
    onClick: () -> Unit,
) {
    SettingsRow(
        headline = merchant,
        supportingText = if (connected) "Connected" else "Not connected",
        supportingTextColor = if (connected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        leadingContent = { MerchantBadge(merchant, size = 40.dp) },
        trailingContent = { SettingsChevron() },
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDetailScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = MaterialTheme.spacing.medium,
                end = MaterialTheme.spacing.medium,
                bottom = 112.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            item { content() }
        }
    }
}

@Composable
private fun TextRecognitionDetail(
    ocrEngine: OcrEngine,
    onOcrEngineChanged: (OcrEngine) -> Unit,
    modelState: GlmOcrModelState,
    liteRtModelState: GlmOcrLiteRtModelState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        OcrEngineCard(
            engine = OcrEngine.ML_KIT,
            selected = ocrEngine == OcrEngine.ML_KIT,
            enabled = true,
            description = "Fast bundled Latin text recognition with line positions.",
            onSelect = { onOcrEngineChanged(OcrEngine.ML_KIT) },
        )
        OcrEngineCard(
            engine = OcrEngine.GLM_OCR,
            selected = ocrEngine == OcrEngine.GLM_OCR,
            enabled = modelState.ready,
            description = if (modelState.ready) {
                "Higher-quality generative OCR using the bundled GGUF models."
            } else {
                "Not installed on this device yet."
            },
            onSelect = { onOcrEngineChanged(OcrEngine.GLM_OCR) },
        )
        if (ocrEngine == OcrEngine.GLM_OCR) {
            GlmOcrModelCard(state = modelState)
        }
        OcrEngineCard(
            engine = OcrEngine.GLM_OCR_LITERT,
            selected = ocrEngine == OcrEngine.GLM_OCR_LITERT,
            enabled = liteRtModelState.ready,
            description = if (liteRtModelState.ready) {
                "Experimental GLM-OCR using the bundled LiteRT vision and decoder models."
            } else {
                "Not installed on this device yet."
            },
            onSelect = { onOcrEngineChanged(OcrEngine.GLM_OCR_LITERT) },
        )
        if (ocrEngine == OcrEngine.GLM_OCR_LITERT) {
            GlmOcrLiteRtModelCard(state = liteRtModelState)
        }
    }
}

@Composable
private fun AttributionCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text("Grocery icons", style = MaterialTheme.typography.titleSmall)
            Text(
                "Icons by OpenMoji (openmoji.org), licensed under CC BY-SA 4.0.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Shelf-life estimates", style = MaterialTheme.typography.titleSmall)
            Text(
                "Storage times derived from the USDA FSIS FoodKeeper dataset (public domain), " +
                    "with stricter values for a few items where German practice differs. " +
                    "Estimates only — the date printed on the packaging always takes precedence.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OcrEngineCard(
    engine: OcrEngine,
    selected: Boolean,
    enabled: Boolean,
    description: String,
    onSelect: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Icon(
                if (engine == OcrEngine.ML_KIT) Icons.Default.Memory else Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(engine.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RadioButton(
                selected = selected,
                onClick = onSelect,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun GlmOcrModelCard(state: GlmOcrModelState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text("GLM-OCR model files", style = MaterialTheme.typography.titleMedium)
            Text(
                "The GGUF decoder and mmproj pair are provisioned with the app and can't be " +
                    "changed on-device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelFileRow(
                label = "Decoder",
                name = state.decoderName,
                sizeBytes = state.decoderSizeBytes,
            )
            ModelFileRow(
                label = "Vision projector",
                name = state.projectorName,
                sizeBytes = state.projectorSizeBytes,
            )
            if (!state.runtimeAvailable) {
                Text(
                    "The bundled GLM-OCR runtime requires an arm64 Android device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "GLM-OCR returns text without ML Kit line boxes, so receipt highlighting is " +
                    "unavailable for GLM-OCR results.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GlmOcrLiteRtModelCard(state: GlmOcrLiteRtModelState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text("GLM-OCR LiteRT files", style = MaterialTheme.typography.titleMedium)
            Text(
                "The fixed 644 × 952 vision encoder and cached decoder are provisioned with the " +
                    "app and can't be changed on-device. First use can take several minutes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelFileRow(
                label = "Vision encoder",
                name = state.visionSizeBytes?.let { GlmOcrLiteRtModelManager.VISION_FILE },
                sizeBytes = state.visionSizeBytes,
            )
            ModelFileRow(
                label = "Cached decoder",
                name = state.decoderSizeBytes?.let { GlmOcrLiteRtModelManager.DECODER_FILE },
                sizeBytes = state.decoderSizeBytes,
            )
            Text(
                "LiteRT output has no line boxes, so receipt highlighting is unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelFileRow(
    label: String,
    name: String?,
    sizeBytes: Long?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                name ?: "Not installed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        sizeBytes?.let {
            Text(
                formatFileSize(it),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String =
    if (bytes >= 1_000_000_000L) {
        String.format(Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0)
    } else {
        String.format(Locale.ROOT, "%.0f MB", bytes / 1_000_000.0)
    }
