package com.example.groceriesapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.taxonomy.ContainerType
import com.example.groceriesapp.ui.amountTickLabels
import com.example.groceriesapp.ui.theme.GroceriesTheme
import com.example.groceriesapp.ui.theme.spacing

/**
 * Shows how much of an item is left as a drawn container filling up, with a measurement scale down
 * its left edge.
 *
 * A number alone ("650 ml") says little about whether that is a lot or a little; a bar says how
 * full but not of what. Drawing the actual vessel carries both at a glance, which is the whole
 * point of the control.
 *
 * [tickLabels] run top to bottom and are assumed evenly spaced — they are laid out against the fill
 * band with `SpaceBetween` rather than positioned by offset, so the scale stays aligned with its
 * ticks at any font scale instead of drifting off them.
 */
@Composable
fun ContainerFillGauge(
    container: ContainerType,
    fillFraction: Float,
    tickLabels: List<String>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    height: Dp = 168.dp,
) {
    val outlineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val emptyColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val fillColor = MaterialTheme.colorScheme.primary
    val shape = rememberContainerShape(container)
    val fraction = fillFraction.coerceIn(0f, 1f)

    Row(
        modifier = modifier.height(height),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        // The labels span only the fill band, not the whole drawing — a bottle's neck and cap hold
        // nothing, so a scale measured against the full height would misread every value.
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(
                    top = height * shape.fillTop / DESIGN_HEIGHT,
                    bottom = height * (DESIGN_HEIGHT - shape.fillBottom) / DESIGN_HEIGHT,
                )
                // Spoken as part of the gauge's own description; five orphan numbers read aloud
                // in sequence would tell a TalkBack user nothing.
                .clearAndSetSemantics {},
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            tickLabels.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxHeight()
                .width(height * DESIGN_WIDTH / DESIGN_HEIGHT)
                .semantics { this.contentDescription = contentDescription },
        ) {
            drawContainer(shape, fraction, outlineColor, emptyColor, fillColor)
        }
    }
}

/**
 * One vessel outline plus the vertical band inside it that actually holds anything.
 *
 * Keeping the band separate from the silhouette is what lets a single clipped rectangle fill any
 * shape correctly: the fill follows a bottle's taper or a sack's bulge for free, and a half-full
 * bottle doesn't read as full just because its neck is drawn.
 */
private data class ContainerShape(
    val path: Path,
    val details: Path,
    val fillTop: Float,
    val fillBottom: Float,
)

private fun DrawScope.drawContainer(
    shape: ContainerShape,
    fraction: Float,
    outlineColor: Color,
    emptyColor: Color,
    fillColor: Color,
) {
    val scale = Matrix().apply {
        scale(size.width / DESIGN_WIDTH, size.height / DESIGN_HEIGHT)
    }
    val outline = Path().apply {
        addPath(shape.path)
        transform(scale)
    }
    val details = Path().apply {
        addPath(shape.details)
        transform(scale)
    }
    val scaleY = size.height / DESIGN_HEIGHT
    val fillTop = shape.fillTop * scaleY
    val fillBottom = shape.fillBottom * scaleY
    val band = fillBottom - fillTop

    drawPath(outline, emptyColor)
    clipPath(outline) {
        drawRect(
            color = fillColor,
            topLeft = Offset(0f, fillBottom - band * fraction),
            size = Size(size.width, band * fraction),
        )
    }
    val stroke = Stroke(width = 2.dp.toPx())
    drawPath(outline, outlineColor, style = stroke)
    drawPath(details, outlineColor, style = stroke)
}

@Composable
private fun rememberContainerShape(container: ContainerType): ContainerShape =
    remember(container) { containerShape(container) }

/**
 * The four silhouettes, authored once in a fixed [DESIGN_WIDTH] × [DESIGN_HEIGHT] space and scaled
 * at draw time. Written as coordinates rather than arithmetic so the shapes can be read and nudged
 * like artwork, which is what they are.
 */
private fun containerShape(container: ContainerType): ContainerShape = when (container) {
    ContainerType.CARTON -> ContainerShape(
        path = Path().apply {
            moveTo(10f, 35f)
            lineTo(50f, 10f)
            lineTo(90f, 35f)
            lineTo(90f, 128f)
            quadraticTo(90f, 132f, 86f, 132f)
            lineTo(14f, 132f)
            quadraticTo(10f, 132f, 10f, 128f)
            close()
        },
        details = Path().apply {
            // The gable seam, and the folded spout corner.
            moveTo(10f, 35f)
            lineTo(90f, 35f)
            moveTo(50f, 10f)
            lineTo(50f, 35f)
        },
        fillTop = 35f,
        fillBottom = 132f,
    )

    ContainerType.BOTTLE -> ContainerShape(
        path = Path().apply {
            moveTo(40f, 14f)
            lineTo(60f, 14f)
            lineTo(60f, 30f)
            // Shoulders flaring out to the body.
            quadraticTo(82f, 42f, 82f, 62f)
            lineTo(82f, 126f)
            quadraticTo(82f, 132f, 76f, 132f)
            lineTo(24f, 132f)
            quadraticTo(18f, 132f, 18f, 126f)
            lineTo(18f, 62f)
            quadraticTo(18f, 42f, 40f, 30f)
            close()
        },
        details = Path().apply {
            // Cap band around the neck.
            moveTo(38f, 20f)
            lineTo(62f, 20f)
        },
        // Liquid sits below the shoulder, never up the neck.
        fillTop = 34f,
        fillBottom = 132f,
    )

    ContainerType.PACK -> ContainerShape(
        path = Path().apply {
            moveTo(14f, 32f)
            lineTo(86f, 32f)
            lineTo(86f, 126f)
            quadraticTo(86f, 132f, 80f, 132f)
            lineTo(20f, 132f)
            quadraticTo(14f, 132f, 14f, 126f)
            close()
        },
        details = Path().apply {
            // Crimped seal across the top, drawn as a zigzag.
            moveTo(14f, 32f)
            var x = 14f
            var down = true
            while (x < 86f) {
                val next = minOf(x + 9f, 86f)
                lineTo(next, if (down) 20f else 32f)
                down = !down
                x = next
            }
        },
        fillTop = 32f,
        fillBottom = 132f,
    )

    ContainerType.BULK -> ContainerShape(
        path = Path().apply {
            // Gathered neck opening out into a bulging sack.
            moveTo(42f, 34f)
            lineTo(58f, 34f)
            quadraticTo(62f, 44f, 70f, 52f)
            cubicTo(84f, 66f, 88f, 100f, 80f, 120f)
            quadraticTo(74f, 132f, 50f, 132f)
            quadraticTo(26f, 132f, 20f, 120f)
            cubicTo(12f, 100f, 16f, 66f, 30f, 52f)
            quadraticTo(38f, 44f, 42f, 34f)
            close()
        },
        details = Path().apply {
            // The tie just below the gathered mouth.
            moveTo(38f, 44f)
            lineTo(62f, 44f)
        },
        fillTop = 46f,
        fillBottom = 132f,
    )
}

private const val DESIGN_WIDTH = 100f
private const val DESIGN_HEIGHT = 140f

/**
 * The only previews in this project, and they earn their keep: four hand-drawn silhouettes at
 * several fill levels cannot be judged from source, and iterating on them through a full device
 * install would cost more than the drawings themselves.
 */
@Preview(widthDp = 420, heightDp = 220, showBackground = true)
@Composable
private fun ContainerFillGaugePreview() {
    GroceriesTheme(darkTheme = false) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ContainerType.entries.forEachIndexed { index, container ->
                ContainerFillGauge(
                    container = container,
                    fillFraction = listOf(0.65f, 0.75f, 0.7f, 0.48f)[index],
                    tickLabels = amountTickLabels(1.0, "l"),
                    contentDescription = container.displayName,
                )
            }
        }
    }
}

@Preview(widthDp = 420, heightDp = 220, showBackground = true)
@Composable
private fun ContainerFillGaugeLevelsPreview() {
    GroceriesTheme(darkTheme = true) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            listOf(0f, 0.25f, 0.5f, 1f).forEach { fraction ->
                ContainerFillGauge(
                    container = ContainerType.BOTTLE,
                    fillFraction = fraction,
                    tickLabels = amountTickLabels(1.0, "l"),
                    contentDescription = "${fraction * 100} percent",
                )
            }
        }
    }
}
