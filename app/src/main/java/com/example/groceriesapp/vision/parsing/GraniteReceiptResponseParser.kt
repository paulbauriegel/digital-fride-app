package com.example.groceriesapp.vision.parsing

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Parses the line-labeled answer format the Granite prompt requests. Deliberately tolerant: a
 * 350M model interleaves chatter, drops fields, and echoes the prompt, so every line that isn't
 * a well-formed labeled line is ignored rather than treated as an error.
 */
class GraniteReceiptResponseParser {
    fun parse(responseText: String, zoneId: ZoneId): ParsedReceipt {
        var merchant: String? = null
        var dateEpochMillis: Long? = null
        var totalCents: Long? = null
        val items = mutableListOf<ParsedLineItem>()

        responseText.lineSequence().forEach { rawLine ->
            val match = LABELED_LINE.matchEntire(rawLine.trim()) ?: return@forEach
            val label = match.groupValues[1].uppercase(Locale.ROOT)
            val value = match.groupValues[2].trim()
            when (label) {
                "MERCHANT" -> if (merchant == null) merchant = valueOrNull(value)
                "DATE" -> if (dateEpochMillis == null) {
                    dateEpochMillis = valueOrNull(value)?.let { parseDate(it, zoneId) }
                }
                "TOTAL" -> if (totalCents == null) {
                    totalCents = valueOrNull(value)?.toCentsOrNull()
                }
                "ITEM" -> parseItem(value)?.let(items::add)
            }
        }

        return ParsedReceipt(
            merchant = merchant,
            purchasedAtEpochMillis = dateEpochMillis,
            totalCents = totalCents,
            currencyCode = "EUR",
            items = items,
        )
    }

    private fun parseItem(value: String): ParsedLineItem? {
        val fields = value.split('|').map(String::trim)
        val (name, quantityField, unitField, priceField) = when (fields.size) {
            4 -> listOf(fields[0], fields[1], fields[2], fields[3])
            3 -> listOf(fields[0], fields[1], null, fields[2])
            2 -> listOf(fields[0], null, null, fields[1])
            else -> return null
        }
        val cleanName = name?.let(::valueOrNull) ?: return null
        if (cleanName.count(Char::isLetter) < 2) return null
        val cents = priceField?.let(::valueOrNull)?.toCentsOrNull() ?: return null
        if (cents <= 0) return null
        val quantity = quantityField?.let(::valueOrNull)
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf { it > 0 }
            ?: 1.0
        val unit = unitField?.let(::valueOrNull)?.let(::normalizeUnit)
        return finishParsedLineItem(
            name = cleanName,
            quantity = quantity,
            unit = unit,
            lineTotalCents = cents,
        )
    }

    private fun normalizeUnit(value: String): String? {
        val unit = when (val lower = value.lowercase(Locale.ROOT)) {
            "stk", "st", "x", "stück", "stuck", "pcs", "piece", "pieces" -> "pc"
            else -> lower
        }
        return unit.takeIf { it in ALLOWED_UNITS }
    }

    private fun parseDate(value: String, zoneId: ZoneId): Long? {
        try {
            return LocalDate.parse(value).atStartOfDay(zoneId).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            // Not ISO; try the receipt date formats below.
        }
        for (formatter in ReceiptParser.DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value, formatter)
                    .atStartOfDay(zoneId).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                // Try the next accepted receipt date format.
            }
        }
        return null
    }

    private fun valueOrNull(value: String): String? =
        value.trim().takeUnless { it.isEmpty() || it.lowercase(Locale.ROOT) in PLACEHOLDERS }

    private companion object {
        val LABELED_LINE = Regex(
            "^(MERCHANT|DATE|TOTAL|ITEM)\\s*[:\\-]\\s*(.*)$",
            RegexOption.IGNORE_CASE,
        )
        val ALLOWED_UNITS = setOf("kg", "g", "l", "ml", "pc")
        val PLACEHOLDERS = setOf("-", "–", "null", "none", "unknown", "n/a")
    }
}
