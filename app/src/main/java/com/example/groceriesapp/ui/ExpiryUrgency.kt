package com.example.groceriesapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.example.groceriesapp.data.InventoryItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * How urgently an item should be used, as whole days until the earliest edge of its expiry
 * window. Complements [formatExpiry], which renders prose: this carries the numeric delta so
 * screens can rank, bucket, and color items consistently.
 */
data class ExpiryUrgency(
    val daysUntil: Long,
    val label: String,
)

fun expiryUrgency(
    item: InventoryItem,
    today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
): ExpiryUrgency? {
    val expiresAt = item.expiresAtEpochMillis ?: return null
    val expiryDate = Instant.ofEpochMilli(expiresAt).atZone(ZoneId.systemDefault()).toLocalDate()
    val days = ChronoUnit.DAYS.between(today, expiryDate)
    val label = when {
        days < 0 -> "Expired"
        days == 0L -> "Use today"
        days == 1L -> "Use by tomorrow"
        else -> "Use within $days days"
    }
    return ExpiryUrgency(daysUntil = days, label = label)
}

/**
 * Compact urgency wording for dense rows ("Today", "Tomorrow", "3 days left") where [label]'s
 * imperative phrasing ("Use by tomorrow") would crowd the layout.
 */
fun ExpiryUrgency.shortLabel(): String = when {
    daysUntil < 0 -> "Expired"
    daysUntil == 0L -> "Today"
    daysUntil == 1L -> "Tomorrow"
    else -> "$daysUntil days left"
}

/**
 * Colour for urgency text. Lives here rather than in each screen so Home and Inventory can't drift
 * apart on where "urgent" stops and "soon" begins — they previously carried separate copies of
 * this ramp.
 */
@Composable
@ReadOnlyComposable
fun ExpiryUrgency.color(): Color = when {
    daysUntil <= 1 -> MaterialTheme.colorScheme.error
    daysUntil <= 3 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Expiring within [withinDays] days from today. Already-expired items count too — they need
 * attention more than anything else, not less.
 */
fun expiresSoon(
    item: InventoryItem,
    withinDays: Long = 3,
    today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
): Boolean {
    val urgency = expiryUrgency(item, today) ?: return false
    return urgency.daysUntil <= withinDays
}
