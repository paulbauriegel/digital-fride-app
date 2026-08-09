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
}
