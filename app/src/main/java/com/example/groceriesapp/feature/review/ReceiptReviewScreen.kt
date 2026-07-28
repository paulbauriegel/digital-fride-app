package com.example.groceriesapp.feature.review

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.DigitalReceipt
import com.example.groceriesapp.data.ReceiptImageEdit
import com.example.groceriesapp.data.ReceiptItem
import com.example.groceriesapp.data.ReceiptTextRegion
import com.example.groceriesapp.ui.formatCurrency
import com.example.groceriesapp.ui.formatDate
import com.example.groceriesapp.ui.theme.spacing
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptReviewScreen(
    receipt: DigitalReceipt,
    expanded: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRescan: () -> Unit,
    onApplyImageEdit: suspend (ReceiptImageEdit) -> Unit,
    onAddItemsToInventory: (Set<String>) -> Unit,
) {
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showItemsSheet by rememberSaveable { mutableStateOf(false) }
    var selectedItemIds by remember(receipt.id) {
        mutableStateOf(
            receipt.items
                .filterNot(ReceiptItem::needsReview)
                .map(ReceiptItem::id)
                .toSet(),
        )
    }
    var selectedRegionId by rememberSaveable { mutableStateOf<String?>(null) }
    var imported by rememberSaveable { mutableStateOf(false) }
    var showImageEditor by rememberSaveable { mutableStateOf(false) }
    var applyingImageEdit by rememberSaveable { mutableStateOf(false) }
    var imageEditError by rememberSaveable { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val selectedRegion = receipt.regions.firstOrNull { it.id == selectedRegionId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(receipt.merchant ?: "Digital receipt")
                        Text(
                            formatDate(receipt.dateEpochMillis),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showImageEditor = true },
                        enabled = receipt.tilePaths.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.CropRotate, contentDescription = "Rotate and crop receipt")
                    }
                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = "Share receipt image")
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete receipt")
                    }
                },
            )
        },
        bottomBar = {
            if (!expanded) {
                CompactReviewActions(
                    itemCount = receipt.items.size,
                    selectedCount = selectedItemIds.size,
                    imported = imported,
                    onReview = { showItemsSheet = true },
                    onAdd = {
                        onAddItemsToInventory(selectedItemIds)
                        imported = true
                    },
                )
            }
        },
    ) { innerPadding ->
        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                ReceiptViewer(
                    receipt = receipt,
                    selectedRegion = selectedRegion,
                    modifier = Modifier.weight(1.5f).fillMaxHeight(),
                )
                ReviewPanel(
                    receipt = receipt,
                    selectedItemIds = selectedItemIds,
                    onSelectionChanged = { selectedItemIds = it },
                    onSelectRegion = { selectedRegionId = it },
                    imported = imported,
                    onAdd = {
                        onAddItemsToInventory(selectedItemIds)
                        imported = true
                    },
                    onRescan = onRescan,
                    modifier = Modifier.width(390.dp).fillMaxHeight(),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                ReceiptSummary(receipt)
                ReceiptViewer(
                    receipt = receipt,
                    selectedRegion = selectedRegion,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }

    if (showItemsSheet) {
        ModalBottomSheet(onDismissRequest = { showItemsSheet = false }) {
            ReviewPanel(
                receipt = receipt,
                selectedItemIds = selectedItemIds,
                onSelectionChanged = { selectedItemIds = it },
                onSelectRegion = {
                    selectedRegionId = it
                    showItemsSheet = false
                },
                imported = imported,
                onAdd = {
                    onAddItemsToInventory(selectedItemIds)
                    imported = true
                },
                onRescan = onRescan,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.86f),
            )
        }
    }

    if (showImageEditor) {
        ReceiptImageEditorSheet(
            receipt = receipt,
            applying = applyingImageEdit,
            errorMessage = imageEditError,
            onDismiss = {
                if (!applyingImageEdit) {
                    showImageEditor = false
                    imageEditError = null
                }
            },
            onApply = { edit ->
                applyingImageEdit = true
                imageEditError = null
                coroutineScope.launch {
                    runCatching { onApplyImageEdit(edit) }
                        .onSuccess { showImageEditor = false }
                        .onFailure { error ->
                            imageEditError = error.message ?: "Could not edit the receipt image."
                        }
                    applyingImageEdit = false
                }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
            title = { Text("Delete this receipt?") },
            text = {
                Text("The combined receipt, recognized text, and original captured sections will be permanently removed.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ReceiptImageEditorSheet(
    receipt: DigitalReceipt,
    applying: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onApply: (ReceiptImageEdit) -> Unit,
) {
    val initial = receipt.imageEdit
    var rotationDegrees by rememberSaveable(receipt.id) {
        mutableFloatStateOf(initial.rotationDegrees)
    }
    var cropLeft by rememberSaveable(receipt.id) { mutableFloatStateOf(initial.cropLeft) }
    var cropTop by rememberSaveable(receipt.id) { mutableFloatStateOf(initial.cropTop) }
    var cropRight by rememberSaveable(receipt.id) { mutableFloatStateOf(initial.cropRight) }
    var cropBottom by rememberSaveable(receipt.id) { mutableFloatStateOf(initial.cropBottom) }
    val edit = ReceiptImageEdit(
        rotationDegrees = rotationDegrees,
        cropLeft = cropLeft,
        cropTop = cropTop,
        cropRight = cropRight,
        cropBottom = cropBottom,
    )
    val previewPath = receipt.sourcePreviewPath ?: receipt.previewPath
    val preview by produceState<ImageBitmap?>(null, previewPath, rotationDegrees) {
        value = withContext(Dispatchers.Default) {
            previewPath
                ?.let { createRotatedPreviewBitmap(it, rotationDegrees) }
                ?.asImageBitmap()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Rotate and crop", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Edits are applied to the full-resolution receipt. Original frames and the unedited panorama remain available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .background(Color.Black, MaterialTheme.shapes.large),
                contentAlignment = Alignment.Center,
            ) {
                preview?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Receipt crop preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    InteractiveCropOverlay(
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height,
                        crop = CropFractions(
                            left = cropLeft,
                            top = cropTop,
                            right = cropRight,
                            bottom = cropBottom,
                        ),
                        onCropChanged = { updated ->
                            cropLeft = updated.left
                            cropTop = updated.top
                            cropRight = updated.right
                            cropBottom = updated.bottom
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } ?: CircularProgressIndicator()
            }
            Text(
                "Drag a corner to crop. Drag inside the rectangle to reposition it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Rotation", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { rotationDegrees = 0f }) {
                        Text("${"%.1f".format(rotationDegrees)}°")
                    }
                }
                Slider(
                    value = rotationDegrees,
                    onValueChange = { rotationDegrees = it.coerceIn(-90f, 90f) },
                    valueRange = -90f..90f,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("−90°", style = MaterialTheme.typography.labelSmall)
                    Text("0°", style = MaterialTheme.typography.labelSmall)
                    Text("+90°", style = MaterialTheme.typography.labelSmall)
                }
            }

            errorMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                TextButton(
                    onClick = {
                        rotationDegrees = 0f
                        cropLeft = 0f
                        cropTop = 0f
                        cropRight = 1f
                        cropBottom = 1f
                    },
                    enabled = !applying,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Reset")
                }
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !applying,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = { onApply(edit) },
                    enabled = !applying,
                    modifier = Modifier.weight(1f),
                ) {
                    if (applying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Apply")
                    }
                }
            }
            Spacer(Modifier.height(MaterialTheme.spacing.small))
        }
    }
}

@Composable
private fun InteractiveCropOverlay(
    imageWidth: Int,
    imageHeight: Int,
    crop: CropFractions,
    onCropChanged: (CropFractions) -> Unit,
    modifier: Modifier = Modifier,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val handleRadius = with(LocalDensity.current) { 28.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary
    val imageAspect = imageWidth.toFloat() / imageHeight.coerceAtLeast(1)
    val latestCrop by rememberUpdatedState(crop)
    val latestOnCropChanged by rememberUpdatedState(onCropChanged)
    var dragMode by remember { mutableStateOf(CropDragMode.NONE) }
    var workingCrop by remember { mutableStateOf(crop) }

    Canvas(
        modifier = modifier
            .onSizeChanged { containerSize = it }
            .semantics {
                contentDescription =
                    "Crop receipt. Drag corners to resize or drag inside to reposition."
            }
            .pointerInput(imageAspect, containerSize) {
                detectDragGestures(
                    onDragStart = { touch ->
                        workingCrop = latestCrop
                        val imageRect = fittedImageRect(containerSize, imageAspect)
                        val cropRect = workingCrop.toPixelRect(imageRect)
                        dragMode = cropRect.dragModeAt(touch, handleRadius)
                    },
                    onDragEnd = { dragMode = CropDragMode.NONE },
                    onDragCancel = { dragMode = CropDragMode.NONE },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val imageRect = fittedImageRect(containerSize, imageAspect)
                        if (
                            dragMode != CropDragMode.NONE &&
                            imageRect.width > 0f &&
                            imageRect.height > 0f
                        ) {
                            workingCrop = workingCrop.dragged(
                                mode = dragMode,
                                deltaX = dragAmount.x / imageRect.width,
                                deltaY = dragAmount.y / imageRect.height,
                            )
                            latestOnCropChanged(workingCrop)
                        }
                    },
                )
            },
    ) {
        val imageRect = fittedImageRect(
            containerSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            imageAspect = imageAspect,
        )
        val cropRect = crop.toPixelRect(imageRect)
        drawRect(
            color = Color.Black.copy(alpha = 0.58f),
            topLeft = imageRect.topLeft,
            size = androidx.compose.ui.geometry.Size(
                imageRect.width,
                max(0f, cropRect.top - imageRect.top),
            ),
        )
        drawRect(
            color = Color.Black.copy(alpha = 0.58f),
            topLeft = Offset(imageRect.left, cropRect.bottom),
            size = androidx.compose.ui.geometry.Size(
                imageRect.width,
                max(0f, imageRect.bottom - cropRect.bottom),
            ),
        )
        drawRect(
            color = Color.Black.copy(alpha = 0.58f),
            topLeft = Offset(imageRect.left, cropRect.top),
            size = androidx.compose.ui.geometry.Size(
                max(0f, cropRect.left - imageRect.left),
                cropRect.height,
            ),
        )
        drawRect(
            color = Color.Black.copy(alpha = 0.58f),
            topLeft = Offset(cropRect.right, cropRect.top),
            size = androidx.compose.ui.geometry.Size(
                max(0f, imageRect.right - cropRect.right),
                cropRect.height,
            ),
        )
        drawRect(
            color = Color.White,
            topLeft = cropRect.topLeft,
            size = cropRect.size,
            style = Stroke(width = 2.dp.toPx()),
        )
        repeat(2) { index ->
            val fraction = (index + 1) / 3f
            drawLine(
                color = Color.White.copy(alpha = 0.65f),
                start = Offset(
                    cropRect.left + cropRect.width * fraction,
                    cropRect.top,
                ),
                end = Offset(
                    cropRect.left + cropRect.width * fraction,
                    cropRect.bottom,
                ),
                strokeWidth = 1.dp.toPx(),
            )
            drawLine(
                color = Color.White.copy(alpha = 0.65f),
                start = Offset(
                    cropRect.left,
                    cropRect.top + cropRect.height * fraction,
                ),
                end = Offset(
                    cropRect.right,
                    cropRect.top + cropRect.height * fraction,
                ),
                strokeWidth = 1.dp.toPx(),
            )
        }
        cropRect.corners.forEach { point ->
            drawCircle(Color.White, radius = 10.dp.toPx(), center = point)
            drawCircle(accent, radius = 6.dp.toPx(), center = point)
        }
    }
}

private fun createRotatedPreviewBitmap(
    path: String,
    rotationDegrees: Float,
): Bitmap? {
    val source = BitmapFactory.decodeFile(path) ?: return null
    val matrix = Matrix().apply { postRotate(rotationDegrees) }
    return Bitmap.createBitmap(
        source,
        0,
        0,
        source.width,
        source.height,
        matrix,
        true,
    )
}

private data class CropFractions(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun toPixelRect(image: Rect): Rect = Rect(
        left = image.left + left * image.width,
        top = image.top + top * image.height,
        right = image.left + right * image.width,
        bottom = image.top + bottom * image.height,
    )

    fun dragged(
        mode: CropDragMode,
        deltaX: Float,
        deltaY: Float,
    ): CropFractions = when (mode) {
        CropDragMode.TOP_LEFT -> copy(
            left = (left + deltaX).coerceIn(0f, right - MIN_CROP_FRACTION),
            top = (top + deltaY).coerceIn(0f, bottom - MIN_CROP_FRACTION),
        )
        CropDragMode.TOP_RIGHT -> copy(
            right = (right + deltaX).coerceIn(left + MIN_CROP_FRACTION, 1f),
            top = (top + deltaY).coerceIn(0f, bottom - MIN_CROP_FRACTION),
        )
        CropDragMode.BOTTOM_LEFT -> copy(
            left = (left + deltaX).coerceIn(0f, right - MIN_CROP_FRACTION),
            bottom = (bottom + deltaY).coerceIn(top + MIN_CROP_FRACTION, 1f),
        )
        CropDragMode.BOTTOM_RIGHT -> copy(
            right = (right + deltaX).coerceIn(left + MIN_CROP_FRACTION, 1f),
            bottom = (bottom + deltaY).coerceIn(top + MIN_CROP_FRACTION, 1f),
        )
        CropDragMode.MOVE -> {
            val width = right - left
            val height = bottom - top
            val movedLeft = (left + deltaX).coerceIn(0f, 1f - width)
            val movedTop = (top + deltaY).coerceIn(0f, 1f - height)
            copy(
                left = movedLeft,
                top = movedTop,
                right = movedLeft + width,
                bottom = movedTop + height,
            )
        }
        CropDragMode.NONE -> this
    }
}

private enum class CropDragMode {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    MOVE,
    NONE,
}

private fun Rect.dragModeAt(point: Offset, handleRadius: Float): CropDragMode {
    val corners = listOf(
        topLeft to CropDragMode.TOP_LEFT,
        topRight to CropDragMode.TOP_RIGHT,
        bottomLeft to CropDragMode.BOTTOM_LEFT,
        bottomRight to CropDragMode.BOTTOM_RIGHT,
    )
    return corners.minByOrNull { (corner, _) ->
        hypot(point.x - corner.x, point.y - corner.y)
    }?.takeIf { (corner, _) ->
        hypot(point.x - corner.x, point.y - corner.y) <= handleRadius
    }?.second ?: if (contains(point)) CropDragMode.MOVE else CropDragMode.NONE
}

private val Rect.corners: List<Offset>
    get() = listOf(topLeft, topRight, bottomLeft, bottomRight)

private fun fittedImageRect(containerSize: IntSize, imageAspect: Float): Rect {
    if (containerSize.width <= 0 || containerSize.height <= 0) return Rect.Zero
    val containerAspect = containerSize.width.toFloat() / containerSize.height
    return if (containerAspect > imageAspect) {
        val height = containerSize.height.toFloat()
        val width = height * imageAspect
        val left = (containerSize.width - width) / 2f
        Rect(left, 0f, left + width, height)
    } else {
        val width = containerSize.width.toFloat()
        val height = width / imageAspect
        val top = (containerSize.height - height) / 2f
        Rect(0f, top, width, top + height)
    }
}

private const val MIN_CROP_FRACTION = 0.05f

@Composable
private fun ReceiptSummary(receipt: DigitalReceipt) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(MaterialTheme.spacing.medium),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                "${receipt.items.size} recognized ${
                    if (receipt.items.size == 1) "item" else "items"
                }",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            val reviewCount = receipt.items.count(ReceiptItem::needsReview)
            if (reviewCount > 0) {
                Text(
                    "$reviewCount ${if (reviewCount == 1) "item needs" else "items need"} review",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(
                "${receipt.tilePaths.size} seamless image ${
                    if (receipt.tilePaths.size == 1) "tile" else "tiles"
                } • ${receipt.originalFramePaths.size} original ${
                    if (receipt.originalFramePaths.size == 1) "frame" else "frames"
                }",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            formatCurrency(receipt.totalCents, receipt.currency),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CompactReviewActions(
    itemCount: Int,
    selectedCount: Int,
    imported: Boolean,
    onReview: () -> Unit,
    onAdd: () -> Unit,
) {
    Surface(
        modifier = Modifier.navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            OutlinedButton(
                onClick = onReview,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (itemCount == 0) "View OCR text" else "Review items")
            }
            Button(
                onClick = onAdd,
                enabled = selectedCount > 0 && !imported,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    if (imported) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (imported) "Added" else "Add $selectedCount")
            }
        }
    }
}

@Composable
private fun ReviewPanel(
    receipt: DigitalReceipt,
    selectedItemIds: Set<String>,
    onSelectionChanged: (Set<String>) -> Unit,
    onSelectRegion: (String?) -> Unit,
    imported: Boolean,
    onAdd: () -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        Text("Recognized info", style = MaterialTheme.typography.titleLarge)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(MaterialTheme.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                InfoRow("Merchant", receipt.merchant ?: "Needs review")
                InfoRow("Date", formatDate(receipt.dateEpochMillis))
                InfoRow("Total", formatCurrency(receipt.totalCents, receipt.currency))
            }
        }

        Text(
            "${receipt.items.size} grocery ${
                if (receipt.items.size == 1) "item" else "items"
            }",
            style = MaterialTheme.typography.titleMedium,
        )
        if (receipt.items.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(MaterialTheme.spacing.medium),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null)
                    Text(
                        "No grocery rows were confidently parsed. The original receipt and raw OCR are still saved.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                receipt.rawText.ifBlank { "No text recognized." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(receipt.items, key = ReceiptItem::id) { item ->
                    ReviewItemRow(
                        item = item,
                        selected = item.id in selectedItemIds,
                        onSelectedChanged = { selected ->
                            onSelectionChanged(
                                if (selected) selectedItemIds + item.id
                                else selectedItemIds - item.id,
                            )
                        },
                        onLocate = { onSelectRegion(item.textRegionId) },
                    )
                }
            }
        }

        Button(
            onClick = onAdd,
            enabled = selectedItemIds.isNotEmpty() && !imported,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(if (imported) Icons.Default.Check else Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (imported) "Added to fridge" else "Add selected to fridge")
        }
        TextButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
            Text("Rescan receipt")
        }
        Text(
            "Expiry dates remain unknown until you set them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReviewItemRow(
    item: ReceiptItem,
    selected: Boolean,
    onSelectedChanged: (Boolean) -> Unit,
    onLocate: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = onSelectedChanged)
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${formatQuantity(item.quantity)} ${item.unit.orEmpty()} • ${item.category ?: "Uncategorized"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.needsReview) {
                    Text(
                        "Needs review — select to confirm",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Text(
                formatCurrency(item.priceCents),
                style = MaterialTheme.typography.labelLarge,
            )
            IconButton(
                onClick = onLocate,
                enabled = item.textRegionId != null,
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Locate ${item.name} in receipt",
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}

private data class ReceiptTileMetadata(
    val path: String,
    val width: Int,
    val height: Int,
    val globalTop: Int,
)

@Composable
private fun ReceiptViewer(
    receipt: DigitalReceipt,
    selectedRegion: ReceiptTextRegion?,
    modifier: Modifier = Modifier,
) {
    var showOriginals by rememberSaveable(receipt.id) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        if (receipt.originalFramePaths.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(
                            horizontal = MaterialTheme.spacing.medium,
                            vertical = MaterialTheme.spacing.small,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    FilterChip(
                        selected = !showOriginals,
                        onClick = { showOriginals = false },
                        label = { Text("Digital receipt") },
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Filled.ReceiptLong,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    FilterChip(
                        selected = showOriginals,
                        onClick = { showOriginals = true },
                        label = { Text("Original frames (${receipt.originalFramePaths.size})") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
        }
        if (showOriginals && receipt.originalFramePaths.isNotEmpty()) {
            OriginalFramesViewer(
                receiptId = receipt.id,
                framePaths = receipt.originalFramePaths,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        } else {
            StitchedReceiptViewer(
                receipt = receipt,
                selectedRegion = selectedRegion,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StitchedReceiptViewer(
    receipt: DigitalReceipt,
    selectedRegion: ReceiptTextRegion?,
    modifier: Modifier = Modifier,
) {
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    val tilePaths = receipt.tilePaths.ifEmpty {
        listOfNotNull(receipt.previewPath)
    }
    val metadata by produceState<List<ReceiptTileMetadata>>(emptyList(), tilePaths) {
        value = withContext(Dispatchers.IO) {
            var top = 0
            tilePaths.mapNotNull { path ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) return@mapNotNull null
                ReceiptTileMetadata(
                    path = path,
                    width = options.outWidth,
                    height = options.outHeight,
                    globalTop = top,
                ).also { top += options.outHeight }
            }
        }
    }
    val listState = rememberLazyListState()
    val horizontalScroll = rememberScrollState()
    LaunchedEffect(selectedRegion?.id, metadata) {
        val tileIndex = selectedRegion?.sourceTileIndex ?: return@LaunchedEffect
        if (tileIndex in metadata.indices) listState.animateScrollToItem(tileIndex)
    }

    BoxWithConstraints(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        val viewerWidth = maxWidth
        Row(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(horizontalScroll),
            horizontalArrangement = Arrangement.Center,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .requiredWidth(viewerWidth * zoom)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(metadata, key = ReceiptTileMetadata::path) { tile ->
                    ReceiptTileImage(
                        metadata = tile,
                        selectedRegion = selectedRegion?.takeIf {
                            it.sourceTileIndex == metadata.indexOf(tile)
                        },
                    )
                }
            }
        }
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(MaterialTheme.spacing.small),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        ) {
            Column {
                IconButton(
                    onClick = { zoom = (zoom + 0.25f).coerceAtMost(2.5f) },
                    enabled = zoom < 2.5f,
                ) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in")
                }
                HorizontalDivider(modifier = Modifier.width(28.dp).align(Alignment.CenterHorizontally))
                IconButton(
                    onClick = { zoom = (zoom - 0.25f).coerceAtLeast(1f) },
                    enabled = zoom > 1f,
                ) {
                    Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out")
                }
            }
        }
    }
}

private data class OriginalFrameMetadata(
    val path: String,
    val width: Int,
    val height: Int,
)

@Composable
private fun OriginalFramesViewer(
    receiptId: String,
    framePaths: List<String>,
    modifier: Modifier = Modifier,
) {
    var frameIndex by rememberSaveable(receiptId) { mutableIntStateOf(0) }
    var zoom by rememberSaveable(receiptId) { mutableFloatStateOf(1f) }
    val selectedPath = framePaths[frameIndex]
    val metadata by produceState<OriginalFrameMetadata?>(null, selectedPath) {
        value = withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(selectedPath, options)
            if (options.outWidth > 0 && options.outHeight > 0) {
                OriginalFrameMetadata(
                    path = selectedPath,
                    width = options.outWidth,
                    height = options.outHeight,
                )
            } else {
                null
            }
        }
    }
    val bitmap by produceState<ImageBitmap?>(null, selectedPath) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(selectedPath)?.asImageBitmap()
        }
    }
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()

    BoxWithConstraints(
        modifier = modifier
            .background(Color.Black),
    ) {
        val viewerWidth = maxWidth
        val frame = metadata
        val image = bitmap
        if (frame != null && image != null) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScroll),
                horizontalArrangement = Arrangement.Center,
            ) {
                Column(
                    modifier = Modifier
                        .requiredWidth(viewerWidth * zoom)
                        .fillMaxHeight()
                        .verticalScroll(verticalScroll),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Image(
                        bitmap = image,
                        contentDescription = "Original capture frame ${frameIndex + 1}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(frame.width.toFloat() / frame.height),
                    )
                }
            }
        } else {
            Text(
                "This original frame could not be opened.",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(MaterialTheme.spacing.small),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        ) {
            Text(
                "Frame ${frameIndex + 1} of ${framePaths.size}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(MaterialTheme.spacing.small),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        ) {
            Column {
                IconButton(
                    onClick = { zoom = (zoom + 0.25f).coerceAtMost(3f) },
                    enabled = zoom < 3f,
                ) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "Zoom into original frame")
                }
                HorizontalDivider(modifier = Modifier.width(28.dp).align(Alignment.CenterHorizontally))
                IconButton(
                    onClick = { zoom = (zoom - 0.25f).coerceAtLeast(1f) },
                    enabled = zoom > 1f,
                ) {
                    Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out of original frame")
                }
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(MaterialTheme.spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            FilledIconButton(
                onClick = {
                    frameIndex = (frameIndex - 1).coerceAtLeast(0)
                    zoom = 1f
                },
                enabled = frameIndex > 0,
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous original frame")
            }
            FilledIconButton(
                onClick = {
                    frameIndex = (frameIndex + 1).coerceAtMost(framePaths.lastIndex)
                    zoom = 1f
                },
                enabled = frameIndex < framePaths.lastIndex,
            ) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Next original frame")
            }
        }
    }
}

@Composable
private fun ReceiptTileImage(
    metadata: ReceiptTileMetadata,
    selectedRegion: ReceiptTextRegion?,
) {
    val highlightColor = MaterialTheme.colorScheme.primary
    val bitmap by produceState<ImageBitmap?>(null, metadata.path) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(metadata.path)?.asImageBitmap()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(metadata.width.toFloat() / metadata.height)
            .background(Color.White),
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "Continuous receipt image",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
        selectedRegion?.let { region ->
            Canvas(Modifier.fillMaxSize()) {
                val left = region.globalLeft / metadata.width * size.width
                val right = region.globalRight / metadata.width * size.width
                val localTop = region.globalTop - metadata.globalTop
                val localBottom = region.globalBottom - metadata.globalTop
                val top = localTop / metadata.height * size.height
                val bottom = localBottom / metadata.height * size.height
                drawRect(
                    color = highlightColor.copy(alpha = 0.4f),
                    topLeft = androidx.compose.ui.geometry.Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                )
                drawRect(
                    color = highlightColor,
                    topLeft = androidx.compose.ui.geometry.Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}

private fun formatQuantity(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)
