package com.example.groceriesapp.ui

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

fun formatCurrency(cents: Int?, currencyCode: String = "EUR"): String {
    if (cents == null) return "—"
    val formatter = NumberFormat.getCurrencyInstance(Locale.getDefault())
    runCatching { formatter.currency = Currency.getInstance(currencyCode) }
    return formatter.format(cents / 100.0)
}
fun formatDate(epochMillis: Long?): String {
    if (epochMillis == null) return "Date not recognized"
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(formatter)
}

fun formatDateTime(epochMillis: Long): String {
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}
