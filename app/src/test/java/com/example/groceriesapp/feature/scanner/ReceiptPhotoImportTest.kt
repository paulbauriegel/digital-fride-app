package com.example.groceriesapp.feature.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptPhotoImportTest {
    @Test
    fun smallPhotoIsDecodedAtFullResolution() {
        assertEquals(1, sampleSizeFor(width = 2_000, height = 1_500, maximumSide = 4_000))
    }

    @Test
    fun exactlyTheLimitIsStillFullResolution() {
        assertEquals(1, sampleSizeFor(width = 4_000, height = 3_000, maximumSide = 4_000))
    }

    @Test
    fun oversizedPhotoHalvesUntilItFits() {
        // 8160 -> 4080 is still over the limit, so the next power of two is the first that fits.
        assertEquals(4, sampleSizeFor(width = 8_160, height = 6_120, maximumSide = 4_000))
    }

    @Test
    fun theLongEdgeDecidesForAPortraitPhoto() {
        assertEquals(2, sampleSizeFor(width = 3_000, height = 6_000, maximumSide = 4_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun anEmptyPhotoIsRejected() {
        sampleSizeFor(width = 0, height = 1_000, maximumSide = 4_000)
    }
}
