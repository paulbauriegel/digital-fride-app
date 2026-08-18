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

/**
 * Circular remaining-amount indicator for inventory rows: a full track circle with a progress arc
 * for how much of the item is left. A nearly-finished staple tints toward error so it stands out
 * while scanning the list. Renders nothing when the amount isn't tracked — most rows never are,
 * and a ring on every one of them would say nothing.
 *
 * [label] is what TalkBack reads. The ring is the only carrier of "how much is left" on a row, so
 * it has to be spoken rather than left as a decorative Canvas; callers pass the real amount
 * ("650 ml left") rather than a percentage wherever they know it.
 */
@Composable
fun RemainingAmountRing(
    fraction: Float?,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    if (fraction == null) return
    val clamped = fraction.coerceIn(0f, 1f)
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val arcColor = if (clamped <= LOW_FRACTION) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
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
            sweepAngle = 360f * clamped,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
    }
}

/** Where the arc turns to the error colour — the old LOW bucket's quarter-full boundary. */
private const val LOW_FRACTION = 0.25f
