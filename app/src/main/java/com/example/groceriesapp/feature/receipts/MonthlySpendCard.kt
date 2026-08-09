package com.example.groceriesapp.feature.receipts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.insights.SpendSummary
import com.example.groceriesapp.ui.components.charts.BarChart
import com.example.groceriesapp.ui.components.charts.MiniBarChart
import com.example.groceriesapp.ui.formatCurrency
import com.example.groceriesapp.ui.theme.spacing
import kotlin.math.abs

/**
 * Period spend summary shared by the Receipts list (compact, [showAxisLabels] = false) and the
 * Insights screen (labeled chart). "Less than last period" renders in primary green — spending
 * less is the good direction here.
 */
@Composable
fun SpendSummaryCard(
    summary: SpendSummary,
    previousPeriodName: String?,
    modifier: Modifier = Modifier,
    showAxisLabels: Boolean = false,
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
                    summary.periodLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatCurrency(summary.totalCents.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                val delta = summary.deltaPercent
                if (delta != null && previousPeriodName != null) {
                    val spentLess = delta <= 0
                    val deltaColor = if (spentLess) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "vs. $previousPeriodName ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            if (spentLess) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                            contentDescription = null,
                            tint = deltaColor,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            " ${abs(delta)}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = deltaColor,
                        )
                    }
                }
            }
            val values = summary.history.map { it.totalCents.toFloat() }
            val highlight = summary.history.lastIndex
            val historyDescription = summary.history.joinToString(", ") { entry ->
                "${entry.label} ${formatCurrency(
                    entry.totalCents.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )}"
            }.takeIf { summary.history.isNotEmpty() }
                ?.let { "Spending history: $it" }
            if (showAxisLabels) {
                BarChart(
                    values = values,
                    labels = summary.history.map { it.label },
                    highlightIndex = highlight,
                    modifier = Modifier
                        .weight(1f)
                        .height(96.dp),
                    contentDescription = historyDescription,
                )
            } else {
                MiniBarChart(
                    values = values,
                    highlightIndex = highlight,
                    modifier = Modifier.width(120.dp),
                    contentDescription = historyDescription,
                )
            }
        }
    }
}
