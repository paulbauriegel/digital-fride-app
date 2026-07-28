package com.example.groceriesapp.vision.editing

import com.example.groceriesapp.data.DigitalReceipt
import com.example.groceriesapp.data.ReceiptImageEdit
import com.example.groceriesapp.data.ReceiptTextRegion
import com.example.groceriesapp.vision.stitching.calculatePreviewDimensions
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

class ReceiptImageEditor {
    suspend fun apply(
        receipt: DigitalReceipt,
        edit: ReceiptImageEdit,
    ): DigitalReceipt = withContext(Dispatchers.IO) {
        check(OpenCVLoader.initLocal()) { "OpenCV could not initialize." }
        val sourceTilePaths = receipt.sourceTilePaths.ifEmpty { receipt.tilePaths }
        val sourcePreviewPath = receipt.sourcePreviewPath ?: receipt.previewPath
        val sourceRegions = receipt.sourceRegions.ifEmpty { receipt.regions }
        require(sourceTilePaths.isNotEmpty()) { "This receipt has no stitched image to edit." }

        if (edit.isIdentity()) {
            val sourceRegionIds = sourceRegions.map(ReceiptTextRegion::id).toSet()
            return@withContext receipt.copy(
                previewPath = sourcePreviewPath,
                tilePaths = sourceTilePaths,
                sourcePreviewPath = sourcePreviewPath,
                sourceTilePaths = sourceTilePaths,
                sourceRegions = sourceRegions,
                imageEdit = edit,
                regions = sourceRegions,
                items = receipt.items.map { item ->
                    item.copy(
                        textRegionId = item.textRegionId?.takeIf(sourceRegionIds::contains),
                    )
                },
            )
        }

        val sourceTiles = mutableListOf<Mat>()
        try {
            sourceTilePaths.forEachIndexed { index, path ->
                val image = Imgcodecs.imread(path, Imgcodecs.IMREAD_UNCHANGED)
                if (image.empty()) {
                    image.release()
                    error("Could not decode stitched tile ${index + 1}.")
                }
                sourceTiles += image
            }
            val sourceWidth = sourceTiles.first().cols()
            val sourceType = sourceTiles.first().type()
            require(sourceTiles.all { it.cols() == sourceWidth && it.type() == sourceType }) {
                "Stitched receipt tiles have inconsistent image formats."
            }
            val sourceHeight = sourceTiles.sumOf(Mat::rows)
            require(sourceWidth.toLong() * sourceHeight <= MAX_SOURCE_PIXELS) {
                "This receipt is too large for free-angle editing on this device."
            }
            val source = Mat(sourceHeight, sourceWidth, sourceType, whiteScalar(sourceTiles.first()))
            try {
                var top = 0
                sourceTiles.forEach { tile ->
                    val destination = source.rowRange(top, top + tile.rows())
                    try {
                        tile.copyTo(destination)
                    } finally {
                        destination.release()
                    }
                    top += tile.rows()
                }
                renderEdit(
                    receipt = receipt,
                    source = source,
                    sourceTilePaths = sourceTilePaths,
                    sourcePreviewPath = sourcePreviewPath,
                    sourceRegions = sourceRegions,
                    edit = edit,
                )
            } finally {
                source.release()
            }
        } finally {
            sourceTiles.forEach(Mat::release)
        }
    }

    private fun renderEdit(
        receipt: DigitalReceipt,
        source: Mat,
        sourceTilePaths: List<String>,
        sourcePreviewPath: String?,
        sourceRegions: List<ReceiptTextRegion>,
        edit: ReceiptImageEdit,
    ): DigitalReceipt {
        val rotation = expandedRotation(
            width = source.cols(),
            height = source.rows(),
            clockwiseDegrees = edit.rotationDegrees.toDouble(),
        )
        val rotated = Mat(
            rotation.height,
            rotation.width,
            source.type(),
            whiteScalar(source),
        )
        try {
            Imgproc.warpAffine(
                source,
                rotated,
                rotation.matrix,
                Size(rotation.width.toDouble(), rotation.height.toDouble()),
                Imgproc.INTER_CUBIC,
                Core.BORDER_CONSTANT,
                whiteScalar(source),
            )
            val crop = edit.cropRect(rotated.cols(), rotated.rows())
            val croppedView = rotated.submat(crop)
            val cropped = croppedView.clone()
            croppedView.release()
            try {
                val receiptDirectory = sourceTilePaths
                    .map(::File)
                    .first()
                    .parentFile
                    ?.parentFile
                    ?: error("Could not resolve the receipt storage directory.")
                val editDirectory = File(
                    File(receiptDirectory, EDITS_DIRECTORY),
                    "edit_${UUID.randomUUID()}",
                ).apply {
                    check(mkdirs()) { "Could not create the edited receipt directory." }
                }
                val tilePaths = writeTiles(cropped, editDirectory)
                val previewFile = File(editDirectory, PREVIEW_FILE)
                writePreview(cropped, previewFile)
                val regions = transformRegions(
                    regions = sourceRegions,
                    rotation = rotation.matrix,
                    crop = crop,
                    outputWidth = cropped.cols(),
                    outputHeight = cropped.rows(),
                )
                val retainedRegionIds = regions.map(ReceiptTextRegion::id).toSet()
                return receipt.copy(
                    previewPath = previewFile.absolutePath,
                    tilePaths = tilePaths,
                    sourcePreviewPath = sourcePreviewPath,
                    sourceTilePaths = sourceTilePaths,
                    sourceRegions = sourceRegions,
                    imageEdit = edit,
                    regions = regions,
                    items = receipt.items.map { item ->
                        item.copy(
                            textRegionId = item.textRegionId?.takeIf(retainedRegionIds::contains),
                        )
                    },
                )
            } finally {
                cropped.release()
            }
        } finally {
            rotated.release()
            rotation.matrix.release()
        }
    }

    private fun writeTiles(image: Mat, directory: File): List<String> =
        buildList {
            var top = 0
            var index = 0
            while (top < image.rows()) {
                val bottom = min(image.rows(), top + TILE_HEIGHT)
                val tile = image.rowRange(top, bottom)
                val file = File(directory, "tile_${index.toString().padStart(3, '0')}.jpg")
                try {
                    writeJpeg(tile, file)
                } finally {
                    tile.release()
                }
                add(file.absolutePath)
                top = bottom
                index += 1
            }
        }

    private fun writePreview(image: Mat, file: File) {
        val dimensions = calculatePreviewDimensions(
            sourceWidth = image.cols(),
            sourceHeight = image.rows(),
            preferredWidth = PREVIEW_WIDTH,
            maximumHeight = MAX_PREVIEW_HEIGHT,
        )
        val preview = Mat()
        try {
            Imgproc.resize(
                image,
                preview,
                Size(dimensions.width.toDouble(), dimensions.height.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_AREA,
            )
            writeJpeg(preview, file)
        } finally {
            preview.release()
        }
    }

    private fun writeJpeg(image: Mat, file: File) {
        val parameters = MatOfInt(
            Imgcodecs.IMWRITE_JPEG_QUALITY,
            JPEG_QUALITY,
        )
        val saved = try {
            Imgcodecs.imwrite(file.absolutePath, image, parameters)
        } finally {
            parameters.release()
        }
        check(saved) { "Could not write the edited receipt image." }
    }

    private fun transformRegions(
        regions: List<ReceiptTextRegion>,
        rotation: Mat,
        crop: Rect,
        outputWidth: Int,
        outputHeight: Int,
    ): List<ReceiptTextRegion> = regions.mapNotNull { region ->
        val corners = listOf(
            Point(region.globalLeft.toDouble(), region.globalTop.toDouble()),
            Point(region.globalRight.toDouble(), region.globalTop.toDouble()),
            Point(region.globalRight.toDouble(), region.globalBottom.toDouble()),
            Point(region.globalLeft.toDouble(), region.globalBottom.toDouble()),
        ).map { point -> rotation.transform(point) }
        val left = (corners.minOf(Point::x) - crop.x).coerceIn(0.0, outputWidth.toDouble())
        val top = (corners.minOf(Point::y) - crop.y).coerceIn(0.0, outputHeight.toDouble())
        val right = (corners.maxOf(Point::x) - crop.x).coerceIn(0.0, outputWidth.toDouble())
        val bottom = (corners.maxOf(Point::y) - crop.y).coerceIn(0.0, outputHeight.toDouble())
        if (right - left < 1.0 || bottom - top < 1.0) {
            null
        } else {
            region.copy(
                globalLeft = left.toFloat(),
                globalTop = top.toFloat(),
                globalRight = right.toFloat(),
                globalBottom = bottom.toFloat(),
                sourceTileIndex = (top / TILE_HEIGHT).toInt(),
            )
        }
    }

    private fun expandedRotation(
        width: Int,
        height: Int,
        clockwiseDegrees: Double,
    ): ExpandedRotation {
        val center = Point(width / 2.0, height / 2.0)
        val matrix = Imgproc.getRotationMatrix2D(center, -clockwiseDegrees, 1.0)
        val cosine = abs(matrix.get(0, 0)[0])
        val sine = abs(matrix.get(0, 1)[0])
        val outputWidth = ceil(height * sine + width * cosine).toInt().coerceAtLeast(1)
        val outputHeight = ceil(height * cosine + width * sine).toInt().coerceAtLeast(1)
        matrix.put(0, 2, matrix.get(0, 2)[0] + outputWidth / 2.0 - center.x)
        matrix.put(1, 2, matrix.get(1, 2)[0] + outputHeight / 2.0 - center.y)
        return ExpandedRotation(matrix, outputWidth, outputHeight)
    }

    private fun ReceiptImageEdit.cropRect(width: Int, height: Int): Rect {
        val left = floor(cropLeft * width).toInt().coerceIn(0, width - 1)
        val top = floor(cropTop * height).toInt().coerceIn(0, height - 1)
        val right = ceil(cropRight * width).toInt().coerceIn(left + 1, width)
        val bottom = ceil(cropBottom * height).toInt().coerceIn(top + 1, height)
        return Rect(left, top, right - left, bottom - top)
    }

    private fun Mat.transform(point: Point): Point {
        val x = get(0, 0)[0] * point.x + get(0, 1)[0] * point.y + get(0, 2)[0]
        val y = get(1, 0)[0] * point.x + get(1, 1)[0] * point.y + get(1, 2)[0]
        return Point(x, y)
    }

    private fun whiteScalar(image: Mat): Scalar =
        when (image.channels()) {
            1 -> Scalar(255.0)
            3 -> Scalar(255.0, 255.0, 255.0)
            4 -> Scalar(255.0, 255.0, 255.0, 255.0)
            else -> error("Unsupported receipt image format.")
        }

    private fun ReceiptImageEdit.isIdentity(): Boolean =
        abs(rotationDegrees) < 0.0001f &&
            abs(cropLeft) < 0.0001f &&
            abs(cropTop) < 0.0001f &&
            abs(cropRight - 1f) < 0.0001f &&
            abs(cropBottom - 1f) < 0.0001f

    private data class ExpandedRotation(
        val matrix: Mat,
        val width: Int,
        val height: Int,
    )

    private companion object {
        const val TILE_HEIGHT = 2_048
        const val JPEG_QUALITY = 95
        const val PREVIEW_WIDTH = 640
        const val MAX_PREVIEW_HEIGHT = 16_000
        const val MAX_SOURCE_PIXELS = 80_000_000L
        const val EDITS_DIRECTORY = "edits"
        const val PREVIEW_FILE = "receipt_preview.jpg"
    }
}
