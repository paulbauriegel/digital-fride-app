package com.example.groceriesapp.feature.receipts

import com.example.groceriesapp.data.DigitalReceipt
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptGroupingTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 24)

    private fun receiptOn(date: String) = DigitalReceipt(
        id = date,
        dateEpochMillis = LocalDate.parse(date).atTime(12, 0).toInstant(zone).toEpochMilli(),
    )

    @Test
    fun `older receipts fall under their year, the current year included`() {
        val receipts = listOf(
            "2026-11-24", "2026-11-20", "2026-11-03", "2026-08-15", "2025-12-31", "2023-01-01",
        ).map(::receiptOn)

        val sections = groupByRecency(receipts, today, zone)

        assertEquals(
            listOf(
                "Today" to listOf("2026-11-24"),
                "This Week" to listOf("2026-11-20"),
                "This Month" to listOf("2026-11-03"),
                "2026" to listOf("2026-08-15"),
                "2025" to listOf("2025-12-31"),
                "2023" to listOf("2023-01-01"),
            ),
            sections.map { (label, group) -> label to group.map(DigitalReceipt::id) },
        )
    }
}
