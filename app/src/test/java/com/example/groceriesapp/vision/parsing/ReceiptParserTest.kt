package com.example.groceriesapp.vision.parsing

import com.example.groceriesapp.data.taxonomy.GroceryCategory
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptParserTest {
    private val parser = ReceiptParser(ZoneOffset.UTC)

    @Test
    fun parsesGermanReceiptWithoutInventingUnsupportedFields() {
        val result = parser.parse(
            """
            REWE
            Musterstraße 12
            27.07.2026 16:20
            Bio Milch 1L             1,49
            Bananen 1,2 kg           2,39
            Vollkornbrot             2,29
            PFAND                    0,25
            GESAMT                  6,42 EUR
            BAR                    10,00
            """.trimIndent(),
        )

        assertEquals("REWE", result.merchant)
        assertNotNull(result.purchasedAtEpochMillis)
        assertEquals(642L, result.totalCents)
        assertEquals(3, result.items.size)
        assertEquals(GroceryCategory.DAIRY.name, result.items[0].category)
        assertEquals("WHOLE_MILK", result.items[0].foodConceptId)
        assertEquals(1.2, result.items[1].quantity, 0.001)
        assertEquals("kg", result.items[1].unit)
        assertFalse(result.items.any { it.name.contains("Pfand", ignoreCase = true) })
    }

    @Test
    fun returnsNullTotalWhenNoLabeledTotalExists() {
        val result = parser.parse(
            """
            EDEKA
            Tomaten 2,49
            Brot 1,99
            """.trimIndent(),
        )

        assertNull(result.totalCents)
        assertEquals(2, result.items.size)
    }

    @Test
    fun ignoresDiscountAndPaymentRows() {
        val result = parser.parse(
            """
            LIDL
            Naturjoghurt 0,79
            Rabatt -0,20
            Kartenzahlung 0,59
            SUMME 0,59
            """.trimIndent(),
        )

        assertEquals(listOf("Naturjoghurt"), result.items.map { it.name })
    }

    @Test
    fun acceptsDotDecimalAndTwoDigitYear() {
        val result = parser.parse(
            """
            ALDI
            27.07.26
            2 x Äpfel 3.98
            SUMME 3.98
            """.trimIndent(),
        )

        assertEquals(398L, result.totalCents)
        assertEquals(398L, result.items.single().lineTotalCents)
        assertEquals(2.0, result.items.single().quantity, 0.001)
        assertNotNull(result.purchasedAtEpochMillis)
    }

    @Test
    fun attachesPieceSubLineToPrecedingItemInsteadOfInventingOne() {
        val result = parser.parse(
            """
            REWE
            Mango Ayran 1,70
            2 Stk x 0,85
            SUMME 1,70
            """.trimIndent(),
        )

        val item = result.items.single()
        assertEquals("Mango ayran", item.name)
        assertEquals(2.0, item.quantity, 0.001)
        assertEquals("pc", item.unit)
        assertEquals(170L, item.lineTotalCents)
    }

    @Test
    fun attachesWeightSubLineToPrecedingItem() {
        val result = parser.parse(
            """
            REWE
            Zwetschge 2,07
            1,558 kg x 1,33 EUR/kg
            SUMME 2,07
            """.trimIndent(),
        )

        val item = result.items.single()
        assertEquals(1.558, item.quantity, 0.001)
        assertEquals("kg", item.unit)
        assertEquals(207L, item.lineTotalCents)
    }

    @Test
    fun dropsSubQuantityLineWithoutPrecedingItem() {
        val result = parser.parse(
            """
            REWE
            3 Stk x 2,49
            SUMME 7,47
            """.trimIndent(),
        )

        assertTrue(result.items.isEmpty())
    }

    @Test
    fun marksOcrAmbiguityForExplicitReview() {
        val result = parser.parse(
            """
            REWE
            M?lch 1,49
            GESAMT 1,49
            """.trimIndent(),
        )

        assertTrue(result.items.single().needsReview)
    }
}
