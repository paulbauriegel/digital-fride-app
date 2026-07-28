package com.example.groceriesapp.vision.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousKeyframePolicyTest {
    @Test
    fun `ready receipt can begin recording without requiring motion`() {
        val decision = decide(recording = false)

        assertFalse(decision.save)
        assertEquals(LiveCaptureGuidance.READY, decision.guidance)
    }

    @Test
    fun `first sharp frame is retained immediately`() {
        val decision = decide(recording = true, hasKeyframe = false)

        assertTrue(decision.save)
        assertEquals(LiveCaptureGuidance.RECORDING, decision.guidance)
    }

    @Test
    fun `stationary frames are silently ignored`() {
        val decision = decide(
            recording = true,
            hasKeyframe = true,
            motion = 0.02f,
            overlap = 0.98f,
        )

        assertFalse(decision.save)
        assertEquals(LiveCaptureGuidance.RECORDING, decision.guidance)
    }

    @Test
    fun `useful overlapping motion creates a keyframe`() {
        val decision = decide(
            recording = true,
            hasKeyframe = true,
            motion = 0.16f,
            overlap = 0.74f,
        )

        assertTrue(decision.save)
        assertEquals(LiveCaptureGuidance.RECORDING, decision.guidance)
    }

    @Test
    fun `fast movement warns and never saves`() {
        val decision = decide(
            recording = true,
            hasKeyframe = true,
            motion = ContinuousKeyframePolicy.TOO_FAST_MOTION + 0.01f,
            overlap = 0.58f,
        )

        assertFalse(decision.save)
        assertEquals(LiveCaptureGuidance.MOVE_SLOWER, decision.guidance)
    }

    @Test
    fun `insufficient overlap reports lost tracking`() {
        val decision = decide(
            recording = true,
            hasKeyframe = true,
            motion = 0.14f,
            overlap = ContinuousKeyframePolicy.MIN_SAFE_OVERLAP - 0.01f,
        )

        assertFalse(decision.save)
        assertEquals(LiveCaptureGuidance.TRACKING_LOST, decision.guidance)
    }

    @Test
    fun `blur blocks a keyframe even with good motion`() {
        val decision = decide(
            recording = true,
            hasKeyframe = true,
            sharpness = ContinuousKeyframePolicy.MIN_SHARPNESS - 0.01f,
            motion = 0.15f,
            overlap = 0.70f,
        )

        assertFalse(decision.save)
        assertEquals(LiveCaptureGuidance.HOLD_STEADY, decision.guidance)
    }

    @Test
    fun `missing receipt boundary blocks capture`() {
        val decision = decide(
            recording = true,
            boundaryConfidence =
                ContinuousKeyframePolicy.MIN_BOUNDARY_CONFIDENCE - 0.01f,
        )

        assertFalse(decision.save)
        assertEquals(LiveCaptureGuidance.FIND_RECEIPT, decision.guidance)
    }

    private fun decide(
        recording: Boolean,
        hasKeyframe: Boolean = false,
        boundaryConfidence: Float = 0.80f,
        sharpness: Float = 0.80f,
        exposure: Float = 0.80f,
        matchConfidence: Float? = 0.80f,
        motion: Float? = 0.14f,
        overlap: Float? = 0.72f,
    ): KeyframeDecision = ContinuousKeyframePolicy.decide(
        KeyframeDecisionInput(
            recording = recording,
            hasKeyframe = hasKeyframe,
            boundaryConfidence = boundaryConfidence,
            sharpness = sharpness,
            exposure = exposure,
            matchConfidence = matchConfidence,
            motion = motion,
            overlap = overlap,
        ),
    )
}
