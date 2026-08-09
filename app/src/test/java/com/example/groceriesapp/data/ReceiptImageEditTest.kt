package com.example.groceriesapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReceiptImageEditTest {
    @Test
    fun defaultEditPreservesWholeImage() {
        assertEquals(
            ReceiptImageEdit(
                rotationDegrees = 0f,
                cropLeft = 0f,
                cropTop = 0f,
                cropRight = 1f,
                cropBottom = 1f,
            ),
            ReceiptImageEdit(),
        )
    }

    @Test
    fun rotationAcceptsQuarterTurnsPlusFineStraightening() {
        ReceiptImageEdit(rotationDegrees = -90f)
        ReceiptImageEdit(rotationDegrees = 90f)
        ReceiptImageEdit(rotationDegrees = 180f)
        ReceiptImageEdit(rotationDegrees = -135.5f)
    }

    @Test
    fun rotationRejectsValuesOutsideHalfTurn() {
        assertThrows(IllegalArgumentException::class.java) {
            ReceiptImageEdit(rotationDegrees = 180.01f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReceiptImageEdit(rotationDegrees = -180.01f)
        }
    }

    @Test
    fun cropCannotCollapseEitherAxis() {
        assertThrows(IllegalArgumentException::class.java) {
            ReceiptImageEdit(cropLeft = 0.5f, cropRight = 0.52f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReceiptImageEdit(cropTop = 0.7f, cropBottom = 0.7f)
        }
    }
}
