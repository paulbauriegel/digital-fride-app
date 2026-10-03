package com.example.groceriesapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Read-only five-star display for a recipe's average rating, rounded to the nearest half star.
 * Renders nothing when [rating] is null so list rows stay compact for unrated recipes.
 */
@Composable
fun StarRating(
    rating: Double?,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    if (rating == null) return
    val halves = (rating.coerceIn(0.0, 5.0) * 2).roundToInt()
    val description = "Rated %.1f out of 5".format(rating)
    Row(
        modifier = modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(5) { index ->
            val filledHalves = (halves - index * 2).coerceIn(0, 2)
            Icon(
                imageVector = when (filledHalves) {
                    2 -> Icons.Default.Star
                    1 -> Icons.AutoMirrored.Filled.StarHalf
                    else -> Icons.Default.StarBorder
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size),
            )
        }
    }
}

/** Tappable five-star input; tapping the current value again clears it. */
@Composable
fun StarRatingInput(
    value: Int?,
    onChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        for (star in 1..5) {
            val filled = value != null && star <= value
            IconButton(onClick = { onChange(if (value == star) null else star) }) {
                Icon(
                    imageVector = if (filled) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "$star ${if (star == 1) "star" else "stars"}",
                    tint = if (filled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(size),
                )
            }
        }
    }
}
