package com.example.groceriesapp.data.recipes

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * "2 days ago"-style wording for cook history. Counts calendar days in [zoneId], not 24-hour
 * spans, so something cooked late last night reads as "Yesterday" this morning. Lives beside the
 * models rather than in `ui/Formatters.kt` so the pure library engine and its tests can use it.
 */
fun formatRelativeDays(
    thenEpochMillis: Long,
    nowEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val then = Instant.ofEpochMilli(thenEpochMillis).atZone(zoneId).toLocalDate()
    val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
    val days = ChronoUnit.DAYS.between(then, now)
    return when {
        days <= 0 -> "Today"
        days == 1L -> "Yesterday"
        days < 7 -> "$days days ago"
        days < 14 -> "1 week ago"
        days < 30 -> "${days / 7} weeks ago"
        days < 60 -> "1 month ago"
        days < 365 -> "${days / 30} months ago"
        days < 730 -> "Last year"
        else -> "${days / 365} years ago"
    }
}
