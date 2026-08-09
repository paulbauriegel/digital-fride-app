package com.example.groceriesapp.feature.insights

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.example.groceriesapp.data.ConsumptionEvent
import com.example.groceriesapp.data.DigitalReceipt
import com.example.groceriesapp.data.insights.InsightsAggregator
import com.example.groceriesapp.data.insights.InsightsRange
import com.example.groceriesapp.data.insights.periodStartOf
import com.example.groceriesapp.data.insights.shiftPeriods
import com.example.groceriesapp.feature.receipts.SpendSummaryCard
import com.example.groceriesapp.ui.components.EmptyStateCard
import com.example.groceriesapp.ui.components.SectionHeader
import com.example.groceriesapp.ui.components.charts.DonutChart
import com.example.groceriesapp.ui.components.charts.DonutSegment
import com.example.groceriesapp.ui.components.charts.LineChart
import com.example.groceriesapp.ui.components.charts.chartColors
import com.example.groceriesapp.ui.components.charts.otherChartColor
import com.example.groceriesapp.data.taxonomy.GroceryCategory
import com.example.groceriesapp.ui.formatCurrency
import com.example.groceriesapp.ui.theme.spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Mockup view 8 — spending and waste rollups. Spend comes from the device-wide receipt archive;
 * waste comes from the active household's consumption log (see [InsightsAggregator]'s KDoc for
 * the scoping rationale).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    receipts: List<DigitalReceipt>,
    consumptionEvents: List<ConsumptionEvent>,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var range by rememberSaveable { mutableStateOf(InsightsRange.MONTH) }
    // How many whole periods back from today the screen is showing; 0 is the current period.
    var periodsBack by rememberSaveable { mutableIntStateOf(0) }
    val aggregator = remember { InsightsAggregator() }
    val today = LocalDate.now()
    val anchor = shiftPeriods(range, today, -periodsBack.toLong())
    val spend = remember(receipts, range, anchor) {
        aggregator.spendSummary(receipts, range, anchor, today)
    }
    val categories = remember(receipts, range, anchor) {
        aggregator.spendByCategory(receipts, range, anchor)
    }
    val waste = remember(receipts, consumptionEvents, range, anchor) {
        aggregator.wasteSummary(consumptionEvents, receipts, range, anchor)
    }
    val mostWasted = remember(consumptionEvents, range, anchor) {
        aggregator.mostWasted(consumptionEvents, range, anchor)
    }
    // Stop paging once the previous period would sit entirely before the oldest recorded data.
    val earliest = remember(receipts, consumptionEvents) {
        earliestRecordedDate(receipts, consumptionEvents)
    }
    val canStepBack = range != InsightsRange.ALL_TIME && earliest != null &&
        periodStartOf(range, shiftPeriods(range, anchor, -1)) >= periodStartOf(range, earliest)

    // Pinned rather than collapsing: the bar carries the search/filter actions, so it
    // stays put and only picks up the scrolled container colour that marks the boundary.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Insights") },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (receipts.isEmpty() && consumptionEvents.isEmpty()) {
            EmptyStateCard(
                title = "No insights yet",
                body = "Scan or import receipts to see where your grocery money goes — and what " +
                    "ends up wasted.",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(MaterialTheme.spacing.medium),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = MaterialTheme.spacing.medium,
                end = MaterialTheme.spacing.medium,
                bottom = MaterialTheme.spacing.medium,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            item(key = "range") {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    InsightsRange.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            modifier = Modifier.minimumInteractiveComponentSize(),
                            selected = range == option,
                            onClick = {
                                range = option
                                // "3 back" means something different per range, so start at the
                                // current period whenever the range changes.
                                periodsBack = 0
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = InsightsRange.entries.size,
                            ),
                        ) {
                            Text(option.displayName, maxLines = 1)
                        }
                    }
                }
            }
            if (range != InsightsRange.ALL_TIME) {
                item(key = "period") {
                    PeriodStepper(
                        label = spend.periodLabel,
                        canStepBack = canStepBack,
                        canStepForward = periodsBack > 0,
                        onStepBack = { periodsBack += 1 },
                        onStepForward = { periodsBack -= 1 },
                    )
                }
            }
            item(key = "spend") {
                SpendSummaryCard(
                    summary = spend,
                    previousPeriodName = previousPeriodName(range, anchor, periodsBack == 0),
                    showAxisLabels = true,
                )
            }
            if (categories.isNotEmpty()) {
                item(key = "categories-header") {
                    SectionHeader(title = "Spending by category")
                }
                item(key = "categories") {
                    CategorySpendCard(categories = categories)
                }
            }
            item(key = "waste") {
                WasteCard(
                    rangeNoun = rangeNoun(range, anchor, periodsBack == 0),
                    totalCents = waste.totalCents,
                    shareOfSpend = waste.shareOfSpend,
                    trend = waste.trend.map { it.totalCents.toFloat() },
                )
            }
            if (mostWasted.isNotEmpty()) {
                item(key = "most-wasted-header") {
                    SectionHeader(title = "Most wasted")
                }
                items(
                    count = mostWasted.size,
                    key = { "wasted-${mostWasted[it].category.name}" },
                ) { index ->
                    val group = mostWasted[index]
                    MostWastedRow(
                        category = group.category,
                        totalCents = group.totalCents,
                    )
                }
            }
        }
    }
}

/** ‹ August › — steps the whole screen one period into the past or back toward today. */
@Composable
private fun PeriodStepper(
    label: String,
    canStepBack: Boolean,
    canStepForward: Boolean,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onStepBack, enabled = canStepBack) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Earlier period")
        }
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
        )
        IconButton(onClick = onStepForward, enabled = canStepForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Later period")
        }
    }
}

@Composable
private fun CategorySpendCard(
    categories: List<com.example.groceriesapp.data.insights.CategorySpend>,
    modifier: Modifier = Modifier,
) {
    val palette = chartColors()
    val otherColor = otherChartColor()
    fun colorAt(index: Int, category: GroceryCategory) =
        if (category == GroceryCategory.OTHER) otherColor else palette[index % palette.size]

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
        ) {
            DonutChart(
                segments = categories.mapIndexed { index, entry ->
                    DonutSegment(
                        value = entry.totalCents.toFloat(),
                        color = colorAt(index, entry.category),
                    )
                },
                modifier = Modifier.size(140.dp),
                // Short label only: the legend beside it already reads out every category and
                // amount, so a full data summary here would just be announced twice.
                contentDescription = "Spending by category",
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                categories.forEachIndexed { index, entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = colorAt(index, entry.category),
                            modifier = Modifier.size(10.dp),
                        ) {}
                        Text(
                            entry.category.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = MaterialTheme.spacing.small),
                        )
                        Text(
                            formatCurrency(entry.totalCents.toInt()),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WasteCard(
    rangeNoun: String,
    totalCents: Long,
    shareOfSpend: Double?,
    trend: List<Float>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            ) {
                Text(
                    "Wasted $rangeNoun",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (totalCents == 0L) {
                    Text(
                        "No waste recorded — nice!",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        formatCurrency(totalCents.toInt()),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    shareOfSpend?.let { share ->
                        Text(
                            "${"%.1f".format((share * 100))}% of food spending",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (totalCents > 0L) {
                LineChart(
                    values = trend,
                    lineColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .width(110.dp)
                        .height(56.dp),
                    // Nothing else on this card states the direction, so the sparkline has to.
                    contentDescription = trendDescription(trend),
                )
            }
        }
    }
}

@Composable
private fun MostWastedRow(
    category: GroceryCategory,
    totalCents: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(category.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Text(
            category.displayName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            formatCurrency(totalCents.toInt()),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun previousPeriodName(
    range: InsightsRange,
    anchor: LocalDate,
    isCurrentPeriod: Boolean,
): String? = when (range) {
    InsightsRange.WEEK -> if (isCurrentPeriod) "last week" else "the week before"
    InsightsRange.MONTH ->
        anchor.minusMonths(1).month.getDisplayName(TextStyle.FULL, Locale.getDefault())
    InsightsRange.YEAR -> (anchor.year - 1).toString()
    InsightsRange.ALL_TIME -> null
}

private fun rangeNoun(range: InsightsRange, anchor: LocalDate, isCurrentPeriod: Boolean): String =
    when (range) {
        InsightsRange.WEEK -> if (isCurrentPeriod) "this week" else "that week"
        InsightsRange.MONTH -> if (isCurrentPeriod) {
            "this month"
        } else {
            "in ${anchor.month.getDisplayName(TextStyle.FULL, Locale.getDefault())}"
        }
        InsightsRange.YEAR -> if (isCurrentPeriod) "this year" else "in ${anchor.year}"
        InsightsRange.ALL_TIME -> "overall"
    }

/** Oldest date any receipt or consumption event carries; null when there is nothing recorded. */
private fun earliestRecordedDate(
    receipts: List<DigitalReceipt>,
    consumptionEvents: List<ConsumptionEvent>,
): LocalDate? {
    val earliestMillis = (
        receipts.map { it.dateEpochMillis ?: it.createdAtEpochMillis } +
            consumptionEvents.map(ConsumptionEvent::removedAtEpochMillis)
        ).minOrNull() ?: return null
    return Instant.ofEpochMilli(earliestMillis).atZone(ZoneId.systemDefault()).toLocalDate()
}

/**
 * Spoken summary of a waste sparkline. The chart is the only thing on its card that shows a
 * direction, so screen readers need the trend stated rather than the raw points.
 */
private fun trendDescription(trend: List<Float>): String? {
    if (trend.size < 2) return null
    val first = trend.first()
    val last = trend.last()
    val direction = when {
        last > first -> "rising"
        last < first -> "falling"
        else -> "flat"
    }
    return "Waste trend over ${trend.size} periods, $direction"
}
