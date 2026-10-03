package com.example.groceriesapp.vision.capture

import java.io.Closeable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

data class PixelPoint(
    val x: Double,
    val y: Double,
)

class DetectedReceiptBoundary(
    val points: List<PixelPoint>,
    val confidence: Float,
    val partial: Boolean,
    val mask: Mat,
) : Closeable {
    override fun close() {
        mask.release()
    }
}

class VisualFrameFeatures(
    val gray: Mat,
    val boundary: DetectedReceiptBoundary?,
    val keypoints: MatOfKeyPoint,
    val descriptors: Mat,
    val sharpness: Float,
    val exposure: Float,
) : Closeable {
    override fun close() {
        gray.release()
        boundary?.close()
        keypoints.release()
        descriptors.release()
    }
}

data class VisualFrameMatch(
    /** Perspective transform from the current frame into the reference frame. */
    val currentToReference: Mat,
    val confidence: Float,
    val inlierCount: Int,
    val motion: Float,
    val overlap: Float,
) : Closeable {
    override fun close() {
        currentToReference.release()
    }
}

/**
 * Fast document geometry model for the live stream.
 *
 * It estimates a receipt foreground mask from local contrast, then fits a
 * convex paper quadrilateral. The output deliberately allows image-border
 * intersections, because a long receipt is normally only partially visible.
 */
class OpenCvReceiptBoundaryModel(
    private val analysisMaximumSide: Int = 640,
) {
    fun detect(gray: Mat): DetectedReceiptBoundary? {
        require(gray.type() == CvType.CV_8UC1)
        if (gray.empty()) return null

        val scale = min(1.0, analysisMaximumSide.toDouble() / max(gray.cols(), gray.rows()))
        val working = Mat()
        if (scale < 1.0) {
            Imgproc.resize(
                gray,
                working,
                Size(gray.cols() * scale, gray.rows() * scale),
                0.0,
                0.0,
                Imgproc.INTER_AREA,
            )
        } else {
            gray.copyTo(working)
        }

        val blurred = Mat()
        val binary = Mat()
        val edges = Mat()
        val paper = Mat()
        val hierarchy = Mat()
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(9.0, 9.0),
        )
        val contours = mutableListOf<MatOfPoint>()
        return try {
            Imgproc.GaussianBlur(working, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.adaptiveThreshold(
                blurred,
                binary,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                41,
                -4.0,
            )
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.Canny(blurred, edges, 38.0, 118.0)
            Imgproc.dilate(edges, edges, kernel)

            Imgproc.findContours(
                binary,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )
            val edgeContours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(
                edges,
                edgeContours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )
            contours += edgeContours

            // Local contrast alone cannot separate paper from a busy, speckled background (carpet,
            // wood grain) or from a hand holding the receipt: the adaptive mask above merges them
            // into one blob whose outline runs through the paper. Paper is nonetheless the
            // brightest large surface in nearly every receipt photo, so a global threshold gives a
            // second, independent paper mask. Opening detaches fingers and stray speckles from it;
            // closing fills the printed text back in so one contour covers the whole sheet.
            Imgproc.threshold(
                blurred,
                paper,
                0.0,
                255.0,
                Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU,
            )
            val kernelUnit = max(working.cols(), working.rows()) / 960.0
            val openKernel = ellipseKernel(15.0 * kernelUnit)
            val closeKernel = ellipseKernel(31.0 * kernelUnit)
            try {
                Imgproc.morphologyEx(paper, paper, Imgproc.MORPH_OPEN, openKernel)
                Imgproc.morphologyEx(paper, paper, Imgproc.MORPH_CLOSE, closeKernel)
            } finally {
                openKernel.release()
                closeKernel.release()
            }
            val paperContours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(
                paper,
                paperContours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )
            contours += paperContours

            val imageArea = working.cols().toDouble() * working.rows()
            val imageMean = Core.mean(working).`val`[0]
            val best = contours
                .asSequence()
                .mapNotNull { contour ->
                    candidateFor(
                        contour = contour,
                        gray = working,
                        imageArea = imageArea,
                        imageMean = imageMean,
                    )
                }
                .maxByOrNull(Candidate::score)
                ?: return null

            if (best.score < MIN_ACCEPTED_SCORE) return null
            val inverseScale = 1.0 / scale
            val points = best.points.map { point ->
                PixelPoint(point.x * inverseScale, point.y * inverseScale)
            }
            val fullMask = Mat.zeros(gray.rows(), gray.cols(), CvType.CV_8UC1)
            val polygon = MatOfPoint(*points.map { Point(it.x, it.y) }.toTypedArray())
            try {
                Imgproc.fillConvexPoly(fullMask, polygon, Scalar(255.0))
            } finally {
                polygon.release()
            }
            DetectedReceiptBoundary(
                points = points,
                confidence = best.score.coerceIn(0.0, 1.0).toFloat(),
                partial = best.partial,
                mask = fullMask,
            )
        } finally {
            contours.forEach(Mat::release)
            working.release()
            blurred.release()
            binary.release()
            edges.release()
            paper.release()
            hierarchy.release()
            kernel.release()
        }
    }

    /** An odd-sized elliptical structuring element of at least 3 pixels. */
    private fun ellipseKernel(size: Double): Mat {
        val side = max(3, size.roundToInt() or 1)
        return Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE,
            Size(side.toDouble(), side.toDouble()),
        )
    }

    private fun candidateFor(
        contour: MatOfPoint,
        gray: Mat,
        imageArea: Double,
        imageMean: Double,
    ): Candidate? {
        val area = Imgproc.contourArea(contour)
        val areaRatio = area / imageArea
        if (areaRatio !in 0.09..0.96 || contour.rows() < 4) return null

        val contour2f = MatOfPoint2f(*contour.toArray())
        return try {
            val perimeter = Imgproc.arcLength(contour2f, true)
            val approximation = MatOfPoint2f()
            try {
                Imgproc.approxPolyDP(contour2f, approximation, perimeter * 0.025, true)
                val approximated = approximation.toArray()
                val approximatedContour = MatOfPoint(*approximated)
                val isConvexQuadrilateral = try {
                    approximated.size == 4 && Imgproc.isContourConvex(approximatedContour)
                } finally {
                    approximatedContour.release()
                }
                val fittedRectangle = Imgproc.minAreaRect(contour2f)
                val points = if (
                    isConvexQuadrilateral
                ) {
                    approximated.toList()
                } else {
                    Array(4) { Point() }.also(fittedRectangle::points).toList()
                }.orderedClockwise()

                val rectangleArea =
                    (fittedRectangle.size.width * fittedRectangle.size.height).coerceAtLeast(1.0)
                val rectangularity = (area / rectangleArea).coerceIn(0.0, 1.0)
                val shortSide = min(fittedRectangle.size.width, fittedRectangle.size.height)
                    .coerceAtLeast(1.0)
                val longSide = max(fittedRectangle.size.width, fittedRectangle.size.height)
                val aspectRatio = longSide / shortSide
                if (aspectRatio < MIN_RECEIPT_ASPECT_RATIO) return null

                val candidateMask = Mat.zeros(gray.rows(), gray.cols(), CvType.CV_8UC1)
                val outsideRing = Mat()
                val polygon = MatOfPoint(*points.toTypedArray())
                val ringSize = (
                    min(gray.cols(), gray.rows()) * OUTSIDE_RING_FRACTION
                    ).toInt()
                    .coerceAtLeast(5)
                    .let { if (it % 2 == 0) it + 1 else it }
                val ringKernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE,
                    Size(ringSize.toDouble(), ringSize.toDouble()),
                )
                val brightness = try {
                    Imgproc.fillConvexPoly(candidateMask, polygon, Scalar(255.0))
                    Imgproc.dilate(candidateMask, outsideRing, ringKernel)
                    Core.subtract(outsideRing, candidateMask, outsideRing)
                    val outsidePixels = Core.countNonZero(outsideRing)
                    if (outsidePixels < imageArea * MIN_OUTSIDE_RING_AREA_RATIO) {
                        return null
                    }
                    PaperBrightness(
                        inside = Core.mean(gray, candidateMask).`val`[0],
                        outside = Core.mean(gray, outsideRing).`val`[0],
                    )
                } finally {
                    polygon.release()
                    candidateMask.release()
                    outsideRing.release()
                    ringKernel.release()
                }
                val brightnessAdvantage =
                    ((brightness.inside - imageMean + 20.0) / 58.0).coerceIn(0.0, 1.0)
                val edgeContrast =
                    ((brightness.inside - brightness.outside + 12.0) / 52.0)
                        .coerceIn(0.0, 1.0)
                val areaScore = (areaRatio / 0.55).coerceIn(0.0, 1.0)
                val aspectScore =
                    ((aspectRatio - MIN_RECEIPT_ASPECT_RATIO) / 1.5).coerceIn(0.0, 1.0)
                if (
                    rectangleArea / imageArea > MAX_LOW_CONTRAST_FRAME_COVERAGE &&
                    edgeContrast < MIN_FRAME_FILL_EDGE_CONTRAST
                ) {
                    return null
                }
                val edgeMargin = min(gray.cols(), gray.rows()) * 0.025
                val partial = points.count { point ->
                    point.x <= edgeMargin ||
                        point.y <= edgeMargin ||
                        point.x >= gray.cols() - edgeMargin ||
                        point.y >= gray.rows() - edgeMargin
                } >= 2
                Candidate(
                    points = points,
                    score =
                        areaScore * 0.20 +
                            rectangularity * 0.20 +
                            brightnessAdvantage * 0.15 +
                            edgeContrast * 0.30 +
                            aspectScore * 0.15,
                    partial = partial,
                )
            } finally {
                approximation.release()
            }
        } finally {
            contour2f.release()
        }
    }

    private data class Candidate(
        val points: List<Point>,
        val score: Double,
        val partial: Boolean,
    )

    private data class PaperBrightness(
        val inside: Double,
        val outside: Double,
    )

    private companion object {
        const val MIN_ACCEPTED_SCORE = 0.40
        const val MIN_RECEIPT_ASPECT_RATIO = 1.28
        const val OUTSIDE_RING_FRACTION = 0.035
        const val MIN_OUTSIDE_RING_AREA_RATIO = 0.004
        const val MAX_LOW_CONTRAST_FRAME_COVERAGE = 0.88
        const val MIN_FRAME_FILL_EDGE_CONTRAST = 0.48
    }
}

class OpenCvPlanarTracker(
    private val maximumFeatures: Int = 1_600,
) {
    private val orb = ORB.create(maximumFeatures)
    private val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)

    fun describe(
        sourceGray: Mat,
        boundaryModel: OpenCvReceiptBoundaryModel,
    ): VisualFrameFeatures {
        val gray = sourceGray.clone()
        val boundary = boundaryModel.detect(gray)
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        val featureMask = boundary?.mask ?: Mat()
        try {
            orb.detectAndCompute(gray, featureMask, keypoints, descriptors)
        } finally {
            if (boundary == null) featureMask.release()
        }
        return VisualFrameFeatures(
            gray = gray,
            boundary = boundary,
            keypoints = keypoints,
            descriptors = descriptors,
            sharpness = sharpnessScore(gray),
            exposure = exposureScore(gray),
        )
    }

    fun match(
        current: VisualFrameFeatures,
        reference: VisualFrameFeatures,
    ): VisualFrameMatch? {
        if (current.descriptors.empty() || reference.descriptors.empty()) return null
        if (current.keypoints.rows() < MIN_MATCHES || reference.keypoints.rows() < MIN_MATCHES) {
            return null
        }

        val matches = mutableListOf<MatOfDMatch>()
        matcher.knnMatch(current.descriptors, reference.descriptors, matches, 2)
        val good = try {
            matches.mapNotNull { pair ->
                val values = pair.toArray()
                values.firstOrNull()?.takeIf {
                    values.size >= 2 && it.distance < LOWE_RATIO * values[1].distance
                }
            }
        } finally {
            matches.forEach(Mat::release)
        }
        if (good.size < MIN_MATCHES) return null

        val currentKeypoints = current.keypoints.toArray()
        val referenceKeypoints = reference.keypoints.toArray()
        val source = MatOfPoint2f(*good.map { currentKeypoints[it.queryIdx].pt }.toTypedArray())
        val destination = MatOfPoint2f(
            *good.map { referenceKeypoints[it.trainIdx].pt }.toTypedArray(),
        )
        val inlierMask = Mat()
        val homography = try {
            Calib3d.findHomography(
                source,
                destination,
                Calib3d.RANSAC,
                3.0,
                inlierMask,
                2_000,
                0.995,
            )
        } finally {
            source.release()
            destination.release()
        }
        if (homography.empty() || homography.rows() != 3 || homography.cols() != 3) {
            homography.release()
            inlierMask.release()
            return null
        }

        val inliers = Core.countNonZero(inlierMask)
        inlierMask.release()
        val inlierRatio = inliers.toFloat() / good.size
        if (inliers < MIN_INLIERS || inlierRatio < MIN_INLIER_RATIO) {
            homography.release()
            return null
        }

        val currentCorners = frameCorners(current.gray)
        val projectedCorners = MatOfPoint2f()
        try {
            Core.perspectiveTransform(currentCorners, projectedCorners, homography)
            val projected = projectedCorners.toArray()
            if (projected.any { !it.x.isFinite() || !it.y.isFinite() }) {
                homography.release()
                return null
            }
            val overlap = boundingOverlap(
                projected = projected,
                referenceWidth = reference.gray.cols().toDouble(),
                referenceHeight = reference.gray.rows().toDouble(),
            )
            val center = Point(current.gray.cols() / 2.0, current.gray.rows() / 2.0)
            val centerInput = MatOfPoint2f(center)
            val centerOutput = MatOfPoint2f()
            val motion = try {
                Core.perspectiveTransform(centerInput, centerOutput, homography)
                val moved = centerOutput.toArray().first()
                (
                    hypot(moved.x - center.x, moved.y - center.y) /
                        hypot(current.gray.cols().toDouble(), current.gray.rows().toDouble())
                    ).toFloat()
            } finally {
                centerInput.release()
                centerOutput.release()
            }
            val confidence = (
                inlierRatio * 0.70f +
                    (inliers / 80f).coerceIn(0f, 1f) * 0.30f
                ).coerceIn(0f, 1f)
            return VisualFrameMatch(
                currentToReference = homography,
                confidence = confidence,
                inlierCount = inliers,
                motion = motion,
                overlap = overlap,
            )
        } finally {
            currentCorners.release()
            projectedCorners.release()
        }
    }

    private fun sharpnessScore(gray: Mat): Float {
        val laplacian = Mat()
        val mean = MatOfDouble()
        val standardDeviation = MatOfDouble()
        return try {
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            Core.meanStdDev(laplacian, mean, standardDeviation)
            val deviation = standardDeviation.toArray().firstOrNull() ?: 0.0
            (ln(1.0 + deviation * deviation) / 9.0).coerceIn(0.0, 1.0).toFloat()
        } finally {
            laplacian.release()
            mean.release()
            standardDeviation.release()
        }
    }

    private fun exposureScore(gray: Mat): Float {
        val mean = Core.mean(gray).`val`[0]
        val centered = 1.0 - abs(mean - 145.0) / 145.0
        return centered.coerceIn(0.0, 1.0).toFloat()
    }

    private fun frameCorners(gray: Mat): MatOfPoint2f = MatOfPoint2f(
        Point(0.0, 0.0),
        Point(gray.cols().toDouble(), 0.0),
        Point(gray.cols().toDouble(), gray.rows().toDouble()),
        Point(0.0, gray.rows().toDouble()),
    )

    private fun boundingOverlap(
        projected: Array<Point>,
        referenceWidth: Double,
        referenceHeight: Double,
    ): Float {
        val left = projected.minOf(Point::x)
        val top = projected.minOf(Point::y)
        val right = projected.maxOf(Point::x)
        val bottom = projected.maxOf(Point::y)
        val area = max(1.0, (right - left) * (bottom - top))
        val intersectionWidth = max(0.0, min(right, referenceWidth) - max(left, 0.0))
        val intersectionHeight = max(0.0, min(bottom, referenceHeight) - max(top, 0.0))
        return ((intersectionWidth * intersectionHeight) / area).coerceIn(0.0, 1.0).toFloat()
    }

    private companion object {
        const val LOWE_RATIO = 0.76f
        const val MIN_MATCHES = 16
        const val MIN_INLIERS = 11
        const val MIN_INLIER_RATIO = 0.30f
    }
}

private fun List<Point>.orderedClockwise(): List<Point> {
    val centerX = sumOf(Point::x) / size
    val centerY = sumOf(Point::y) / size
    return sortedBy { point -> atan2(point.y - centerY, point.x - centerX) }
}

private fun Double.isFinite(): Boolean = !isNaN() && !isInfinite()
