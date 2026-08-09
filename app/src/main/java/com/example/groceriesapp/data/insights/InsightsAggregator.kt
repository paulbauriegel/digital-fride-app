package com.example.groceriesapp.data.insights

import com.example.groceriesapp.data.ConsumptionEvent
import com.example.groceriesapp.data.ConsumptionReason
import com.example.groceriesapp.data.DigitalReceipt
import com.example.groceriesapp.data.ReceiptItem
import com.example.groceriesapp.data.taxonomy.GroceryCategory
import com.example.groceriesapp.data.taxonomy.resolveCategory
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

enum class InsightsRange(val displayName: String) {
    WEEK("Week"),
    MONTH("Month"),
    YEAR("Year"),
    ALL_TIME("All time"),
}

/** Canonical start date of the bucket [date] falls into; ALL_TIME is one unbounded bucket. */
fun periodStartOf(range: InsightsRange, date: LocalDate): LocalDate = when (range) {
    InsightsRange.WEEK -> date.with(DayOfWeek.MONDAY)
    InsightsRange.MONTH -> date.withDayOfMonth(1)
    InsightsRange.YEAR -> date.withDayOfYear(1)
    InsightsRange.ALL_TIME -> LocalDate.EPOCH
}

/** Moves [date] by [count] whole periods; negative counts step into the past. */
fun shiftPeriods(range: InsightsRange, date: LocalDate, count: Long): LocalDate = when (range) {
    InsightsRange.WEEK -> date.plusWeeks(count)
    InsightsRange.MONTH -> date.plusMonths(count)
    InsightsRange.YEAR -> date.plusYears(count)
    InsightsRange.ALL_TIME -> date
}

/** One chart bucket — a week, month, or year — with a short axis label. */
data class PeriodSpend(val label: String, val totalCents: Long)

data class SpendSummary(
    /** "August", "This week", "2026", or "All time". */
    val periodLabel: String,
    val totalCents: Long,
    /** Spend of the immediately preceding period; null for ALL_TIME or when it has no receipts. */
    val previousTotalCents: Long?,
    /** Rounded percentage change vs. the previous period; negative means spent less. */
    val deltaPercent: Int?,
    /** Trailing buckets ending at the current period, empty buckets included for stable chart slots. */
    val history: List<PeriodSpend>,
)

data class CategorySpend(
    val category: GroceryCategory,
    val totalCents: Long,
    /** This category's fraction of the period's categorized spend, 0..1. */
    val share: Double,
)

data class WasteSummary(
    val totalCents: Long,
    /** Wasted value as a fraction of the same period's spend; null when spend is zero. */
    val shareOfSpend: Double?,
    val trend: List<PeriodSpend>,
)

data class WastedGroup(val category: GroceryCategory, val totalCents: Long)

/**
 * Pure rollups over receipts and the consumption log for the Insights screen and the Receipts
 * summary card. Same design as [com.example.groceriesapp.data.recipes.RecipeMatcher]: no Android
 * dependencies, time injected ([zoneId] plus a `today` parameter) so the JVM tests are
 * deterministic.
 *
 * Scoping: spend is computed from receipts, which are stored device-wide; waste comes from the
 * consumption log, which is per-household. Callers pass whatever the active repository exposes,
 * so an Insights screen shows all receipts alongside the active household's waste.
 */
class InsightsAggregator(private val zoneId: ZoneId = ZoneId.systemDefault()) {

    /**
     * [today] anchors the period being reported — the Insights screen passes an earlier date to
     * step back through months. [now] is the real current date and only affects wording ("This
     * week" vs. a dated label), so it defaults to [today] for callers that never navigate.
     */
    fun spendSummary(
        receipts: List<DigitalReceipt>,
        range: InsightsRange,
        today: LocalDate,
        now: LocalDate = today,
    ): SpendSummary {
        val dated = receipts.mapNotNull { receipt ->
            val value = receipt.spendCents() ?: return@mapNotNull null
            receipt.localDate() to value
        }
        val currentStart = periodStart(range, today)
        val current = dated.sumIn(range, currentStart)
        val previous = if (range == InsightsRange.ALL_TIME) {
            null
        } else {
            val previousStart = minusPeriods(range, currentStart, 1)
            dated
                .filter { (date, _) -> periodStart(range, date) == previousStart }
                .takeIf { it.isNotEmpty() }
                ?.sumOf { (_, value) -> value }
        }
        return SpendSummary(
            periodLabel = periodLabel(range, today, now),
            totalCents = current,
            previousTotalCents = previous,
            deltaPercent = previous?.takeIf { it > 0L }?.let {
                ((current - it) * 100.0 / it).roundToInt()
            },
            history = historyBuckets(range, today).map { start ->
                PeriodSpend(bucketLabel(range, start), dated.sumIn(range, start))
            },
        )
    }

    /**
     * Spend over the [windowDays] days ending on [today], compared with the [windowDays] before
     * that. Unlike [spendSummary] with [InsightsRange.MONTH] this never resets on the 1st, so the
     * Receipts list keeps showing a full window's worth of spending rather than a near-empty month.
     * [history] holds consecutive windows ending at today, oldest first.
     */
    fun rollingSpendSummary(
        receipts: List<DigitalReceipt>,
        today: LocalDate,
        windowDays: Long = ROLLING_WINDOW_DAYS,
        buckets: Int = HISTORY_BUCKETS.toInt(),
    ): SpendSummary {
        val dated = receipts.mapNotNull { receipt ->
            val value = receipt.spendCents() ?: return@mapNotNull null
            receipt.localDate() to value
        }
        fun windowStart(windowsBack: Long): LocalDate =
            today.minusDays(windowDays * (windowsBack + 1) - 1)

        fun sumOfWindow(windowsBack: Long): Long {
            val start = windowStart(windowsBack)
            val end = start.plusDays(windowDays - 1)
            return dated
                .filter { (date, _) -> !date.isBefore(start) && !date.isAfter(end) }
                .sumOf { (_, value) -> value }
        }

        val current = sumOfWindow(0)
        val previous = sumOfWindow(1).takeIf { it > 0L }
        return SpendSummary(
            periodLabel = "Last $windowDays days",
            totalCents = current,
            previousTotalCents = previous,
            deltaPercent = previous?.let { ((current - it) * 100.0 / it).roundToInt() },
            history = (buckets - 1 downTo 0).map { windowsBack ->
                val start = windowStart(windowsBack.toLong())
                PeriodSpend("${start.dayOfMonth} ${shortMonth(start)}", sumOfWindow(windowsBack.toLong()))
            },
        )
    }

    fun spendByCategory(
        receipts: List<DigitalReceipt>,
        range: InsightsRange,
        today: LocalDate,
        // The taxonomy has ~19 categories, so a small top-N sweeps a lot of correctly categorised
        // spend into the trailing bucket; 6 keeps the donut readable without an oversized tail.
        topN: Int = 6,
    ): List<CategorySpend> {
        val currentStart = periodStart(range, today)
        val totals = receipts
            .asSequence()
            .filter { periodStart(range, it.localDate()) == currentStart }
            .flatMap { it.items }
            .filterNot(ReceiptItem::isDepositLine)
            .groupBy { resolveCategory(it.category, it.name) }
            .mapValues { (_, items) -> items.sumOf { (it.priceCents ?: 0).toLong() } }
            .filterValues { it > 0L }
        if (totals.isEmpty()) return emptyList()
        val ranked = totals.entries.sortedByDescending { it.value }
        val top = ranked
            .filter { it.key != GroceryCategory.OTHER }
            .take(topN)
        val otherCents = ranked.sumOf { it.value } - top.sumOf { it.value }
        val grandTotal = ranked.sumOf { it.value }.toDouble()
        return buildList {
            top.forEach { (category, cents) ->
                add(CategorySpend(category, cents, cents / grandTotal))
            }
            if (otherCents > 0L) {
                add(CategorySpend(GroceryCategory.OTHER, otherCents, otherCents / grandTotal))
            }
        }
    }

    fun wasteSummary(
        events: List<ConsumptionEvent>,
        receipts: List<DigitalReceipt>,
        range: InsightsRange,
        today: LocalDate,
    ): WasteSummary {
        val wasted = events.mapNotNull { event ->
            val value = event.wastedCents() ?: return@mapNotNull null
            event.localDate() to value
        }
        val currentStart = periodStart(range, today)
        val totalCents = wasted.sumIn(range, currentStart)
        val spendCents = spendSummary(receipts, range, today).totalCents
        return WasteSummary(
            totalCents = totalCents,
            shareOfSpend = if (spendCents > 0L) totalCents.toDouble() / spendCents else null,
            trend = historyBuckets(range, today).map { start ->
                PeriodSpend(bucketLabel(range, start), wasted.sumIn(range, start))
            },
        )
    }

    fun mostWasted(
        events: List<ConsumptionEvent>,
        range: InsightsRange,
        today: LocalDate,
        limit: Int = 5,
    ): List<WastedGroup> {
        val currentStart = periodStart(range, today)
        return events
            .asSequence()
            .filter { it.wastedCents() != null }
            .filter { periodStart(range, it.localDate()) == currentStart }
            .groupBy { resolveCategory(it.category, it.name) }
            .map { (category, grouped) ->
                WastedGroup(category, grouped.sumOf { (it.priceCents ?: 0).toLong() })
            }
            .sortedByDescending(WastedGroup::totalCents)
            .take(limit)
    }

    // Receipts without an OCR'd total fall back to summing line prices; a receipt with neither
    // carries no usable amount and is excluded from spend entirely.
    private fun DigitalReceipt.spendCents(): Long? {
        totalCents?.let { return it.toLong() }
        val priced = items.mapNotNull(ReceiptItem::priceCents)
        if (priced.isEmpty()) return null
        return priced.sumOf(Int::toLong)
    }

    private fun DigitalReceipt.localDate(): LocalDate =
        epochToLocalDate(dateEpochMillis ?: createdAtEpochMillis)

    private fun ConsumptionEvent.localDate(): LocalDate = epochToLocalDate(removedAtEpochMillis)

    private fun epochToLocalDate(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate()

    private fun List<Pair<LocalDate, Long>>.sumIn(range: InsightsRange, start: LocalDate): Long =
        filter { (date, _) -> periodStart(range, date) == start }.sumOf { (_, value) -> value }

    private fun periodStart(range: InsightsRange, date: LocalDate): LocalDate =
        periodStartOf(range, date)

    private fun minusPeriods(range: InsightsRange, start: LocalDate, count: Long): LocalDate =
        shiftPeriods(range, start, -count)

    /**
     * Trailing chart buckets ending at the current period. ALL_TIME charts the last
     * [ALL_TIME_HISTORY_MONTHS] months instead — a single all-time bar carries no information.
     */
    private fun historyBuckets(range: InsightsRange, today: LocalDate): List<LocalDate> =
        when (range) {
            InsightsRange.ALL_TIME -> {
                val currentMonth = today.withDayOfMonth(1)
                (ALL_TIME_HISTORY_MONTHS - 1 downTo 0L).map(currentMonth::minusMonths)
            }
            else -> {
                val currentStart = periodStart(range, today)
                (HISTORY_BUCKETS - 1 downTo 0L).map { minusPeriods(range, currentStart, it) }
            }
        }

    private fun bucketLabel(range: InsightsRange, start: LocalDate): String = when (range) {
        InsightsRange.WEEK -> "${start.dayOfMonth} ${shortMonth(start)}"
        InsightsRange.MONTH, InsightsRange.ALL_TIME -> shortMonth(start)
        InsightsRange.YEAR -> start.year.toString()
    }

    private fun periodLabel(range: InsightsRange, today: LocalDate, now: LocalDate): String {
        val isCurrent = periodStart(range, today) == periodStart(range, now)
        return when (range) {
            InsightsRange.WEEK ->
                if (isCurrent) "This week" else "Week of ${bucketLabel(range, periodStart(range, today))}"
            InsightsRange.MONTH -> monthLabel(today, now)
            InsightsRange.YEAR -> today.year.toString()
            InsightsRange.ALL_TIME -> "All time"
        }
    }

    /** "August" for a month in the current year, "August 2025" once the year differs from [now]. */
    private fun monthLabel(date: LocalDate, now: LocalDate): String {
        val month = date.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        return if (date.year == now.year) month else "$month ${date.year}"
    }

    private fun shortMonth(date: LocalDate): String =
        date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())

    private companion object {
        const val HISTORY_BUCKETS = 6L
        const val ALL_TIME_HISTORY_MONTHS = 12L
        const val ROLLING_WINDOW_DAYS = 30L
    }
}

private fun ConsumptionEvent.wastedCents(): Long? {
    if (reason != ConsumptionReason.THROWN_AWAY) return null
    return priceCents?.toLong()
}

/**
 * Bottle/container deposit lines are real receipt rows but not groceries; keeping them out of the
 * category rollup mirrors the import flow's default-unselected treatment of the same rows.
 */
private fun ReceiptItem.isDepositLine(): Boolean = name.contains("pfand", ignoreCase = true)
