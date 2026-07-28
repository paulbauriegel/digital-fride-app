package com.example.groceriesapp.vision.capture

import android.media.Image
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs

data class FrameVisionResult(
    val imageWidth: Int,
    val imageHeight: Int,
    val boundaryPoints: List<PixelPoint>?,
    val boundaryConfidence: Float,
    val boundaryPartial: Boolean,
    val guidance: LiveCaptureGuidance,
    val arTracking: Boolean,
    val sharpness: Float,
    val exposure: Float,
    val motion: Float,
    val overlap: Float,
    val coverage: Float,
)

/**
 * Owns live CV state for one scan. Calls are serialized by the AR view's
 * analysis executor.
 */
class ContinuousReceiptFrameProcessor(
    private val nextFrameFile: () -> File,
    private val onCaptureBusy: (Boolean) -> Unit,
    private val onKeyframeSaved: (String) -> Unit,
    private val onCaptureError: (Throwable) -> Unit,
    initialKeyframePath: String? = null,
) : Closeable {
    private val boundaryModel = OpenCvReceiptBoundaryModel()
    private val tracker = OpenCvPlanarTracker()
    private val closed = AtomicBoolean(false)
    private var keyframe: VisualFrameFeatures? = null
    private var coverage = 0f

    init {
        initialKeyframePath
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?.let { file ->
                val restored = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
                if (!restored.empty()) {
                    keyframe = tracker.describe(restored, boundaryModel)
                    coverage = 0.18f
                }
                restored.release()
            }
    }

    @Synchronized
    fun process(
        image: Image,
        recording: Boolean,
        arTracking: Boolean,
    ): FrameVisionResult? {
        if (closed.get()) {
            image.close()
            return null
        }
        val source = try {
            image.toGrayMat()
        } finally {
            image.close()
        }

        val current = try {
            tracker.describe(source, boundaryModel)
        } finally {
            source.release()
        }
        val reference = keyframe
        val match = if (reference == null) null else tracker.match(current, reference)
        try {
            val boundaryConfidence = current.boundary?.confidence ?: 0f
            val decision = if (!arTracking) {
                KeyframeDecision(false, LiveCaptureGuidance.TRACKING_LOST)
            } else {
                ContinuousKeyframePolicy.decide(
                    KeyframeDecisionInput(
                        recording = recording,
                        hasKeyframe = reference != null,
                        boundaryConfidence = boundaryConfidence,
                        sharpness = current.sharpness,
                        exposure = current.exposure,
                        matchConfidence = match?.confidence,
                        motion = match?.motion,
                        overlap = match?.overlap,
                    ),
                )
            }

            if (decision.save && recording && !closed.get()) {
                onCaptureBusy(true)
                try {
                    val target = nextFrameFile()
                    target.parentFile?.mkdirs()
                    val parameters = org.opencv.core.MatOfInt(
                        Imgcodecs.IMWRITE_JPEG_QUALITY,
                        JPEG_QUALITY,
                    )
                    val saved = try {
                        Imgcodecs.imwrite(
                            target.absolutePath,
                            current.gray,
                            parameters,
                        )
                    } finally {
                        parameters.release()
                    }
                    check(saved) {
                        "OpenCV could not save the selected receipt frame."
                    }
                    keyframe?.close()
                    keyframe = current
                    val newlyVisible = 1f - (match?.overlap ?: 0.78f)
                    coverage = (coverage + newlyVisible * 0.34f).coerceIn(0f, 0.96f)
                    onKeyframeSaved(target.absolutePath)
                } catch (error: Throwable) {
                    onCaptureError(error)
                } finally {
                    onCaptureBusy(false)
                }
            }

            return FrameVisionResult(
                imageWidth = current.gray.cols(),
                imageHeight = current.gray.rows(),
                boundaryPoints = current.boundary?.points?.toList(),
                boundaryConfidence = boundaryConfidence,
                boundaryPartial = current.boundary?.partial ?: false,
                guidance = decision.guidance,
                arTracking = arTracking,
                sharpness = current.sharpness,
                exposure = current.exposure,
                motion = match?.motion ?: 0f,
                overlap = match?.overlap ?: if (reference == null) 1f else 0f,
                coverage = coverage,
            )
        } finally {
            match?.close()
            if (keyframe !== current) current.close()
        }
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        keyframe?.close()
        keyframe = null
    }

    private fun Image.toGrayMat(): Mat {
        require(format == android.graphics.ImageFormat.YUV_420_888)
        val plane = planes[0]
        val result = Mat(height, width, CvType.CV_8UC1)
        val buffer = plane.buffer.duplicate()
        if (plane.pixelStride == 1 && plane.rowStride == width) {
            val bytes = ByteArray(width * height)
            buffer.get(bytes)
            result.put(0, 0, bytes)
            return result
        }

        val row = ByteArray(plane.rowStride)
        val packed = ByteArray(width * height)
        var outputIndex = 0
        repeat(height) { y ->
            buffer.position(y * plane.rowStride)
            val available = minOf(plane.rowStride, buffer.remaining())
            buffer.get(row, 0, available)
            repeat(width) { x ->
                packed[outputIndex++] = row[x * plane.pixelStride]
            }
        }
        result.put(0, 0, packed)
        return result
    }

    private companion object {
        const val JPEG_QUALITY = 94
    }
}
