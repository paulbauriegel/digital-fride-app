package com.example.groceriesapp.feature.more

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.ui.components.SectionHeader
import com.example.groceriesapp.ui.theme.spacing
import com.example.groceriesapp.vision.ocr.GlmOcrModelManager
import com.example.groceriesapp.vision.ocr.GlmOcrModelPart
import com.example.groceriesapp.vision.ocr.GlmOcrModelState
import com.example.groceriesapp.vision.ocr.GlmOcrLiteRtModelManager
import com.example.groceriesapp.vision.ocr.GlmOcrLiteRtModelPart
import com.example.groceriesapp.vision.ocr.GlmOcrLiteRtModelState
import com.example.groceriesapp.vision.ocr.OcrEngine
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    darkTheme: Boolean,
    onDarkThemeChanged: (Boolean) -> Unit,
    ocrEngine: OcrEngine,
    onOcrEngineChanged: (OcrEngine) -> Unit,
    receiptCount: Int,
    inventoryCount: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val modelManager = remember(context) { GlmOcrModelManager(context) }
    val liteRtModelManager = remember(context) { GlmOcrLiteRtModelManager(context) }
    var modelState by remember { mutableStateOf(modelManager.state()) }
    var liteRtModelState by remember { mutableStateOf(liteRtModelManager.state()) }
    var importingPart by remember { mutableStateOf<GlmOcrModelPart?>(null) }
    var importingLiteRtPart by remember { mutableStateOf<GlmOcrLiteRtModelPart?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var liteRtImportError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val importModel: (GlmOcrModelPart, Uri) -> Unit = { part, uri ->
        importingPart = part
        importError = null
        coroutineScope.launch {
            runCatching { modelManager.import(part, uri) }
                .onSuccess { modelState = it }
                .onFailure { error ->
                    importError = error.message ?: "Could not import the selected GGUF file."
                }
            importingPart = null
        }
    }
    val decoderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) importModel(GlmOcrModelPart.DECODER, uri)
    }
    val projectorPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) importModel(GlmOcrModelPart.PROJECTOR, uri)
    }
    val importLiteRtModel: (GlmOcrLiteRtModelPart, Uri) -> Unit = { part, uri ->
        importingLiteRtPart = part
        liteRtImportError = null
        coroutineScope.launch {
            runCatching { liteRtModelManager.import(part, uri) }
                .onSuccess { liteRtModelState = it }
                .onFailure { error ->
                    liteRtImportError = error.message ?: "Could not import the LiteRT model."
                }
            importingLiteRtPart = null
        }
    }
    val liteRtVisionPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) importLiteRtModel(GlmOcrLiteRtModelPart.VISION, uri)
    }
    val liteRtDecoderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) importLiteRtModel(GlmOcrLiteRtModelPart.DECODER, uri)
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("More") }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = MaterialTheme.spacing.medium,
                end = MaterialTheme.spacing.medium,
                bottom = 112.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            item {
                LocalFirstCard(receiptCount, inventoryCount, ocrEngine)
            }
            item {
                SectionHeader("Appearance")
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MaterialTheme.spacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
                    ) {
                        Icon(
                            if (darkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                            contentDescription = null,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dark theme", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Designed for tracked receipt panoramas",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = darkTheme,
                            onCheckedChange = onDarkThemeChanged,
                        )
                    }
                }
            }
            item {
                SectionHeader("Text recognition")
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
                        enabled = modelState.ready && importingPart == null,
                        description = if (modelState.ready) {
                            "Higher-quality generative OCR using the imported local GGUF models."
                        } else {
                            "Import the decoder and multimodal projector to enable GLM-OCR."
                        },
                        onSelect = { onOcrEngineChanged(OcrEngine.GLM_OCR) },
                    )
                    GlmOcrModelCard(
                        state = modelState,
                        importingPart = importingPart,
                        errorMessage = importError,
                        onImportDecoder = {
                            decoderPicker.launch(arrayOf("application/octet-stream", "*/*"))
                        },
                        onImportProjector = {
                            projectorPicker.launch(arrayOf("application/octet-stream", "*/*"))
                        },
                    )
                    OcrEngineCard(
                        engine = OcrEngine.GLM_OCR_LITERT,
                        selected = ocrEngine == OcrEngine.GLM_OCR_LITERT,
                        enabled = liteRtModelState.ready && importingLiteRtPart == null,
                        description = if (liteRtModelState.ready) {
                            "Experimental GLM-OCR using the local LiteRT vision and cached decoder models."
                        } else {
                            "Install the LiteRT vision encoder and decoder to enable this engine."
                        },
                        onSelect = { onOcrEngineChanged(OcrEngine.GLM_OCR_LITERT) },
                    )
                    GlmOcrLiteRtModelCard(
                        state = liteRtModelState,
                        importingPart = importingLiteRtPart,
                        errorMessage = liteRtImportError,
                        onImportVision = {
                            liteRtVisionPicker.launch(arrayOf("application/octet-stream", "*/*"))
                        },
                        onImportDecoder = {
                            liteRtDecoderPicker.launch(arrayOf("application/octet-stream", "*/*"))
                        },
                    )
                }
            }
            item {
                SectionHeader("Capture tips")
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(MaterialTheme.spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
                    ) {
                        TipRow(Icons.Default.Straighten, "Keep the receipt flat")
                        TipRow(Icons.Default.LightMode, "Use even light without glare")
                        TipRow(Icons.AutoMirrored.Filled.ReceiptLong, "Move freely and let the live paper outline guide you")
                    }
                }
            }
            item {
                SectionHeader("On the roadmap")
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(MaterialTheme.spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        Text(
                            "Shared fridges • barcode lookup • shopping suggestions • meal planning",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "These need separate product data and sync decisions. They are not silently enabled in this local scanner.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalFirstCard(
    receiptCount: Int,
    inventoryCount: Int,
    ocrEngine: OcrEngine,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Text("Private by default", style = MaterialTheme.typography.titleLarge)
            Text(
                "Receipt images, recognized text, and inventory remain in app-private storage. " +
                    "${ocrEngine.displayName} runs entirely on this device.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large)) {
                Text("$receiptCount receipts", style = MaterialTheme.typography.labelLarge)
                Text("$inventoryCount items", style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Default.CloudOff, contentDescription = "No cloud sync")
            }
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
                MaterialTheme.colorScheme.surfaceContainer
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
private fun GlmOcrModelCard(
    state: GlmOcrModelState,
    importingPart: GlmOcrModelPart?,
    errorMessage: String?,
    onImportDecoder: () -> Unit,
    onImportProjector: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text("GLM-OCR model files", style = MaterialTheme.typography.titleMedium)
            Text(
                "Use the matching ggml-org GLM-OCR GGUF decoder and mmproj pair. Files are copied " +
                    "into app-private storage and never uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelFileRow(
                label = "Decoder",
                name = state.decoderName,
                sizeBytes = state.decoderSizeBytes,
            )
            OutlinedButton(
                onClick = onImportDecoder,
                enabled = importingPart == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ImportIcon(importingPart == GlmOcrModelPart.DECODER)
                Spacer(Modifier.width(8.dp))
                Text(if (state.decoderSizeBytes == null) "Import decoder GGUF" else "Replace decoder")
            }
            ModelFileRow(
                label = "Vision projector",
                name = state.projectorName,
                sizeBytes = state.projectorSizeBytes,
            )
            OutlinedButton(
                onClick = onImportProjector,
                enabled = importingPart == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ImportIcon(importingPart == GlmOcrModelPart.PROJECTOR)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.projectorSizeBytes == null) {
                        "Import projector GGUF"
                    } else {
                        "Replace projector"
                    },
                )
            }
            if (!state.runtimeAvailable) {
                Text(
                    "The bundled GLM-OCR runtime requires an arm64 Android device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            errorMessage?.let {
                Text(
                    it,
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
private fun GlmOcrLiteRtModelCard(
    state: GlmOcrLiteRtModelState,
    importingPart: GlmOcrLiteRtModelPart?,
    errorMessage: String?,
    onImportVision: () -> Unit,
    onImportDecoder: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text("GLM-OCR LiteRT files", style = MaterialTheme.typography.titleMedium)
            Text(
                "The fixed 644 × 952 vision encoder and cached decoder stay in this app's " +
                    "on-device model directory. First use can take several minutes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelFileRow(
                label = "Vision encoder",
                name = state.visionSizeBytes?.let {
                    GlmOcrLiteRtModelManager.VISION_FILE
                },
                sizeBytes = state.visionSizeBytes,
            )
            OutlinedButton(
                onClick = onImportVision,
                enabled = importingPart == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ImportIcon(importingPart == GlmOcrLiteRtModelPart.VISION)
                Spacer(Modifier.width(8.dp))
                Text(if (state.visionSizeBytes == null) "Import vision model" else "Replace vision model")
            }
            ModelFileRow(
                label = "Cached decoder",
                name = state.decoderSizeBytes?.let {
                    GlmOcrLiteRtModelManager.DECODER_FILE
                },
                sizeBytes = state.decoderSizeBytes,
            )
            OutlinedButton(
                onClick = onImportDecoder,
                enabled = importingPart == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ImportIcon(importingPart == GlmOcrLiteRtModelPart.DECODER)
                Spacer(Modifier.width(8.dp))
                Text(if (state.decoderSizeBytes == null) "Import decoder model" else "Replace decoder model")
            }
            errorMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
                name ?: "Not imported",
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

@Composable
private fun ImportIcon(importing: Boolean) {
    if (importing) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
    } else {
        Icon(Icons.Default.FolderOpen, contentDescription = null)
    }
}

private fun formatFileSize(bytes: Long): String =
    if (bytes >= 1_000_000_000L) {
        String.format(Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0)
    } else {
        String.format(Locale.ROOT, "%.0f MB", bytes / 1_000_000.0)
    }

@Composable
private fun TipRow(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
