package com.example.groceriesapp.vision.stitching

import org.junit.Assert.assertEquals
import org.junit.Test

class StraighteningAngleTest {
    @Test
    fun aSmallTiltIsKeptAsItIs() {
        assertEquals(Math.toRadians(8.0), straighteningAngle(Math.toRadians(8.0)), TOLERANCE)
        assertEquals(Math.toRadians(-8.0), straighteningAngle(Math.toRadians(-8.0)), TOLERANCE)
    }

    @Test
    fun aHalfTurnIsNeverApplied() {
        // The detected edge pointed the other way along the same axis: upright either way, so the
        // rotation that leaves the image as it came in wins.
        assertEquals(0.0, straighteningAngle(Math.PI), TOLERANCE)
        assertEquals(0.0, straighteningAngle(-Math.PI), TOLERANCE)
    }

    @Test
    fun anObtuseAngleFoldsBackUnderAQuarterTurn() {
        assertEquals(
            Math.toRadians(-10.0),
            straighteningAngle(Math.toRadians(170.0)),
            TOLERANCE,
        )
        assertEquals(
            Math.toRadians(10.0),
            straighteningAngle(Math.toRadians(-170.0)),
            TOLERANCE,
        )
    }

    @Test
    fun aQuarterTurnItselfIsStillAllowed() {
        // A receipt photographed sideways does need standing up; only the flip is unwanted.
        assertEquals(Math.PI / 2.0, straighteningAngle(Math.PI / 2.0), TOLERANCE)
    }

    @Test
    fun anUnusableAngleBecomesNoRotation() {
        assertEquals(0.0, straighteningAngle(Double.NaN), TOLERANCE)
        assertEquals(0.0, straighteningAngle(Double.POSITIVE_INFINITY), TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
