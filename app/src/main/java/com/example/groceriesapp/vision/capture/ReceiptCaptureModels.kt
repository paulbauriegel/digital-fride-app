package com.example.groceriesapp.vision.capture

import kotlin.math.abs

data class NormalizedPoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite() && y.isFinite())
    }
}

data class ReceiptBoundaryOverlay(
    val points: List<NormalizedPoint>,
    val confidence: Float,
    val partial: Boolean,
) {
    init {
        require(points.size >= 4)
        require(confidence.isFinite() && confidence in 0f..1f)
    }
}

enum class LiveCaptureGuidance {
    FIND_RECEIPT,
    READY,
    RECORDING,
    MOVE_SLOWER,
    TRACKING_LOST,
    HOLD_STEADY,
}

data class ReceiptCaptureObservation(
    val boundary: ReceiptBoundaryOverlay? = null,
    val guidance: LiveCaptureGuidance = LiveCaptureGuidance.FIND_RECEIPT,
    val arTracking: Boolean = false,
    val sharpness: Float = 0f,
    val exposure: Float = 0f,
    val motion: Float = 0f,
    val overlap: Float = 0f,
    val coverage: Float = 0f,
)

data class KeyframeDecisionInput(
    val recording: Boolean,
    val hasKeyframe: Boolean,
    val boundaryConfidence: Float,
    val sharpness: Float,
    val exposure: Float,
    val matchConfidence: Float?,
    val motion: Float?,
    val overlap: Float?,
)

data class KeyframeDecision(
    val save: Boolean,
    val guidance: LiveCaptureGuidance,
)

/**
 * Pure capture policy shared by the live AR session and unit tests.
 *
 * Slow or stationary movement is intentionally not an error: duplicate frames
 * are ignored. Warnings are reserved for blur-producing speed or lost visual
 * registration.
 */
object ContinuousKeyframePolicy {
    const val MIN_BOUNDARY_CONFIDENCE = 0.42f
    const val MIN_SHARPNESS = 0.34f
    const val MIN_EXPOSURE = 0.32f
    const val MIN_MATCH_CONFIDENCE = 0.42f
    const val MIN_NEW_AREA_MOTION = 0.11f
    const val MAX_USEFUL_OVERLAP = 0.91f
    const val MIN_SAFE_OVERLAP = 0.32f
    const val TOO_FAST_MOTION = 0.28f

    fun decide(input: KeyframeDecisionInput): KeyframeDecision {
        if (input.boundaryConfidence < MIN_BOUNDARY_CONFIDENCE) {
            return KeyframeDecision(false, LiveCaptureGuidance.FIND_RECEIPT)
        }
        if (!input.recording) {
            return KeyframeDecision(false, LiveCaptureGuidance.READY)
        }
        if (input.sharpness < MIN_SHARPNESS || input.exposure < MIN_EXPOSURE) {
            return KeyframeDecision(false, LiveCaptureGuidance.HOLD_STEADY)
        }
        if (!input.hasKeyframe) {
            return KeyframeDecision(true, LiveCaptureGuidance.RECORDING)
        }

        val match = input.matchConfidence
            ?: return KeyframeDecision(false, LiveCaptureGuidance.TRACKING_LOST)
        val motion = input.motion
            ?: return KeyframeDecision(false, LiveCaptureGuidance.TRACKING_LOST)
        val overlap = input.overlap
            ?: return KeyframeDecision(false, LiveCaptureGuidance.TRACKING_LOST)

        if (match < MIN_MATCH_CONFIDENCE || overlap < MIN_SAFE_OVERLAP) {
            return KeyframeDecision(false, LiveCaptureGuidance.TRACKING_LOST)
        }
        if (abs(motion) > TOO_FAST_MOTION) {
            return KeyframeDecision(false, LiveCaptureGuidance.MOVE_SLOWER)
        }

        val usefulNewArea =
            abs(motion) >= MIN_NEW_AREA_MOTION && overlap <= MAX_USEFUL_OVERLAP
        return KeyframeDecision(
            save = usefulNewArea,
            guidance = LiveCaptureGuidance.RECORDING,
        )
    }
}
