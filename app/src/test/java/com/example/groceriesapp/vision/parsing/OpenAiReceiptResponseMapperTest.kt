package com.example.groceriesapp.vision.parsing

import java.time.LocalDate
import java.time.ZoneOffset
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiReceiptResponseMapperTest {
    private fun map(content: String): ParsedReceipt =
        OpenAiReceiptResponseMapper.map(content, ZoneOffset.UTC)

    @Test
    fun mapsACompleteResponse() {
        val result = map(
            """
            {
              "merchant": "REWE",
              "date": "2026-08-01",
              "total_cents": 1237,
              "items": [
                {"name": "Bio Joghurt Natur", "quantity": 2, "unit": "pc", "line_total_cents": 258},
                {"name": "Bananen", "quantity": 0.486, "unit": "kg", "line_total_cents": 77}
              ]
            }
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
        assertEquals(258L, result.items[0].lineTotalCents)
        assertEquals("pc", result.items[0].unit)
        assertEquals(0.486, result.items[1].quantity, 1e-9)
    }

    @Test
    fun mapsNullsAndEmptyItems() {
        val result = map(
            """{"merchant": null, "date": null, "total_cents": null, "items": []}""",
        )

        assertNull(result.merchant)
        assertNull(result.purchasedAtEpochMillis)
        assertNull(result.totalCents)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun unparseableDateBecomesNullInsteadOfThrowing() {
        val result = map(
            """{"merchant": "REWE", "date": "01.08.2026", "total_cents": 100, "items": []}""",
        )

        assertNull(result.purchasedAtEpochMillis)
    }

    @Test
    fun invalidUnitIsCoercedToNull() {
        val result = map(
            """
            {"merchant": null, "date": null, "total_cents": null, "items": [
              {"name": "Saft", "quantity": 1, "unit": "bottle", "line_total_cents": 199}
            ]}
            """.trimIndent(),
        )

        assertNull(result.items.single().unit)
    }

    @Test
    fun nonPositivePricesAndBlankNamesAreDropped() {
        val result = map(
            """
            {"merchant": null, "date": null, "total_cents": null, "items": [
              {"name": "Pfand", "quantity": 1, "unit": null, "line_total_cents": -25},
              {"name": "  ", "quantity": 1, "unit": null, "line_total_cents": 100},
              {"name": "Brot", "quantity": 1, "unit": null, "line_total_cents": 349}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf("Brot"), result.items.map(ParsedLineItem::name))
    }

    @Test
    fun nonPositiveQuantityDefaultsToOne() {
        val result = map(
            """
            {"merchant": null, "date": null, "total_cents": null, "items": [
              {"name": "Brot", "quantity": 0, "unit": null, "line_total_cents": 349}
            ]}
            """.trimIndent(),
        )

        assertEquals(1.0, result.items.single().quantity, 0.0)
    }

    @Test
    fun assignsCategoriesThroughTheSharedCatalog() {
        val result = map(
            """
            {"merchant": null, "date": null, "total_cents": null, "items": [
              {"name": "Milch", "quantity": 1, "unit": null, "line_total_cents": 119}
            ]}
            """.trimIndent(),
        )

        assertEquals("DAIRY", result.items.single().category)
    }

    @Test
    fun malformedJsonThrows() {
        assertThrows(JSONException::class.java) { map("not json") }
    }
}
