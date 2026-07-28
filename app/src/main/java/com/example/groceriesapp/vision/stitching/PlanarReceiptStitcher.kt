package com.example.groceriesapp.vision.stitching

import java.io.File
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import com.example.groceriesapp.vision.capture.OpenCvPlanarTracker
import com.example.groceriesapp.vision.capture.OpenCvReceiptBoundaryModel
import com.example.groceriesapp.vision.capture.PixelPoint
import com.example.groceriesapp.vision.capture.VisualFrameFeatures
import com.example.groceriesapp.vision.capture.VisualFrameMatch

data class StitchedReceiptArtifact(
    val previewPath: String,
    val tilePaths: List<String>,
    val originalFramePaths: List<String>,
    val normalizedWidth: Int,
    val totalHeight: Int,
    val minimumAlignmentConfidence: Float,
)

class StitchingException(message: String) : IllegalStateException(message)

/**
 * Builds a planar document mosaic from freely captured keyframes.
 *
 * Adjacent and recent keyframes are registered with ORB features and a RANSAC
 * homography. Receipt masks—not the surrounding table—define the final canvas.
 * Output remains disk-backed in vertical tiles so a long receipt does not need
 * one enormous Android Bitmap.
 */
class PlanarReceiptStitcher(
    private val normalizedWidth: Int = 1_000,
    private val tileHeight: Int = 2_048,
) {
    suspend fun stitch(
        frameFiles: List<File>,
        outputDirectory: File,
        onProgress: (Float) -> Unit = {},
    ): StitchedReceiptArtifact = withContext(Dispatchers.Default) {
        require(frameFiles.isNotEmpty()) { "At least one captured frame is required" }
        check(OpenCVLoader.initLocal()) { "OpenCV could not initialize." }

        val tracker = OpenCvPlanarTracker(maximumFeatures = 2_200)
        val boundaryModel = OpenCvReceiptBoundaryModel(analysisMaximumSide = 960)
        val registered = registerFrames(
            frameFiles = frameFiles,
            tracker = tracker,
            boundaryModel = boundaryModel,
            onProgress = { onProgress(it * 0.34f) },
        )
        try {
            val normalized = normalizeTransforms(registered)
            val outputHeight = normalized.outputHeight
            if (outputHeight <= 0 || outputHeight > MAX_OUTPUT_HEIGHT) {
                throw StitchingException("The receipt panorama dimensions are unsafe.")
            }

            val tilesDirectory = File(outputDirectory, "tiles").apply { mkdirs() }
            tilesDirectory.listFiles()
                .orEmpty()
                .filter(File::isFile)
                .forEach(File::delete)
            val tilePaths = renderTiles(
                frames = registered,
                finalTransforms = normalized.transforms,
                outputWidth = normalizedWidth,
                outputHeight = outputHeight,
                tilesDirectory = tilesDirectory,
                onProgress = { progress -> onProgress(0.34f + progress * 0.52f) },
            )
            val previewFile = File(outputDirectory, "receipt_preview.jpg")
            createPreview(
                tilePaths = tilePaths,
                sourceWidth = normalizedWidth,
                sourceHeight = outputHeight,
                outputFile = previewFile,
            )
            onProgress(1f)

            StitchedReceiptArtifact(
                previewPath = previewFile.absolutePath,
                tilePaths = tilePaths,
                originalFramePaths = frameFiles.map(File::getAbsolutePath),
                normalizedWidth = normalizedWidth,
                totalHeight = outputHeight,
                minimumAlignmentConfidence =
                    registered.drop(1).minOfOrNull(RegisteredFrame::alignmentConfidence) ?: 1f,
            )
        } finally {
            registered.forEach(RegisteredFrame::close)
        }
    }

    private suspend fun registerFrames(
        frameFiles: List<File>,
        tracker: OpenCvPlanarTracker,
        boundaryModel: OpenCvReceiptBoundaryModel,
        onProgress: (Float) -> Unit,
    ): List<RegisteredFrame> {
        val registered = mutableListOf<RegisteredFrame>()
        val references = ArrayDeque<ReferenceFrame>()
        return try {
            frameFiles.forEachIndexed { index, file ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val gray = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
                if (gray.empty()) {
                    gray.release()
                    throw StitchingException("Could not decode keyframe ${index + 1}.")
                }
                val features = try {
                    tracker.describe(gray, boundaryModel)
                } finally {
                    gray.release()
                }
                val boundary = features.boundary
                    ?: run {
                        features.close()
                        throw StitchingException(
                            "Receipt edges were not recoverable in keyframe ${index + 1}.",
                        )
                    }
                if (boundary.confidence < MIN_OFFLINE_BOUNDARY_CONFIDENCE) {
                    features.close()
                    throw StitchingException(
                        "Receipt edge confidence is too low in keyframe ${index + 1}.",
                    )
                }

                val globalTransform: Mat
                val alignmentConfidence: Float
                if (registered.isEmpty()) {
                    globalTransform = identityMatrix()
                    alignmentConfidence = 1f
                } else {
                    val candidates = references.mapNotNull { reference ->
                        tracker.match(features, reference.features)?.let { match ->
                            MatchCandidate(reference, match)
                        }
                    }
                    val best = candidates.maxByOrNull { candidate ->
                        candidate.match.confidence + candidate.match.inlierCount / 500f
                    }
                    candidates.filterNot { it === best }.forEach { it.match.close() }
                    if (best == null ||
                        best.match.confidence < MIN_OFFLINE_MATCH_CONFIDENCE ||
                        best.match.overlap < MIN_OFFLINE_OVERLAP
                    ) {
                        features.close()
                        best?.match?.close()
                        throw StitchingException(
                            "Keyframe ${index + 1} does not safely overlap the receipt panorama.",
                        )
                    }
                    globalTransform = multiply(
                        registered[best.reference.registeredIndex].globalTransform,
                        best.match.currentToReference,
                    )
                    alignmentConfidence = best.match.confidence
                    best.match.close()
                }

                registered += RegisteredFrame(
                    file = file,
                    width = features.gray.cols(),
                    height = features.gray.rows(),
                    boundaryPoints = boundary.points,
                    sharpness = features.sharpness,
                    globalTransform = globalTransform,
                    alignmentConfidence = alignmentConfidence,
                )
                references.addLast(
                    ReferenceFrame(
                        registeredIndex = registered.lastIndex,
                        features = features,
                    ),
                )
                while (references.size > REFERENCE_WINDOW) {
                    references.removeFirst().features.close()
                }
                onProgress((index + 1f) / frameFiles.size)
            }
            registered
        } catch (error: Throwable) {
            registered.forEach(RegisteredFrame::close)
            throw error
        } finally {
            references.forEach { it.features.close() }
        }
    }

    private fun normalizeTransforms(frames: List<RegisteredFrame>): NormalizedTransforms {
        val globalBoundaryPoints = frames.flatMap { frame ->
            transformPoints(frame.boundaryPoints, frame.globalTransform)
        }
        if (globalBoundaryPoints.size < 4) {
            throw StitchingException("The receipt panorama has no stable paper boundary.")
        }

        val longAxisAngle = longestAxisAngle(globalBoundaryPoints)
        val rotation = rotationMatrix(Math.PI / 2.0 - longAxisAngle)
        val rotatedTransforms = frames.map { frame ->
            multiply(rotation, frame.globalTransform)
        }
        rotation.release()
        val rotatedPoints = frames.flatMapIndexed { index, frame ->
            transformPoints(frame.boundaryPoints, rotatedTransforms[index])
        }
        val minX = rotatedPoints.minOf(PixelPoint::x)
        val maxX = rotatedPoints.maxOf(PixelPoint::x)
        val minY = rotatedPoints.minOf(PixelPoint::y)
        val maxY = rotatedPoints.maxOf(PixelPoint::y)
        val receiptWidth = maxX - minX
        val receiptHeight = maxY - minY
        if (!receiptWidth.isFinite() || !receiptHeight.isFinite() ||
            receiptWidth < 8.0 || receiptHeight < 8.0
        ) {
            rotatedTransforms.forEach(Mat::release)
            throw StitchingException("The receipt panorama bounds are invalid.")
        }

        val scale = normalizedWidth / receiptWidth
        val outputHeight = ceil(receiptHeight * scale).toInt()
        val normalization = matrixOf(
            scale, 0.0, -minX * scale,
            0.0, scale, -minY * scale,
            0.0, 0.0, 1.0,
        )
        val finalTransforms = rotatedTransforms.map { transform ->
            multiply(normalization, transform).also { transform.release() }
        }
        normalization.release()
        return NormalizedTransforms(finalTransforms, outputHeight)
    }

    private suspend fun renderTiles(
        frames: List<RegisteredFrame>,
        finalTransforms: List<Mat>,
        outputWidth: Int,
        outputHeight: Int,
        tilesDirectory: File,
        onProgress: (Float) -> Unit,
    ): List<String> {
        val tileCount = (outputHeight + tileHeight - 1) / tileHeight
        val paths = mutableListOf<String>()
        try {
            repeat(tileCount) { tileIndex ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val tileTop = tileIndex * tileHeight
                val currentHeight = min(tileHeight, outputHeight - tileTop)
                val canvas = Mat(currentHeight, outputWidth, CvType.CV_8UC1, Scalar(255.0))
                val bestWeight = Mat.zeros(currentHeight, outputWidth, CvType.CV_32FC1)
                try {
                    frames.forEachIndexed { frameIndex, frame ->
                        val finalTransform = finalTransforms[frameIndex]
                        val transformedBoundary =
                            transformPoints(frame.boundaryPoints, finalTransform)
                        if (!intersectsTile(transformedBoundary, tileTop, currentHeight)) {
                            return@forEachIndexed
                        }
                        val gray = Imgcodecs.imread(
                            frame.file.absolutePath,
                            Imgcodecs.IMREAD_GRAYSCALE,
                        )
                        if (gray.empty()) {
                            gray.release()
                            throw StitchingException(
                                "Could not reopen keyframe ${frameIndex + 1}.",
                            )
                        }
                        try {
                            compositeFrameIntoTile(
                                gray = gray,
                                sourceBoundary = frame.boundaryPoints,
                                transform = finalTransform,
                                tileTop = tileTop,
                                destination = canvas,
                                bestWeight = bestWeight,
                                quality = 0.65f + frame.sharpness * 0.70f,
                            )
                        } finally {
                            gray.release()
                        }
                    }
                    val tileFile = File(
                        tilesDirectory,
                        "tile_${tileIndex.toString().padStart(3, '0')}.jpg",
                    )
                    val parameters = org.opencv.core.MatOfInt(
                        Imgcodecs.IMWRITE_JPEG_QUALITY,
                        JPEG_QUALITY,
                    )
                    val saved = try {
                        Imgcodecs.imwrite(tileFile.absolutePath, canvas, parameters)
                    } finally {
                        parameters.release()
                    }
                    if (!saved) throw StitchingException("Could not write receipt tile.")
                    paths += tileFile.absolutePath
                } finally {
                    canvas.release()
                    bestWeight.release()
                }
                onProgress((tileIndex + 1f) / tileCount)
            }
        } finally {
            finalTransforms.forEach(Mat::release)
        }
        return paths
    }

    private fun compositeFrameIntoTile(
        gray: Mat,
        sourceBoundary: List<PixelPoint>,
        transform: Mat,
        tileTop: Int,
        destination: Mat,
        bestWeight: Mat,
        quality: Float,
    ) {
        val sourceMask = Mat.zeros(gray.rows(), gray.cols(), CvType.CV_8UC1)
        val polygon = MatOfPoint(
            *sourceBoundary.map { Point(it.x, it.y) }.toTypedArray(),
        )
        val tileTranslation = matrixOf(
            1.0, 0.0, 0.0,
            0.0, 1.0, -tileTop.toDouble(),
            0.0, 0.0, 1.0,
        )
        val tileTransform = multiply(tileTranslation, transform)
        val warped = Mat()
        val warpedMask = Mat()
        val incomingWeight = Mat()
        val updateMask = Mat()
        try {
            Imgproc.fillConvexPoly(sourceMask, polygon, Scalar(255.0))
            Imgproc.warpPerspective(
                gray,
                warped,
                tileTransform,
                Size(destination.cols().toDouble(), destination.rows().toDouble()),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                Scalar(255.0),
            )
            Imgproc.warpPerspective(
                sourceMask,
                warpedMask,
                tileTransform,
                Size(destination.cols().toDouble(), destination.rows().toDouble()),
                Imgproc.INTER_NEAREST,
                Core.BORDER_CONSTANT,
                Scalar(0.0),
            )
            Imgproc.distanceTransform(warpedMask, incomingWeight, Imgproc.DIST_L2, 3)
            Core.multiply(incomingWeight, Scalar(quality.toDouble()), incomingWeight)
            Core.compare(incomingWeight, bestWeight, updateMask, Core.CMP_GT)
            warped.copyTo(destination, updateMask)
            incomingWeight.copyTo(bestWeight, updateMask)
        } finally {
            sourceMask.release()
            polygon.release()
            tileTranslation.release()
            tileTransform.release()
            warped.release()
            warpedMask.release()
            incomingWeight.release()
            updateMask.release()
        }
    }

    private fun createPreview(
        tilePaths: List<String>,
        sourceWidth: Int,
        sourceHeight: Int,
        outputFile: File,
    ) {
        val dimensions = calculatePreviewDimensions(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            preferredWidth = PREFERRED_PREVIEW_WIDTH,
            maximumHeight = MAX_PREVIEW_HEIGHT,
        )
        val preview = Mat(
            dimensions.height,
            dimensions.width,
            CvType.CV_8UC1,
            Scalar(255.0),
        )
        try {
            var sourceTop = 0
            tilePaths.forEachIndexed { index, path ->
                val tile = Imgcodecs.imread(path, Imgcodecs.IMREAD_GRAYSCALE)
                if (tile.empty()) {
                    tile.release()
                    throw StitchingException("Could not decode receipt tile ${index + 1}.")
                }
                try {
                    val destinationTop =
                        sourceTop.toLong() * dimensions.height / sourceHeight
                    sourceTop += tile.rows()
                    val destinationBottom =
                        sourceTop.toLong() * dimensions.height / sourceHeight
                    val destinationHeight =
                        (destinationBottom - destinationTop).toInt().coerceAtLeast(1)
                    val destinationTopInt = destinationTop.toInt()
                    val resized = Mat()
                    try {
                        Imgproc.resize(
                            tile,
                            resized,
                            Size(dimensions.width.toDouble(), destinationHeight.toDouble()),
                            0.0,
                            0.0,
                            Imgproc.INTER_AREA,
                        )
                        val safeBottom = min(
                            dimensions.height,
                            destinationTopInt + resized.rows(),
                        )
                        if (safeBottom > destinationTopInt) {
                            val sourceRows = resized.rowRange(0, safeBottom - destinationTopInt)
                            val destinationRows = preview.rowRange(
                                destinationTopInt,
                                safeBottom,
                            )
                            try {
                                sourceRows.copyTo(destinationRows)
                            } finally {
                                sourceRows.release()
                                destinationRows.release()
                            }
                        }
                    } finally {
                        resized.release()
                    }
                } finally {
                    tile.release()
                }
            }
            val parameters = org.opencv.core.MatOfInt(
                Imgcodecs.IMWRITE_JPEG_QUALITY,
                JPEG_QUALITY,
            )
            val saved = try {
                Imgcodecs.imwrite(outputFile.absolutePath, preview, parameters)
            } finally {
                parameters.release()
            }
            if (!saved) throw StitchingException("Could not write receipt preview.")
        } finally {
            preview.release()
        }
    }

    private data class ReferenceFrame(
        val registeredIndex: Int,
        val features: VisualFrameFeatures,
    )

    private data class MatchCandidate(
        val reference: ReferenceFrame,
        val match: VisualFrameMatch,
    )

    private data class NormalizedTransforms(
        val transforms: List<Mat>,
        val outputHeight: Int,
    )

    private class RegisteredFrame(
        val file: File,
        val width: Int,
        val height: Int,
        val boundaryPoints: List<PixelPoint>,
        val sharpness: Float,
        val globalTransform: Mat,
        val alignmentConfidence: Float,
    ) : AutoCloseable {
        override fun close() {
            globalTransform.release()
        }
    }

    private companion object {
        const val REFERENCE_WINDOW = 3
        const val MIN_OFFLINE_BOUNDARY_CONFIDENCE = 0.36f
        const val MIN_OFFLINE_MATCH_CONFIDENCE = 0.38f
        const val MIN_OFFLINE_OVERLAP = 0.24f
        const val MAX_OUTPUT_HEIGHT = 120_000
        const val JPEG_QUALITY = 95
        const val PREFERRED_PREVIEW_WIDTH = 640
        const val MAX_PREVIEW_HEIGHT = 16_000
    }
}

data class PreviewDimensions(
    val width: Int,
    val height: Int,
)

internal fun calculatePreviewDimensions(
    sourceWidth: Int,
    sourceHeight: Int,
    preferredWidth: Int,
    maximumHeight: Int,
): PreviewDimensions {
    require(sourceWidth > 0 && sourceHeight > 0)
    require(preferredWidth > 0 && maximumHeight > 0)
    val width = min(preferredWidth, sourceWidth)
    val proportionalHeight =
        max(1L, sourceHeight.toLong() * width / sourceWidth.toLong())
    if (proportionalHeight <= maximumHeight) {
        return PreviewDimensions(width, proportionalHeight.toInt())
    }
    val constrainedWidth = max(
        1L,
        sourceWidth.toLong() * maximumHeight / sourceHeight.toLong(),
    ).toInt()
    return PreviewDimensions(constrainedWidth, maximumHeight)
}

private fun identityMatrix(): Mat = Mat.eye(3, 3, CvType.CV_64F)

private fun matrixOf(vararg values: Double): Mat =
    Mat(3, 3, CvType.CV_64F).apply {
        require(values.size == 9)
        put(0, 0, *values)
    }

private fun multiply(left: Mat, right: Mat): Mat {
    val output = Mat()
    val empty = Mat()
    try {
        Core.gemm(left, right, 1.0, empty, 0.0, output)
    } finally {
        empty.release()
    }
    return output
}

private fun rotationMatrix(radians: Double): Mat {
    val cosine = cos(radians)
    val sine = sin(radians)
    return matrixOf(
        cosine, -sine, 0.0,
        sine, cosine, 0.0,
        0.0, 0.0, 1.0,
    )
}

private fun transformPoints(points: List<PixelPoint>, transform: Mat): List<PixelPoint> {
    val source = MatOfPoint2f(*points.map { Point(it.x, it.y) }.toTypedArray())
    val destination = MatOfPoint2f()
    return try {
        Core.perspectiveTransform(source, destination, transform)
        destination.toArray().map { PixelPoint(it.x, it.y) }
    } finally {
        source.release()
        destination.release()
    }
}

private fun longestAxisAngle(points: List<PixelPoint>): Double {
    val pointMatrix = MatOfPoint2f(*points.map { Point(it.x, it.y) }.toTypedArray())
    return try {
        val rectangle = Imgproc.minAreaRect(pointMatrix)
        val corners = Array(4) { Point() }.also(rectangle::points)
        val edges = corners.indices.map { index ->
            val start = corners[index]
            val end = corners[(index + 1) % corners.size]
            Triple(
                end.x - start.x,
                end.y - start.y,
                kotlin.math.hypot(end.x - start.x, end.y - start.y),
            )
        }
        val longest = edges.maxByOrNull { it.third } ?: return 0.0
        atan2(longest.second, longest.first)
    } finally {
        pointMatrix.release()
    }
}

private fun intersectsTile(
    points: List<PixelPoint>,
    tileTop: Int,
    tileHeight: Int,
): Boolean {
    val top = points.minOf(PixelPoint::y)
    val bottom = points.maxOf(PixelPoint::y)
    return bottom >= tileTop && top <= tileTop + tileHeight
}

private fun Double.isFinite(): Boolean = !isNaN() && !isInfinite()
