package com.example.groceriesapp.vision.stitching

import com.example.groceriesapp.vision.capture.PixelPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class FullFrameBoundaryTest {
    @Test
    fun coversTheWholeImageClockwiseFromTheTopLeft() {
        val points = fullFrameBoundaryPoints(width = 1_200, height = 800)

        assertEquals(
            listOf(
                PixelPoint(0.0, 0.0),
                PixelPoint(1_199.0, 0.0),
                PixelPoint(1_199.0, 799.0),
                PixelPoint(0.0, 799.0),
            ),
            points,
        )
    }

    @Test
    fun aSinglePixelFrameStillHasFourCorners() {
        val points = fullFrameBoundaryPoints(width = 1, height = 1)

        assertEquals(4, points.size)
        assertEquals(PixelPoint(0.0, 0.0), points.first())
    }

    @Test(expected = IllegalArgumentException::class)
    fun anEmptyFrameIsRejected() {
        fullFrameBoundaryPoints(width = 0, height = 100)
    }
}
