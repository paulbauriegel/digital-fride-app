package com.example.groceriesapp.ui

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    private fun epochOf(dateTime: LocalDateTime): Long =
        dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val scannedAt = epochOf(LocalDateTime.of(2026, 8, 8, 21, 4))

    @Test
    fun receiptWithARealTimeShowsIt() {
        val purchasedAt = epochOf(LocalDateTime.of(2026, 8, 8, 18, 54, 44))

        assertEquals("Aug 8, 18:54", formatReceiptTimestamp(purchasedAt, scannedAt))
    }

    @Test
    fun dateOnlyReceiptShowsNoTimeInsteadOfMidnight() {
        // BIO COMPANY and OCR'd receipts carry a bare date, which lands on local midnight.
        val purchasedAt = epochOf(LocalDate.of(2026, 8, 1).atStartOfDay())

        assertEquals("Aug 1", formatReceiptTimestamp(purchasedAt, scannedAt))
    }

    @Test
    fun receiptWithoutADateFallsBackToTheScanTimeIncludingItsTime() {
        assertEquals("Aug 8, 21:04", formatReceiptTimestamp(null, scannedAt))
    }

    @Test
    fun volumesBelowALitreReadInMillilitres() {
        assertEquals("650 ml", formatAmount(0.65, "l"))
        assertEquals("750 ml", formatAmount(0.75, "l"))
        assertEquals("350 ml", formatAmount(350.0, "ml"))
    }

    @Test
    fun volumesAtOrAboveALitrePromoteToLitres() {
        assertEquals("1 L", formatAmount(1.0, "l"))
        assertEquals("1 L", formatAmount(1000.0, "ml"))
        assertEquals("1.5 L", formatAmount(1500.0, "ml"))
    }

    @Test
    fun massesFollowTheSameGramKilogramSplit() {
        assertEquals("350 g", formatAmount(350.0, "g"))
        assertEquals("999 g", formatAmount(999.0, "g"))
        assertEquals("1.2 kg", formatAmount(1.2, "kg"))
        assertEquals("2.5 kg", formatAmount(2500.0, "g"))
    }

    @Test
    fun countsAndUnrecognisedUnitsAreLeftAlone() {
        assertEquals("3 pc", formatAmount(3.0, "pc"))
        // Nothing sensible to convert "Becher" or "Bund" into, so don't guess.
        assertEquals("2 Becher", formatAmount(2.0, "Becher"))
        assertEquals("1", formatAmount(1.0, null))
    }

    @Test
    fun amountsNeverShowATrailingZeroDecimal() {
        assertEquals("500 g", formatAmount(0.5, "kg"))
        assertEquals("2 L", formatAmount(2.0, "l"))
    }

    @Test
    fun tickLabelsRunFullToEmpty() {
        assertEquals(
            listOf("1 L", "750 ml", "500 ml", "250 ml", "0 ml"),
            amountTickLabels(1.0, "l"),
        )
        assertEquals(
            listOf("2.5 kg", "1.9 kg", "1.3 kg", "625 g", "0 g"),
            amountTickLabels(2.5, "kg"),
        )
    }

    @Test
    fun aPickedDateKeepsItsCalendarDayInTheLocalZone() {
        // The Material picker hands back UTC midnight. Berlin is UTC+1/+2, so passing that value
        // through unconverted would render as the previous day.
        val pickedUtcMidnight = LocalDate.of(2026, 5, 18)
            .atStartOfDay(ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli()

        val stored = datePickerMillisToLocal(pickedUtcMidnight)

        assertEquals(
            LocalDate.of(2026, 5, 18),
            java.time.Instant.ofEpochMilli(stored).atZone(ZoneId.systemDefault()).toLocalDate(),
        )
    }

    @Test
    fun aStoredDateRoundTripsBackThroughThePicker() {
        val pickedUtcMidnight = LocalDate.of(2026, 8, 12)
            .atStartOfDay(ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli()

        assertEquals(
            pickedUtcMidnight,
            localMillisToDatePicker(datePickerMillisToLocal(pickedUtcMidnight)),
        )
    }

    @Test
    fun aPickedDateSurvivesADaylightSavingBoundary() {
        // Noon anchoring is what keeps a spring-forward from rolling the date back a day.
        listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25)).forEach { date ->
            val picked = date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

            assertEquals(picked, localMillisToDatePicker(datePickerMillisToLocal(picked)))
        }
    }
}
