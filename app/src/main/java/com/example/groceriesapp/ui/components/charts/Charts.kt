package com.example.groceriesapp.ui.components.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn Canvas charts for Insights and the Receipts summary card. No charting dependency on
 * purpose — the shapes needed here (a handful of bars, one donut, one sparkline) are simpler than
 * any library's API, and Canvas is the codebase's precedent for custom drawing.
 *
 * All of them tolerate empty and all-zero input by drawing only their track/baseline.
 */

/**
 * Charts draw straight to a Canvas, which is invisible to screen readers. Callers pass a spoken
 * summary of what the shape says so the data isn't lost when the picture can't be seen.
 */
internal fun Modifier.chartSemantics(description: String?): Modifier =
    if (description == null) this else semantics { contentDescription = description }

/** Vertical bars with optional x-axis labels; [highlightIndex] renders in primary. */
@Composable
fun BarChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    labels: List<String>? = null,
    highlightIndex: Int? = null,
    barCornerRadius: Dp = 4.dp,
    contentDescription: String? = null,
) {
    val barColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlightColor = MaterialTheme.colorScheme.primary
    val animatedScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 500),
        label = "barGrow",
    )
    Column(modifier = modifier.chartSemantics(contentDescription)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true),
        ) {
            if (values.isEmpty()) return@Canvas
            val max = values.max().takeIf { it > 0f } ?: 1f
            val slot = size.width / values.size
            val barWidth = slot * 0.55f
            val corner = CornerRadius(barCornerRadius.toPx())
            values.forEachIndexed { index, value ->
                val fraction = (value / max).coerceIn(0f, 1f) * animatedScale
                // A zero bucket still shows a 2dp nub so the slot reads as "measured, nothing
                // spent" rather than missing data.
                val barHeight = (size.height * fraction).coerceAtLeast(2.dp.toPx())
                drawRoundRect(
                    color = if (index == highlightIndex) highlightColor else barColor,
                    topLeft = Offset(
                        x = slot * index + (slot - barWidth) / 2f,
                        y = size.height - barHeight,
                    ),
                    size = Size(barWidth, barHeight),
                    cornerRadius = corner,
                )
            }
        }
        if (labels != null && labels.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                labels.forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Label-less, tight bar chart for summary cards. */
@Composable
fun MiniBarChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    highlightIndex: Int? = null,
    contentDescription: String? = null,
) {
    BarChart(
        values = values,
        labels = null,
        highlightIndex = highlightIndex,
        barCornerRadius = 2.dp,
        modifier = modifier.height(48.dp),
        contentDescription = contentDescription,
    )
}

data class DonutSegment(val value: Float, val color: Color)

/** Ring chart starting at 12 o'clock; an all-zero input draws only the track. */
@Composable
fun DonutChart(
    segments: List<DonutSegment>,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 22.dp,
    gapDegrees: Float = 2f,
    contentDescription: String? = null,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val animatedSweep by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 600),
        label = "donutSweep",
    )
    Canvas(modifier = modifier.chartSemantics(contentDescription)) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Butt)
        val inset = strokeWidth.toPx() / 2f
        val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
        val topLeft = Offset(inset, inset)
        val total = segments.sumOf { it.value.toDouble() }.toFloat()
        if (total <= 0f) {
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            return@Canvas
        }
        // Gaps only make sense between two or more visible slices.
        val drawn = segments.filter { it.value > 0f }
        val gap = if (drawn.size > 1) gapDegrees else 0f
        val available = 360f - gap * drawn.size
        var startAngle = -90f
        drawn.forEach { segment ->
            val sweep = (segment.value / total) * available * animatedSweep
            drawArc(
                color = segment.color,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            startAngle += sweep + gap
        }
    }
}

/** Sparkline through evenly spaced points; constant input renders a flat midline. */
@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = null,
) {
    Canvas(modifier = modifier.chartSemantics(contentDescription)) {
        if (values.isEmpty()) return@Canvas
        val max = values.max()
        val min = values.min()
        val span = (max - min).takeIf { it > 0f }
        val strokeWidth = 2.dp.toPx()
        // Inset vertically so the stroke cap never clips at the extremes.
        val drawableHeight = size.height - strokeWidth * 2f
        fun pointAt(index: Int): Offset {
            val x = if (values.size == 1) {
                size.width / 2f
            } else {
                size.width * index / (values.size - 1f)
            }
            val normalized = span?.let { (values[index] - min) / it } ?: 0.5f
            return Offset(x, strokeWidth + drawableHeight * (1f - normalized))
        }
        val path = Path()
        values.indices.forEach { index ->
            val point = pointAt(index)
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
        drawCircle(
            color = lineColor,
            radius = strokeWidth * 1.5f,
            center = pointAt(values.lastIndex),
        )
    }
}
