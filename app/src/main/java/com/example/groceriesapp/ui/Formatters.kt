package com.example.groceriesapp.ui

import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.recipes.UnitKind
import com.example.groceriesapp.data.recipes.UnitNormalizer
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
 * mistaken for one printed on the packaging, which is shown as an exact date instead. A date the
 * user set by hand reads the same way: it is exact, and hedging it as a range would misrepresent
 * what they told the app.
 */
fun formatExpiry(item: InventoryItem): String {
    val start = item.expiresAtEpochMillis ?: return "Expiry unknown"
    if (item.expiryEstimateSource in EXACT_EXPIRY_SOURCES) {
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

/** Sources that name one specific day rather than a window, so they never render as a range. */
private val EXACT_EXPIRY_SOURCES =
    setOf(ExpirySource.PRINTED_LABEL.name, ExpirySource.USER.name)

private fun dayWord(days: Long): String = if (days == 1L) "day" else "days"

fun formatQuantity(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)

/**
 * An amount in whichever unit reads most naturally: "650 ml" rather than "0.65 l", "1.2 kg" rather
 * than "1200 g". Storage keeps the item's own unit; this is purely how it is shown.
 *
 * Mass and volume promote to kg/L at 1000 of their base unit and otherwise stay in g/ml, which is
 * how a package label would put it. Counts and units the app doesn't recognise are left exactly as
 * they are — inventing a conversion for "Becher" or "Bund" would be a guess, and a bare receipt
 * line's unit is unknown rather than assumed, the same stance [com.example.groceriesapp.data
 * .isCountable] takes.
 */
fun formatAmount(value: Double, unit: String?): String {
    // The kind comes from the unit alone rather than from a successful conversion: an empty
    // container is a real reading on this scale, and UnitNormalizer rejects non-positive amounts,
    // so asking it first would print a zero litre as "0 l" instead of "0 ml".
    val (subUnit, baseUnit) = when (UnitNormalizer.kindOf(unit)) {
        UnitKind.MASS -> "g" to "kg"
        UnitKind.VOLUME -> "ml" to "L"
        UnitKind.COUNT, UnitKind.UNKNOWN -> return formatUnknownUnitAmount(value, unit)
    }
    val baseValue = UnitNormalizer.normalize(value, unit)?.value ?: 0.0
    if (baseValue < 1_000.0) return "${trimDecimals(baseValue)} $subUnit"
    return "${trimDecimals(baseValue / 1_000.0)} $baseUnit"
}

/**
 * Evenly spaced scale labels for an amount gauge, running top (full) to bottom (empty). Even
 * spacing is what lets the gauge lay them out against the fill band without per-label positioning.
 */
fun amountTickLabels(packageSize: Double, unit: String?, steps: Int = 4): List<String> =
    (steps downTo 0).map { step -> formatAmount(packageSize * step / steps, unit) }

private fun formatUnknownUnitAmount(value: Double, unit: String?): String {
    val suffix = unit?.trim()?.takeIf(String::isNotEmpty)?.let { " $it" }.orEmpty()
    return "${trimDecimals(value)}$suffix"
}

/** At most one decimal, and none at all on a whole number — "650", "1.2", not "650.0" or "1.20". */
private fun trimDecimals(value: Double): String {
    val rounded = Math.round(value * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else "%.1f".format(Locale.ROOT, rounded)
}

/**
 * Converts a Material `DatePickerState.selectedDateMillis` into an instant this app can store.
 *
 * The picker reports UTC midnight, while every other date here is a system-zone instant — expiry
 * windows are anchored in the local zone and rendered with [ZoneId.systemDefault]. Handing the
 * picker's value straight through would land a German user's choice an hour early and render it as
 * the day before.
 *
 * The result sits at local **noon**, not midnight, so a later daylight-saving shift can't roll the
 * date across a day boundary.
 */
fun datePickerMillisToLocal(utcMillis: Long): Long =
    Instant.ofEpochMilli(utcMillis)
        .atZone(ZoneId.of("UTC"))
        .toLocalDate()
        .atTime(LocalTime.NOON)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

/** The inverse of [datePickerMillisToLocal], for seeding the picker from a stored date. */
fun localMillisToDatePicker(epochMillis: Long): Long =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .atStartOfDay(ZoneId.of("UTC"))
        .toInstant()
        .toEpochMilli()

fun formatDateTime(epochMillis: Long): String {
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}
