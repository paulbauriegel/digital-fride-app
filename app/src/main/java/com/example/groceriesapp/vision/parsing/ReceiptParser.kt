package com.example.groceriesapp.vision.parsing

import com.example.groceriesapp.data.taxonomy.FoodConceptCatalog
import com.example.groceriesapp.data.taxonomy.GroceryCategory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.Locale

data class ParsedLineItem(
    val name: String,
    val quantity: Double = 1.0,
    /** Null when the receipt line doesn't say — a bare line could be a 500 g pack, not pieces. */
    val unit: String? = null,
    val lineTotalCents: Long,
    /** Canonical [GroceryCategory] name, e.g. "DAIRY". */
    val category: String,
    val foodConceptId: String?,
    val needsReview: Boolean,
)

data class ParsedReceipt(
    val merchant: String?,
    val purchasedAtEpochMillis: Long?,
    val totalCents: Long?,
    val currencyCode: String,
    val items: List<ParsedLineItem>,
)

/**
 * Conservative, local receipt parsing. The image and raw OCR stay authoritative; this parser only
 * proposes editable fields and deliberately marks ambiguous rows for review.
 */
class ReceiptParser(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun parse(rawText: String): ParsedReceipt {
        val lines = rawText
            .lineSequence()
            .map(::normalizeLine)
            .filter(String::isNotBlank)
            .toList()

        val merchant = findMerchant(lines)
        val total = findTotal(lines)
        val purchasedAt = findDate(lines)
        val items = lines.mapNotNull(::parseItem)

        return ParsedReceipt(
            merchant = merchant,
            purchasedAtEpochMillis = purchasedAt,
            totalCents = total,
            currencyCode = "EUR",
            items = items,
        )
    }

    private fun findMerchant(lines: List<String>): String? {
        val excluded = HEADER_NOISE
        return lines.take(10).firstOrNull { line ->
            val letters = line.count(Char::isLetter)
            letters >= 3 &&
                line.length <= 48 &&
                excluded.none { line.contains(it, ignoreCase = true) } &&
                !DATE_ANYWHERE.containsMatchIn(line) &&
                !PRICE_AT_END.containsMatchIn(line)
        }?.trim()?.let(::titleCaseMerchant)
    }

    private fun findTotal(lines: List<String>): Long? {
        lines.asReversed().forEach { line ->
            if (TOTAL_LABEL.containsMatchIn(line)) {
                PRICE_ANYWHERE.findAll(line).lastOrNull()?.value?.toCentsOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun findDate(lines: List<String>): Long? {
        for (line in lines) {
            val match = DATE_ANYWHERE.find(line) ?: continue
            val value = match.value
            for (formatter in DATE_FORMATTERS) {
                try {
                    val parsed = LocalDate.parse(value, formatter)
                    return parsed.atStartOfDay(zoneId).toInstant().toEpochMilli()
                } catch (_: DateTimeParseException) {
                    // Try the next accepted receipt date format.
                }
            }
        }
        return null
    }

    private fun parseItem(line: String): ParsedLineItem? {
        if (NON_ITEM_LABELS.any { label ->
                if (label.length <= 3) {
                    Regex("\\b${Regex.escape(label)}\\b", RegexOption.IGNORE_CASE)
                        .containsMatchIn(line)
                } else {
                    line.contains(label, ignoreCase = true)
                }
            }
        ) {
            return null
        }
        val match = ITEM_LINE.matchEntire(line) ?: return null
        val rawName = match.groupValues[1]
            .trim(' ', '.', '-', ':')
            .replace(Regex("\\s{2,}"), " ")
        val cents = match.groupValues[2].toCentsOrNull() ?: return null
        if (rawName.length < 2 || rawName.count(Char::isLetter) < 2 || cents <= 0) return null

        val quantityMatch = QUANTITY.find(rawName)
        val leadingCountMatch = LEADING_COUNT.find(rawName)
        val quantity = quantityMatch?.groupValues?.get(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?: leadingCountMatch?.groupValues?.get(1)?.toDoubleOrNull()
            ?: 1.0
        // "pc" only when the line literally counts pieces ("2x Joghurt"); a bare line's unit is
        // unknown, not pieces.
        val unit = quantityMatch?.groupValues?.get(2)
            ?.lowercase(Locale.ROOT)
            ?.let(::normalizeUnit)
            ?: if (leadingCountMatch != null) "pc" else null
        val cleanedName = rawName
            .replace(QUANTITY, "")
            .replace(LEADING_COUNT, "")
            .trim(' ', '-', 'x', 'X')
            .ifBlank { rawName }

        return finishParsedLineItem(
            name = cleanedName,
            quantity = quantity,
            unit = unit,
            lineTotalCents = cents,
            reviewSourceText = rawName,
        )
    }

    private fun normalizeLine(value: String): String = value
        .replace('\u00A0', ' ')
        .replace('€', ' ')
        .replace(Regex("[|_]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun titleCaseMerchant(value: String): String {
        if (value.length <= 12 && value == value.uppercase(Locale.GERMANY)) return value
        return value.lowercase(Locale.GERMANY)
            .split(' ')
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.titlecase(Locale.GERMANY) }
            }
    }

    private fun normalizeUnit(unit: String): String = when (unit) {
        "stk", "st", "x" -> "pc"
        else -> unit
    }

    internal companion object {
        val ITEM_LINE = Regex("^(.+?)[\\s:]+(-?\\d{1,4}[.,]\\d{2})\\s*[A-Z]?$")
        val PRICE_AT_END = Regex("-?\\d{1,4}[.,]\\d{2}\\s*[A-Z]?$")
        val PRICE_ANYWHERE = Regex("-?\\d{1,4}(?:[.]\\d{3})*[.,]\\d{2}")
        val TOTAL_LABEL = Regex(
            "(?:^|\\s)(gesamt|summe|total|zu zahlen|endsumme|betrag)(?:\\s|:|$)",
            RegexOption.IGNORE_CASE,
        )
        val DATE_ANYWHERE = Regex("\\b\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}\\b")
        val DATE_FORMATTERS = listOf(
            DateTimeFormatter.ofPattern("d.M.uuuu"),
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("d-M-uuuu"),
            DateTimeFormatterBuilder()
                .appendPattern("d.M.")
                .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
                .toFormatter(),
            DateTimeFormatterBuilder()
                .appendPattern("d/M/")
                .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
                .toFormatter(),
            DateTimeFormatterBuilder()
                .appendPattern("d-M-")
                .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
                .toFormatter(),
        )
        val QUANTITY = Regex(
            "\\b(\\d+(?:[.,]\\d+)?)\\s*(kg|g|l|ml|stk|st)\\b",
            RegexOption.IGNORE_CASE,
        )
        val LEADING_COUNT = Regex("^(\\d+)\\s*[xX]\\s*")
        val OCR_AMBIGUITY = Regex("[^\\p{L}\\p{N} .,&+\\-/]")
        val HEADER_NOISE = listOf(
            "kassenbon",
            "beleg",
            "filiale",
            "straße",
            "strasse",
            "datum",
            "uhrzeit",
            "tel.",
        )
        val NON_ITEM_LABELS = listOf(
            "gesamt",
            "summe",
            "total",
            "zu zahlen",
            "gegeben",
            "rückgeld",
            "ruckgeld",
            "bar",
            "karte",
            "mwst",
            "ust",
            "steuer",
            "netto",
            "brutto",
            "pfand",
            "rabatt",
            "coupon",
            "ersparnis",
            "kassenbon",
            "transaktion",
        )
    }
}

/**
 * Shared tail of line-item construction so every extraction engine (rules, Granite, OpenAI)
 * assigns categories, concepts, and review flags identically — engines never invent categories.
 */
internal fun finishParsedLineItem(
    name: String,
    quantity: Double,
    unit: String?,
    lineTotalCents: Long,
    reviewSourceText: String = name,
): ParsedLineItem {
    val concept = FoodConceptCatalog.match(name)
    return ParsedLineItem(
        name = name.lowercase(Locale.GERMANY)
            .replaceFirstChar { it.titlecase(Locale.GERMANY) },
        quantity = quantity,
        unit = unit,
        lineTotalCents = lineTotalCents,
        category = (concept?.category ?: GroceryCategory.OTHER).name,
        foodConceptId = concept?.id,
        needsReview = reviewSourceText.length < 4 ||
            ReceiptParser.OCR_AMBIGUITY.containsMatchIn(reviewSourceText),
    )
}

/** Parses a German or English decimal money string ("2,49", "1.234,56") into cents. */
internal fun String.toCentsOrNull(): Long? = runCatching {
    val compact = replace(" ", "")
    val decimal = when {
        ',' in compact && '.' in compact -> {
            if (compact.lastIndexOf(',') > compact.lastIndexOf('.')) {
                compact.replace(".", "").replace(',', '.')
            } else {
                compact.replace(",", "")
            }
        }
        ',' in compact -> compact.replace(',', '.')
        else -> compact
    }
    decimal
        .toBigDecimal()
        .multiply(BigDecimal(100))
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()
}.getOrNull()
