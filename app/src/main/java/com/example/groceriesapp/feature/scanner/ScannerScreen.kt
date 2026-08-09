package com.example.groceriesapp.feature.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.groceriesapp.ui.theme.scanStatusColors
import com.example.groceriesapp.ui.theme.spacing
import com.example.groceriesapp.vision.ProcessingStage
import com.example.groceriesapp.vision.capture.LiveCaptureGuidance
import com.example.groceriesapp.vision.capture.ReceiptBoundaryOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ScannerScreen(
    state: ScanUiState,
    viewModel: ScanViewModel,
    onClose: () -> Unit,
    onCompleted: (String) -> Unit,
) {
    val context = LocalContext.current
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraPermissionGranted = granted }
    var showDiscardDialog by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val requestClose = {
        if (state.acceptedFramePaths.isEmpty()) {
            viewModel.discardSession()
            onClose()
        } else {
            showDiscardDialog = true
        }
    }

    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(state.acceptedSections) {
        if (state.acceptedSections > 0) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    LaunchedEffect(state.observation.guidance) {
        if (state.observation.guidance in setOf(
                LiveCaptureGuidance.MOVE_SLOWER,
                LiveCaptureGuidance.TRACKING_LOST,
            )
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    LaunchedEffect(state.completedReceiptId) {
        state.completedReceiptId?.let(onCompleted)
    }
    BackHandler(
        enabled = state.phase != ScanPhase.COMPLETE,
        onBack = requestClose,
    )

    when (state.phase) {
        ScanPhase.PROCESSING, ScanPhase.COMPLETE -> ProcessingReceiptScreen(state)
        ScanPhase.ERROR -> ScannerErrorScreen(
            message = state.errorMessage.orEmpty(),
            retryLabel = if (state.processingFailure) "Retry panorama" else "Return to scanner",
            onRetry = viewModel::retry,
            onClose = { showDiscardDialog = true },
        )
        else -> {
            if (!cameraPermissionGranted) {
                CameraPermissionScreen(
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onClose = requestClose,
                )
            } else {
                LiveScanner(
                    state = state,
                    viewModel = viewModel,
                    onClose = requestClose,
                )
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Discard this recording?") },
            text = {
                Text("The selected receipt keyframes will be removed. Saved receipts and fridge items are not affected.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        viewModel.discardSession()
                        onClose()
                    },
                ) {
                    Text("Discard recording")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Keep recording")
                }
            },
        )
    }
}

@Composable
private fun LiveScanner(
    state: ScanUiState,
    viewModel: ScanViewModel,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        CameraReceiptPreview(
            recording = state.phase == ScanPhase.RECORDING,
            torchEnabled = state.torchEnabled,
            initialKeyframePath = state.initialKeyframePath,
            nextFrameFile = viewModel::nextFrameFile,
            onObservation = viewModel::onObservation,
            onCaptureBusy = viewModel::onCaptureBusy,
            onKeyframeSaved = viewModel::onKeyframeSaved,
            onCameraError = viewModel::onCaptureError,
            modifier = Modifier.fillMaxSize(),
        )
        LiveReceiptBoundary(
            boundary = state.observation.boundary,
            guidance = state.observation.guidance,
            modifier = Modifier.fillMaxSize(),
        )
        ScannerTopBar(
            state = state,
            onClose = onClose,
            onToggleTorch = viewModel::toggleTorch,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        CaptureControlPanel(
            state = state,
            onStart = viewModel::startRecording,
            onFinish = viewModel::finishScan,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ScannerTopBar(
    state: ScanUiState,
    onClose: () -> Unit,
    onToggleTorch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = MaterialTheme.spacing.small, vertical = MaterialTheme.spacing.small),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledIconButton(
            onClick = onClose,
            colors = overlayIconButtonColors(),
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close scanner")
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = CircleShape,
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = MaterialTheme.spacing.medium,
                    vertical = MaterialTheme.spacing.small,
                ),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.phase == ScanPhase.RECORDING) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape),
                    )
                }
                Text(
                    text = if (state.phase == ScanPhase.RECORDING) {
                        "Recording receipt"
                    } else {
                        "Receipt panorama"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        FilledIconButton(
            onClick = onToggleTorch,
            colors = overlayIconButtonColors(),
        ) {
            Icon(
                imageVector = if (state.torchEnabled) {
                    Icons.Default.FlashOn
                } else {
                    Icons.Default.FlashOff
                },
                contentDescription = if (state.torchEnabled) {
                    "Turn torch off"
                } else {
                    "Turn torch on"
                },
            )
        }
    }
}

@Composable
private fun overlayIconButtonColors() =
    IconButtonDefaults.filledIconButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    )

@Composable
private fun LiveReceiptBoundary(
    boundary: ReceiptBoundaryOverlay?,
    guidance: LiveCaptureGuidance,
    modifier: Modifier = Modifier,
) {
    val status = MaterialTheme.scanStatusColors
    val color = when (guidance) {
        LiveCaptureGuidance.READY, LiveCaptureGuidance.RECORDING -> status.good
        LiveCaptureGuidance.MOVE_SLOWER, LiveCaptureGuidance.HOLD_STEADY -> status.caution
        LiveCaptureGuidance.TRACKING_LOST -> status.error
        LiveCaptureGuidance.FIND_RECEIPT -> status.lost
    }
    Canvas(modifier) {
        val points = boundary?.points.orEmpty()
        if (points.size < 4) return@Canvas
        val path = Path().apply {
            val first = points.first()
            moveTo(first.x * size.width, first.y * size.height)
            points.drop(1).forEach { point ->
                lineTo(point.x * size.width, point.y * size.height)
            }
            close()
        }
        drawPath(path, color.copy(alpha = 0.12f))
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 3.dp.toPx()),
        )
        points.forEach { point ->
            drawCircle(
                color = color,
                radius = 5.dp.toPx(),
                center = Offset(point.x * size.width, point.y * size.height),
            )
        }
    }
}

@Composable
private fun CaptureControlPanel(
    state: ScanUiState,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val guidance = guidanceContent(state.observation.guidance, state.phase)
    val status = MaterialTheme.scanStatusColors
    val accent = when (state.observation.guidance) {
        LiveCaptureGuidance.READY, LiveCaptureGuidance.RECORDING -> status.good
        LiveCaptureGuidance.MOVE_SLOWER, LiveCaptureGuidance.HOLD_STEADY -> status.caution
        LiveCaptureGuidance.TRACKING_LOST -> status.error
        LiveCaptureGuidance.FIND_RECEIPT -> status.lost
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(MaterialTheme.spacing.large)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            AnimatedContent(
                targetState = guidance,
                label = "capture guidance",
            ) { content ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = accent.copy(alpha = 0.16f),
                        contentColor = accent,
                        shape = CircleShape,
                    ) {
                        Icon(
                            imageVector = content.icon,
                            contentDescription = null,
                            modifier = Modifier.padding(MaterialTheme.spacing.medium),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall)) {
                        Text(
                            text = content.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = accent,
                        )
                        Text(
                            text = content.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                StatusPill(
                    label = if (state.observation.arTracking) "AR tracking" else "AR paused",
                    good = state.observation.arTracking,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(
                    label = "${state.acceptedSections} keyframes",
                    good = state.acceptedSections > 0,
                    modifier = Modifier.weight(1f),
                )
            }

            when (state.phase) {
                ScanPhase.READY -> Button(
                    onClick = onStart,
                    enabled = state.canRecord,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Icon(Icons.Default.MotionPhotosOn, contentDescription = null)
                    Spacer(Modifier.size(MaterialTheme.spacing.small))
                    Text("Start recording")
                }
                ScanPhase.RECORDING -> Button(
                    onClick = onFinish,
                    enabled = state.canFinish,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Icon(Icons.Default.StopCircle, contentDescription = null)
                    Spacer(Modifier.size(MaterialTheme.spacing.small))
                    Text(
                        when {
                            state.captureBusy -> "Saving keyframe…"
                            state.acceptedSections == 0 -> "Move over the receipt"
                            else -> "Finish receipt"
                        },
                    )
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun StatusPill(
    label: String,
    good: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (good) {
        MaterialTheme.scanStatusColors.good
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = color,
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.medium,
                vertical = MaterialTheme.spacing.small,
            ),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (good) Icons.Default.Check else Icons.Default.CenterFocusStrong,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private data class GuidanceContent(
    val title: String,
    val body: String,
    val icon: ImageVector,
)

private fun guidanceContent(
    guidance: LiveCaptureGuidance,
    phase: ScanPhase,
): GuidanceContent = when (guidance) {
    LiveCaptureGuidance.FIND_RECEIPT -> GuidanceContent(
        title = "Find the receipt",
        body = "Point the camera at any visible section of the paper.",
        icon = Icons.AutoMirrored.Filled.ReceiptLong,
    )
    LiveCaptureGuidance.READY -> GuidanceContent(
        title = "Receipt found",
        body = "Start recording, then move freely along the paper.",
        icon = Icons.Default.Check,
    )
    LiveCaptureGuidance.RECORDING -> GuidanceContent(
        title = if (phase == ScanPhase.RECORDING) "Keep moving" else "Receipt found",
        body = "Sharp overlapping areas are selected automatically.",
        icon = Icons.Default.MotionPhotosOn,
    )
    LiveCaptureGuidance.MOVE_SLOWER -> GuidanceContent(
        title = "Move slower",
        body = "The current movement may blur text or lose overlap.",
        icon = Icons.Default.Speed,
    )
    LiveCaptureGuidance.TRACKING_LOST -> GuidanceContent(
        title = "Tracking lost",
        body = "Return to the last visible receipt area until the outline recovers.",
        icon = Icons.Default.CenterFocusStrong,
    )
    LiveCaptureGuidance.HOLD_STEADY -> GuidanceContent(
        title = "Hold steady",
        body = "Wait for focus and even lighting. Slow movement is fine.",
        icon = Icons.Default.CenterFocusStrong,
    )
}

@Composable
private fun ProcessingReceiptScreen(state: ScanUiState) {
    val stage = state.processingStage ?: ProcessingStage.STITCHING
    val previewPath = state.processingPreviewPath
    val preview by produceState<ImageBitmap?>(null, previewPath) {
        value = previewPath?.let { path ->
            withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            }
        }
    }
    // The determinate bar stalls for the whole OCR stage when a local language model reads the
    // panorama (minutes, no intermediate progress), so switch to an indeterminate bar there.
    // The same applies to the parsing stage when a language model extracts the items.
    val indeterminate = (stage == ProcessingStage.RECOGNIZING_TEXT && state.slowOcrEngine) ||
        (stage == ProcessingStage.PARSING_ITEMS && state.slowExtraction)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(MaterialTheme.spacing.extraLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            preview?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = "Stitched receipt panorama",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
            } ?: CircularProgressIndicator()
            Text(
                text = when (stage) {
                    ProcessingStage.STITCHING -> "Building the receipt panorama"
                    ProcessingStage.RECOGNIZING_TEXT -> "Reading receipt text"
                    ProcessingStage.PARSING_ITEMS -> "Finding grocery items"
                    ProcessingStage.SAVING -> "Saving the digital receipt"
                },
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = when {
                    stage == ProcessingStage.STITCHING ->
                        "The selected frames are aligned on the receipt plane and written as " +
                            "one continuous image."
                    stage == ProcessingStage.RECOGNIZING_TEXT && state.slowOcrEngine ->
                        "The panorama is ready. The on-device GLM-OCR model is reading it now — " +
                            "this can take several minutes. ML Kit in Settings is much faster."
                    stage == ProcessingStage.RECOGNIZING_TEXT ->
                        "The panorama is ready and its text is being recognized."
                    stage == ProcessingStage.PARSING_ITEMS && state.slowExtraction ->
                        "A language model is extracting the items — this can take a while."
                    else -> "Almost done."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (indeterminate) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { state.processingProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ScannerErrorScreen(
    message: String,
    retryLabel: String,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(MaterialTheme.spacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            Text("Scanner stopped", style = MaterialTheme.typography.headlineSmall)
            Text(
                message,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(retryLabel)
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("Discard recording")
            }
        }
    }
}

@Composable
private fun CameraPermissionScreen(
    onRequestPermission: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(MaterialTheme.spacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ReceiptLong,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("Camera access required", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Camera access is used only to build the receipt panorama on this device.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                Text("Allow camera")
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("Close")
            }
        }
    }
}
