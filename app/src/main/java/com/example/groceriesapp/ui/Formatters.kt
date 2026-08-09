package com.example.groceriesapp.ui

import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.taxonomy.ExpirySource
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.Locale

fun formatCurrency(cents: Int?, currencyCode: String = "EUR"): String {
    if (cents == null) return "—"
    val symbol = runCatching { Currency.getInstance(currencyCode).getSymbol(Locale.GERMANY) }
        .getOrDefault(currencyCode)
    val amount = NumberFormat.getNumberInstance(Locale.GERMANY).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    return symbol + amount.format(cents / 100.0)
}

/**
 * Compact row timestamp, e.g. `May 26, 08:35` — or just `May 26` when the receipt carries no time
 * of day. BIO COMPANY's API and the OCR date parser both yield a bare date, which lands on local
 * midnight; printing `00:00` for those would invent a checkout time the app was never told.
 * A null [dateEpochMillis] falls back to [createdAtEpochMillis], whose time is the real scan or
 * import moment and is kept.
 */
fun formatReceiptTimestamp(dateEpochMillis: Long?, createdAtEpochMillis: Long): String {
    val zoned = Instant.ofEpochMilli(dateEpochMillis ?: createdAtEpochMillis)
        .atZone(ZoneId.systemDefault())
    val dateOnly = dateEpochMillis != null && zoned.toLocalTime() == LocalTime.MIDNIGHT
    return zoned.format(if (dateOnly) RECEIPT_DATE_FORMAT else RECEIPT_TIMESTAMP_FORMAT)
}

private val RECEIPT_TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH)

private val RECEIPT_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)

fun formatDate(epochMillis: Long?): String {
    if (epochMillis == null) return "Date not recognized"
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(formatter)
}

/**
 * Estimated windows are shown as a span ("Best used within 4–7 days") so a guessed date is never
 * mistaken for one printed on the packaging, which is shown as an exact date instead.
 */
fun formatExpiry(item: InventoryItem): String {
    val start = item.expiresAtEpochMillis ?: return "Expiry unknown"
    if (item.expiryEstimateSource == ExpirySource.PRINTED_LABEL.name) {
        return "Best before ${formatDate(start)}"
    }
    val end = item.expiryRangeEndEpochMillis
    if (end == null || item.expiryEstimateSource == null) return "Use by ${formatDate(start)}"

    val today = LocalDate.now(ZoneId.systemDefault())
    val zone = ZoneId.systemDefault()
    val startDays = ChronoUnit.DAYS.between(today, Instant.ofEpochMilli(start).atZone(zone).toLocalDate())
    val endDays = ChronoUnit.DAYS.between(today, Instant.ofEpochMilli(end).atZone(zone).toLocalDate())
    if (endDays < 0) return "Past estimate"
    if (startDays <= 0) return "Best used within $endDays ${dayWord(endDays)}"
    if (startDays == endDays) return "Best used within $startDays ${dayWord(startDays)}"
    return "Best used within $startDays–$endDays days"
}

private fun dayWord(days: Long): String = if (days == 1L) "day" else "days"

fun formatQuantity(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)

fun formatDateTime(epochMillis: Long): String {
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}
