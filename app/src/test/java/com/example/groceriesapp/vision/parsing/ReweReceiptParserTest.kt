package com.example.groceriesapp.vision.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReweReceiptParserTest {
    private val parser = ReweReceiptParser()

    private fun receipt(body: String) = """
        |  Peterson Warenhandels GmbH & Co.KG
        |        Sakrower Landstraße 1a
        |             14089 Berlin
        |         UID Nr.: DE217623660
        |                                   EUR
        |$body
        | --------------------------------------
        | SUMME                   EUR     43,82
        | ======================================
    """.trimMargin()

    @Test
    fun parsesPlainItemLines() {
        val items = parser.parse(
            receipt(
                """
                |MINI BABYBEL                     4,19 B
                |MIREE MEERRETT.                  1,11 B
                """.trimMargin(),
            ),
        )

        assertEquals(listOf("MINI BABYBEL", "MIREE MEERRETT."), items.map { it.name })
        assertEquals(419L, items[0].lineTotalCents)
        assertEquals("B", items[0].taxFlag)
        assertEquals(1.0, items[0].quantity, 0.001)
        // A bare line carries no quantity breakdown, so the unit is unknown — not assumed "Stk"
        // (the item could just as well be a 500 g pack).
        assertNull(items[0].unit)
        assertNull(items[0].normalizedUnit())
    }

    @Test
    fun attachesIndentedPieceQuantityToPrecedingItem() {
        val items = parser.parse(
            receipt(
                """
                |LACHSROELLCHEN                   5,98 B
                |             2 Stk x    2,99
                """.trimMargin(),
            ),
        )

        val item = items.single()
        assertEquals(2.0, item.quantity, 0.001)
        assertEquals("Stk", item.unit)
        assertEquals("pc", item.normalizedUnit())
        assertEquals(299L, item.pricePerUnitCents)
        assertEquals(598L, item.finalPriceCents)
    }

    @Test
    fun attachesIndentedWeightQuantityToPrecedingItem() {
        val items = parser.parse(
            receipt(
                """
                |BANANEN                          1,65 B
                |         0,830 kg x   1,99 EUR/kg
                """.trimMargin(),
            ),
        )

        val item = items.single()
        assertEquals(0.83, item.quantity, 0.001)
        assertEquals("kg", item.unit)
        assertEquals("kg", item.normalizedUnit())
        assertEquals(199L, item.pricePerUnitCents)
    }

    @Test
    fun subtractsIndentedDiscountFromItemTotal() {
        val items = parser.parse(
            receipt(
                """
                |LACHSROELLCHEN                   2,99 B
                | Frischerabatt                  -0,90 B
                |MINI BABYBEL                     2,59 B
                """.trimMargin(),
            ),
        )

        assertEquals(2, items.size)
        val discounted = items.first()
        assertEquals(299L, discounted.lineTotalCents)
        assertEquals(1, discounted.discounts.size)
        assertEquals("Frischerabatt", discounted.discounts.single().description)
        assertEquals(-90L, discounted.discounts.single().amountCents)
        assertEquals(209L, discounted.finalPriceCents)
        // The discount must not leak onto the next item.
        assertEquals(259L, items[1].finalPriceCents)
    }

    @Test
    fun flagsDepositLines() {
        val items = parser.parse(
            receipt(
                """
                |PFAND 0,15 EUR                   0,15 A *
                |WALDMEISTE SIRUP                 1,79 B
                """.trimMargin(),
            ),
        )

        assertTrue(items[0].isDeposit)
        assertEquals("A", items[0].taxFlag)
        assertFalse(items[1].isDeposit)
    }

    @Test
    fun ignoresHeaderAndStopsAtFooter() {
        val items = parser.parse(
            receipt("MINI BABYBEL                     4,19 B"),
        )

        assertEquals(1, items.size)
        assertFalse(items.any { it.name.contains("SUMME") })
        assertFalse(items.any { it.name.contains("Berlin") })
    }
}
