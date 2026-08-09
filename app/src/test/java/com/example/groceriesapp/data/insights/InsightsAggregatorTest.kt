package com.example.groceriesapp.data.insights

import com.example.groceriesapp.data.ConsumptionEvent
import com.example.groceriesapp.data.ConsumptionReason
import com.example.groceriesapp.data.DigitalReceipt
import com.example.groceriesapp.data.ReceiptItem
import com.example.groceriesapp.data.taxonomy.GroceryCategory
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsAggregatorTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val aggregator = InsightsAggregator(zone)

    private fun epochOf(date: LocalDate): Long =
        date.atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli()

    private fun receipt(
        date: LocalDate,
        totalCents: Int?,
        items: List<ReceiptItem> = emptyList(),
        id: String = "r-$date-$totalCents",
    ) = DigitalReceipt(
        id = id,
        dateEpochMillis = epochOf(date),
        totalCents = totalCents,
        items = items,
        createdAtEpochMillis = epochOf(date),
    )

    private fun thrownAway(
        date: LocalDate,
        priceCents: Int?,
        category: String? = null,
        name: String = "Item",
        reason: ConsumptionReason = ConsumptionReason.THROWN_AWAY,
    ) = ConsumptionEvent(
        itemId = "i-$date-$priceCents",
        name = name,
        category = category,
        priceCents = priceCents,
        reason = reason,
        removedAtEpochMillis = epochOf(date),
    )

    private fun monthName(date: LocalDate): String =
        date.month.getDisplayName(TextStyle.FULL, Locale.getDefault())

    private fun monthShort(date: LocalDate): String =
        date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())

    // --- spendSummary ---

    @Test
    fun monthSpendSumsCurrentMonthAndComparesToPrevious() {
        val today = LocalDate.of(2026, 8, 8)
        val receipts = listOf(
            receipt(LocalDate.of(2026, 8, 3), totalCents = 15_000),
            receipt(LocalDate.of(2026, 8, 7), totalCents = 5_000),
            receipt(LocalDate.of(2026, 7, 20), totalCents = 25_000),
            receipt(LocalDate.of(2026, 6, 1), totalCents = 99_000),
        )

        val summary = aggregator.spendSummary(receipts, InsightsRange.MONTH, today)

        assertEquals(20_000L, summary.totalCents)
        assertEquals(25_000L, summary.previousTotalCents)
        assertEquals(-20, summary.deltaPercent)
        assertEquals(6, summary.history.size)
        assertEquals(20_000L, summary.history.last().totalCents)
        // March–May carried no receipts but still occupy chart slots.
        assertEquals(listOf(0L, 0L, 0L), summary.history.take(3).map { it.totalCents })
    }

    @Test
    fun decemberToJanuaryBoundaryFindsThePreviousYearMonth() {
        val today = LocalDate.of(2026, 1, 15)
        val receipts = listOf(
            receipt(LocalDate.of(2026, 1, 2), totalCents = 1_000),
            receipt(LocalDate.of(2025, 12, 30), totalCents = 2_000),
        )

        val summary = aggregator.spendSummary(receipts, InsightsRange.MONTH, today)

        assertEquals(1_000L, summary.totalCents)
        assertEquals(2_000L, summary.previousTotalCents)
        assertEquals(-50, summary.deltaPercent)
    }

    @Test
    fun nullTotalFallsBackToSummingItemPrices() {
        val today = LocalDate.of(2026, 8, 8)
        val receipts = listOf(
            receipt(
                LocalDate.of(2026, 8, 4),
                totalCents = null,
                items = listOf(
                    ReceiptItem(name = "Milch", priceCents = 119),
                    ReceiptItem(name = "Brot", priceCents = 219),
                    ReceiptItem(name = "Unbepreist"),
                ),
            ),
        )

        assertEquals(
            338L,
            aggregator.spendSummary(receipts, InsightsRange.MONTH, today).totalCents,
        )
    }

    @Test
    fun receiptWithNoUsableAmountIsExcludedFromSpend() {
        val today = LocalDate.of(2026, 8, 8)
        val receipts = listOf(
            receipt(
                LocalDate.of(2026, 8, 4),
                totalCents = null,
                items = listOf(ReceiptItem(name = "Unleserlich")),
            ),
        )

        val summary = aggregator.spendSummary(receipts, InsightsRange.MONTH, today)

        assertEquals(0L, summary.totalCents)
        assertNull(summary.previousTotalCents)
        assertNull(summary.deltaPercent)
    }

    @Test
    fun weekBucketsStartOnMonday() {
        // 2026-08-05 is a Wednesday; its week starts Monday 2026-08-03.
        val today = LocalDate.of(2026, 8, 5)
        val receipts = listOf(
            receipt(LocalDate.of(2026, 8, 3), totalCents = 700),
            receipt(LocalDate.of(2026, 8, 2), totalCents = 300), // Sunday → previous week
        )

        val summary = aggregator.spendSummary(receipts, InsightsRange.WEEK, today)

        assertEquals(700L, summary.totalCents)
        assertEquals(300L, summary.previousTotalCents)
        assertEquals("This week", summary.periodLabel)
    }

    @Test
    fun anchoringToAnEarlierMonthReportsThatMonthAgainstItsOwnPredecessor() {
        val now = LocalDate.of(2026, 8, 8)
        val anchor = shiftPeriods(InsightsRange.MONTH, now, -1)
        val receipts = listOf(
            receipt(LocalDate.of(2026, 8, 3), totalCents = 15_000),
            receipt(LocalDate.of(2026, 7, 20), totalCents = 8_000),
            receipt(LocalDate.of(2026, 6, 4), totalCents = 4_000),
        )

        val summary = aggregator.spendSummary(receipts, InsightsRange.MONTH, anchor, now)

        assertEquals(8_000L, summary.totalCents)
        assertEquals(4_000L, summary.previousTotalCents)
        assertEquals(monthName(LocalDate.of(2026, 7, 1)), summary.periodLabel)
        assertEquals(monthShort(LocalDate.of(2026, 7, 1)), summary.history.last().label)
    }

    @Test
    fun monthLabelCarriesTheYearOnceItLeavesTheCurrentOne() {
        val now = LocalDate.of(2026, 2, 10)
        val anchor = shiftPeriods(InsightsRange.MONTH, now, -3)

        val summary = aggregator.spendSummary(emptyList(), InsightsRange.MONTH, anchor, now)

        assertEquals("${monthName(LocalDate.of(2025, 11, 1))} 2025", summary.periodLabel)
    }

    @Test
    fun weekLabelStopsSayingThisWeekOnceNavigatedAway() {
        val now = LocalDate.of(2026, 8, 5)
        val anchor = shiftPeriods(InsightsRange.WEEK, now, -1)

        val summary = aggregator.spendSummary(emptyList(), InsightsRange.WEEK, anchor, now)

        // The week of Wednesday 2026-08-05 starts Monday the 3rd; one week back is the 27th.
        assertEquals("Week of 27 ${monthShort(LocalDate.of(2026, 7, 27))}", summary.periodLabel)
    }

    @Test
    fun rollingWindowCoversThirtyDaysBackFromTodayNotTheCalendarMonth() {
        val today = LocalDate.of(2026, 8, 8) // window runs 2026-07-10 .. 2026-08-08.
        val receipts = listOf(
            receipt(LocalDate.of(2026, 8, 8), totalCents = 2_000),
            receipt(LocalDate.of(2026, 7, 10), totalCents = 1_000), // first day of the window
            receipt(LocalDate.of(2026, 7, 9), totalCents = 500), // last day of the previous one
            receipt(LocalDate.of(2026, 6, 10), totalCents = 500), // first day of the previous one
            receipt(LocalDate.of(2026, 6, 9), totalCents = 90_000), // older than both windows
        )

        val summary = aggregator.rollingSpendSummary(receipts, today)

        assertEquals(3_000L, summary.totalCents)
        assertEquals(1_000L, summary.previousTotalCents)
        assertEquals(200, summary.deltaPercent)
        assertEquals("Last 30 days", summary.periodLabel)
        assertEquals(6, summary.history.size)
        assertEquals(3_000L, summary.history.last().totalCents)
        assertEquals("10 ${monthShort(LocalDate.of(2026, 7, 10))}", summary.history.last().label)
    }

    @Test
    fun rollingWindowHasNoDeltaWhenThePreviousWindowIsEmpty() {
        val today = LocalDate.of(2026, 8, 8)
        val receipts = listOf(receipt(LocalDate.of(2026, 8, 1), totalCents = 4_000))

        val summary = aggregator.rollingSpendSummary(receipts, today)

        assertEquals(4_000L, summary.totalCents)
        assertNull(summary.previousTotalCents)
        assertNull(summary.deltaPercent)
    }

    @Test
    fun allTimeHasNoPreviousPeriod() {
        val today = LocalDate.of(2026, 8, 8)
        val receipts = listOf(
            receipt(LocalDate.of(2024, 1, 1), totalCents = 1_000),
            receipt(LocalDate.of(2026, 8, 1), totalCents = 2_000),
        )

        val summary = aggregator.spendSummary(receipts, InsightsRange.ALL_TIME, today)

        assertEquals(3_000L, summary.totalCents)
        assertNull(summary.previousTotalCents)
        assertNull(summary.deltaPercent)
        assertEquals("All time", summary.periodLabel)
        assertEquals(12, summary.history.size)
    }

    @Test
    fun deltaIsNullWhenPreviousPeriodHasNoReceipts() {
        val today = LocalDate.of(2026, 8, 8)
        val receipts = listOf(receipt(LocalDate.of(2026, 8, 3), totalCents = 4_000))

        val summary = aggregator.spendSummary(receipts, InsightsRange.MONTH, today)

        assertNull(summary.previousTotalCents)
        assertNull(summary.deltaPercent)
    }

    // --- spendByCategory ---

    @Test
    fun categoryRollupRanksTopCategoriesAndCollapsesTheRestIntoOther() {
        val today = LocalDate.of(2026, 8, 8)
        val items = listOf(
            ReceiptItem(name = "Apfel", priceCents = 500, category = "FRUIT"),
            ReceiptItem(name = "Milch", priceCents = 400, category = "DAIRY"),
            ReceiptItem(name = "Brot", priceCents = 300, category = "BAKERY"),
            ReceiptItem(name = "Hähnchen", priceCents = 200, category = "MEAT"),
            ReceiptItem(name = "Saft", priceCents = 100, category = "DRINKS"),
            ReceiptItem(name = "Xyzzy Unbekannt", priceCents = 50, category = null),
        )
        val receipts = listOf(receipt(LocalDate.of(2026, 8, 4), totalCents = null, items = items))

        val rollup = aggregator.spendByCategory(receipts, InsightsRange.MONTH, today, topN = 4)

        assertEquals(
            listOf(
                GroceryCategory.FRUIT,
                GroceryCategory.DAIRY,
                GroceryCategory.BAKERY,
                GroceryCategory.MEAT,
                GroceryCategory.OTHER,
            ),
            rollup.map(CategorySpend::category),
        )
        // Drinks (100) and the unknown item (50) fold into Other.
        assertEquals(150L, rollup.last().totalCents)
        assertEquals(1.0, rollup.sumOf(CategorySpend::share), 1e-9)
    }

    @Test
    fun depositLinesAreExcludedFromCategorySpend() {
        val today = LocalDate.of(2026, 8, 8)
        val items = listOf(
            ReceiptItem(name = "Milch", priceCents = 119, category = "DAIRY"),
            ReceiptItem(name = "Pfand Fl", priceCents = 25, category = null),
        )
        val receipts = listOf(receipt(LocalDate.of(2026, 8, 4), totalCents = null, items = items))

        val rollup = aggregator.spendByCategory(receipts, InsightsRange.MONTH, today)

        assertEquals(listOf(GroceryCategory.DAIRY), rollup.map(CategorySpend::category))
        assertEquals(119L, rollup.single().totalCents)
    }

    // --- waste ---

    @Test
    fun wasteCountsOnlyPricedThrownAwayEvents() {
        val today = LocalDate.of(2026, 8, 8)
        val events = listOf(
            thrownAway(LocalDate.of(2026, 8, 2), priceCents = 580),
            thrownAway(LocalDate.of(2026, 8, 3), priceCents = null),
            thrownAway(LocalDate.of(2026, 8, 4), priceCents = 300, reason = ConsumptionReason.USED_UP),
            thrownAway(LocalDate.of(2026, 7, 30), priceCents = 999),
        )
        val receipts = listOf(receipt(LocalDate.of(2026, 8, 1), totalCents = 11_600))

        val waste = aggregator.wasteSummary(events, receipts, InsightsRange.MONTH, today)

        assertEquals(580L, waste.totalCents)
        assertEquals(0.05, waste.shareOfSpend!!, 1e-9)
        assertEquals(6, waste.trend.size)
        assertEquals(580L, waste.trend.last().totalCents)
        assertEquals(999L, waste.trend[4].totalCents)
    }

    @Test
    fun wasteShareIsNullWhenNothingWasSpent() {
        val today = LocalDate.of(2026, 8, 8)
        val events = listOf(thrownAway(LocalDate.of(2026, 8, 2), priceCents = 100))

        val waste = aggregator.wasteSummary(events, emptyList(), InsightsRange.MONTH, today)

        assertEquals(100L, waste.totalCents)
        assertNull(waste.shareOfSpend)
    }

    @Test
    fun mostWastedGroupsByCategoryAndOrdersByValue() {
        val today = LocalDate.of(2026, 8, 8)
        val events = listOf(
            thrownAway(LocalDate.of(2026, 8, 1), 300, category = "VEGETABLES", name = "Salat"),
            thrownAway(LocalDate.of(2026, 8, 2), 280, category = "VEGETABLES", name = "Spinat"),
            thrownAway(LocalDate.of(2026, 8, 3), 420, category = "DAIRY", name = "Joghurt"),
            thrownAway(LocalDate.of(2026, 8, 4), 310, category = "BAKERY", name = "Brot"),
            thrownAway(LocalDate.of(2026, 7, 1), 9_999, category = "MEAT", name = "Steak"),
        )

        val wasted = aggregator.mostWasted(events, InsightsRange.MONTH, today, limit = 2)

        assertEquals(
            listOf(GroceryCategory.VEGETABLES, GroceryCategory.DAIRY),
            wasted.map(WastedGroup::category),
        )
        assertEquals(580L, wasted.first().totalCents)
    }

    @Test
    fun emptyInputsProduceEmptyOrZeroResults() {
        val today = LocalDate.of(2026, 8, 8)

        assertEquals(
            0L,
            aggregator.spendSummary(emptyList(), InsightsRange.MONTH, today).totalCents,
        )
        assertTrue(aggregator.spendByCategory(emptyList(), InsightsRange.MONTH, today).isEmpty())
        assertTrue(aggregator.mostWasted(emptyList(), InsightsRange.MONTH, today).isEmpty())
    }
}
