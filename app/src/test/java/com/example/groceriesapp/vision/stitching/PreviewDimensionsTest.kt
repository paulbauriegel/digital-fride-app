package com.example.groceriesapp.vision.stitching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewDimensionsTest {
    @Test
    fun usesPreferredWidthWhenHeightFits() {
        val dimensions = calculatePreviewDimensions(
            sourceWidth = 1_000,
            sourceHeight = 10_000,
            preferredWidth = 640,
            maximumHeight = 16_000,
        )

        assertEquals(640, dimensions.width)
        assertEquals(6_400, dimensions.height)
    }

    @Test
    fun shrinksWidthWithoutViolatingHeightCap() {
        val dimensions = calculatePreviewDimensions(
            sourceWidth = 1_000,
            sourceHeight = 1_000_000,
            preferredWidth = 640,
            maximumHeight = 16_000,
        )

        assertEquals(16, dimensions.width)
        assertEquals(16_000, dimensions.height)
    }

    @Test
    fun extremeAspectRatioStillHasStrictPositiveBounds() {
        val dimensions = calculatePreviewDimensions(
            sourceWidth = 1_000,
            sourceHeight = 100_000_000,
            preferredWidth = 640,
            maximumHeight = 16_000,
        )

        assertTrue(dimensions.width in 1..640)
        assertTrue(dimensions.height in 1..16_000)
        assertEquals(1, dimensions.width)
        assertEquals(16_000, dimensions.height)
    }
}
