package com.example.groceriesapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.FillLevel

/**
 * Circular remaining-amount indicator for inventory rows: a full track circle with a progress
 * arc for how much of the item is left. LOW and EMPTY tint toward error so a nearly-finished
 * staple stands out while scanning the list. Renders nothing when the amount isn't tracked.
 */
@Composable
fun FillLevelRing(
    fillLevel: FillLevel?,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    if (fillLevel == null) return
    val fraction = when (fillLevel) {
        FillLevel.FULL -> 1f
        FillLevel.HALF -> 0.5f
        FillLevel.LOW -> 0.25f
        FillLevel.EMPTY -> 0.05f
    }
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val arcColor = when (fillLevel) {
        FillLevel.FULL, FillLevel.HALF -> MaterialTheme.colorScheme.primary
        FillLevel.LOW, FillLevel.EMPTY -> MaterialTheme.colorScheme.error
    }
    // The ring is the only carrier of "how much is left" on an inventory row, so it has to be
    // readable by TalkBack rather than left as a decorative Canvas.
    val label = when (fillLevel) {
        FillLevel.FULL -> "Full"
        FillLevel.HALF -> "Half left"
        FillLevel.LOW -> "Running low"
        FillLevel.EMPTY -> "Almost empty"
    }
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = label },
    ) {
        val strokeWidth = 3.dp.toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        val inset = strokeWidth / 2f
        val arcSize = Size(this.size.width - inset * 2f, this.size.height - inset * 2f)
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
        drawArc(
            color = arcColor,
            startAngle = -90f,
            sweepAngle = 360f * fraction,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
    }
}
