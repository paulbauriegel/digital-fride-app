package com.example.groceriesapp.vision.parsing

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraniteReceiptResponseParserTest {
    private val parser = GraniteReceiptResponseParser()

    private fun parse(text: String): ParsedReceipt = parser.parse(text, ZoneOffset.UTC)

    @Test
    fun parsesTheWorkedExampleFromThePrompt() {
        val result = parse(
            """
            MERCHANT: REWE
            DATE: 2026-08-01
            TOTAL: 12,37
            ITEM: Bio Joghurt Natur | 2 | pc | 2,58
            ITEM: Bananen | 0,486 | kg | 0,77
            """.trimIndent(),
        )

        assertEquals("REWE", result.merchant)
        assertEquals(
            LocalDate.of(2026, 8, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            result.purchasedAtEpochMillis,
        )
        assertEquals(1237L, result.totalCents)
        assertEquals("EUR", result.currencyCode)
        assertEquals(2, result.items.size)
        val yogurt = result.items[0]
        assertEquals(2.0, yogurt.quantity, 0.0)
        assertEquals("pc", yogurt.unit)
        assertEquals(258L, yogurt.lineTotalCents)
        val bananas = result.items[1]
        assertEquals("Bananen", bananas.name)
        assertEquals(0.486, bananas.quantity, 1e-9)
        assertEquals("kg", bananas.unit)
        assertEquals(77L, bananas.lineTotalCents)
    }

    @Test
    fun toleratesThreeAndTwoFieldItemLines() {
        val result = parse(
            """
            ITEM: Milch | 2 | 2,38
            ITEM: Brot | 3,49
            """.trimIndent(),
        )

        assertEquals(2, result.items.size)
        assertEquals(2.0, result.items[0].quantity, 0.0)
        assertNull(result.items[0].unit)
        assertEquals(238L, result.items[0].lineTotalCents)
        assertEquals(1.0, result.items[1].quantity, 0.0)
        assertEquals(349L, result.items[1].lineTotalCents)
    }

    @Test
    fun placeholdersBecomeNulls() {
        val result = parse(
            """
            MERCHANT: -
            DATE: -
            TOTAL: -
            ITEM: Milch | 1 | - | 1,19
            """.trimIndent(),
        )

        assertNull(result.merchant)
        assertNull(result.purchasedAtEpochMillis)
        assertNull(result.totalCents)
        assertNull(result.items.single().unit)
    }

    @Test
    fun ignoresChatterFencesAndUnknownLines() {
        val result = parse(
            """
            Sure, here is the extracted data:
            ```
            MERCHANT: Edeka
            ITEM: Butter | 1 | pc | 2,49
            ```
            I hope this helps!
            """.trimIndent(),
        )

        assertEquals("Edeka", result.merchant)
        assertEquals(1, result.items.size)
    }

    @Test
    fun firstMerchantDateAndTotalWin() {
        val result = parse(
            """
            MERCHANT: REWE
            MERCHANT: Lidl
            TOTAL: 5,00
            TOTAL: 9,99
            """.trimIndent(),
        )

        assertEquals("REWE", result.merchant)
        assertEquals(500L, result.totalCents)
    }

    @Test
    fun acceptsGermanDateFormat() {
        val result = parse("DATE: 1.8.2026")

        assertEquals(
            LocalDate.of(2026, 8, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            result.purchasedAtEpochMillis,
        )
    }

    @Test
    fun acceptsDotDecimalsAndNormalizesUnits() {
        val result = parse("ITEM: Bananen | 0.486 | KG | 0.77\nITEM: Joghurt | 2 | Stk | 2,58")

        assertEquals(0.486, result.items[0].quantity, 1e-9)
        assertEquals("kg", result.items[0].unit)
        assertEquals(77L, result.items[0].lineTotalCents)
        assertEquals("pc", result.items[1].unit)
    }

    @Test
    fun dropsUnparseableAndNonPositiveItems() {
        val result = parse(
            """
            ITEM: Milch | 1 | pc | free
            ITEM: Pfand | 1 | pc | -0,25
            ITEM: X | 1,00
            ITEM:
            ITEM: Brot | 1 | pc | 3,49
            """.trimIndent(),
        )

        assertEquals(listOf("Brot"), result.items.map(ParsedLineItem::name))
    }

    @Test
    fun unknownUnitBecomesNull() {
        val result = parse("ITEM: Saft | 1 | bottle | 1,99")

        assertNull(result.items.single().unit)
    }

    @Test
    fun assignsCategoriesThroughTheSharedCatalog() {
        val result = parse("ITEM: Milch | 1 | - | 1,19")

        val item = result.items.single()
        assertEquals("Milch", item.name)
        assertEquals("DAIRY", item.category)
        assertTrue(item.foodConceptId != null)
    }

    @Test
    fun emptyResponseYieldsEmptyReceipt() {
        val result = parse("The receipt could not be read.")

        assertNull(result.merchant)
        assertTrue(result.items.isEmpty())
    }
}
