package com.example.groceriesapp.data.recipes

import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeRecencyTest {

    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 8, 21, 9, 0).toInstant(zone).toEpochMilli()

    private fun daysAgo(days: Long, hour: Int = 12): Long =
        LocalDateTime.of(2026, 8, 21, hour, 0).minusDays(days).toInstant(zone).toEpochMilli()

    private fun format(then: Long) = formatRelativeDays(then, now, zone)

    @Test
    fun `same calendar day is today even when later in the day`() {
        assertEquals("Today", format(daysAgo(0, hour = 23)))
        assertEquals("Today", format(daysAgo(0, hour = 1)))
    }

    @Test
    fun `counts calendar days not 24 hour spans`() {
        // 23:00 last night is only ten hours before 09:00 today, but it was yesterday.
        assertEquals("Yesterday", format(daysAgo(1, hour = 23)))
    }

    @Test
    fun `days weeks months and years`() {
        assertEquals("2 days ago", format(daysAgo(2)))
        assertEquals("6 days ago", format(daysAgo(6)))
        assertEquals("1 week ago", format(daysAgo(7)))
        assertEquals("1 week ago", format(daysAgo(13)))
        assertEquals("2 weeks ago", format(daysAgo(14)))
        assertEquals("3 weeks ago", format(daysAgo(27)))
        assertEquals("1 month ago", format(daysAgo(30)))
        assertEquals("2 months ago", format(daysAgo(61)))
        assertEquals("11 months ago", format(daysAgo(340)))
        assertEquals("Last year", format(daysAgo(400)))
        assertEquals("2 years ago", format(daysAgo(731)))
    }
}
