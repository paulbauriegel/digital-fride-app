package com.example.groceriesapp.vision.parsing

import com.example.groceriesapp.data.taxonomy.FoodConceptCatalog
import com.example.groceriesapp.data.taxonomy.GroceryCategory
import java.util.Locale

data class ReweDiscount(
    val description: String,
    val amountCents: Long,
)

data class ReweLineItem(
    val name: String,
    val lineTotalCents: Long,
    val taxFlag: String,
    val isDeposit: Boolean,
    val quantity: Double,
    val unit: String,
    val pricePerUnitCents: Long?,
    val discounts: List<ReweDiscount>,
) {
    /** Discounts are already negative on the receipt, so this sums to what was actually paid. */
    val finalPriceCents: Long
        get() = lineTotalCents + discounts.sumOf(ReweDiscount::amountCents)
}

/**
 * Parses the layout-preserved text of a REWE eBon PDF. Unlike the OCR path these receipts are
 * born-digital and column-aligned: an item is a flush-left line, and indented lines that follow it
 * carry its quantity breakdown or discounts.
 */
class ReweReceiptParser {

    fun parse(text: String): List<ReweLineItem> {
        val items = mutableListOf<MutableItem>()
        var started = false

        for (line in text.lineSequence()) {
            val trimmed = line.trim()

            if (!started) {
                if (trimmed.contains(HEADER_TERMINATOR)) started = true
                continue
            }
            if (trimmed.startsWith("-------") || trimmed.startsWith("=======")) break
            if (trimmed.isEmpty() || trimmed == "EUR") continue

            if (line.first().isWhitespace()) {
                val current = items.lastOrNull() ?: continue
                val quantityMatch = QUANTITY_LINE.find(line)
                if (quantityMatch != null) {
                    current.quantity = quantityMatch.groupValues[1].toGermanDouble()
                        ?: current.quantity
                    current.unit = quantityMatch.groupValues[2]
                    current.pricePerUnitCents = quantityMatch.groupValues[3].toGermanCents()
                    continue
                }
                val discountMatch = DISCOUNT_LINE.matchEntire(line) ?: continue
                val amount = discountMatch.groupValues[2].toGermanCents() ?: continue
                current.discounts += ReweDiscount(
                    description = discountMatch.groupValues[1].trim(),
                    amountCents = amount,
                )
                continue
            }

            val match = ITEM_LINE.matchEntire(trimmed) ?: continue
            val name = match.groupValues[1].trim()
            val cents = match.groupValues[2].toGermanCents() ?: continue
            if (name.isEmpty()) continue

            items += MutableItem(
                name = name,
                lineTotalCents = cents,
                taxFlag = match.groupValues[3],
                isDeposit = match.groupValues[4].isNotBlank() ||
                    name.uppercase(Locale.GERMANY).contains("PFAND"),
            )
        }

        return items.map(MutableItem::toLineItem)
    }

    private class MutableItem(
        val name: String,
        val lineTotalCents: Long,
        val taxFlag: String,
        val isDeposit: Boolean,
        var quantity: Double = 1.0,
        var unit: String = "Stk",
        var pricePerUnitCents: Long? = null,
        val discounts: MutableList<ReweDiscount> = mutableListOf(),
    ) {
        fun toLineItem() = ReweLineItem(
            name = name,
            lineTotalCents = lineTotalCents,
            taxFlag = taxFlag,
            isDeposit = isDeposit,
            quantity = quantity,
            unit = unit,
            pricePerUnitCents = pricePerUnitCents,
            discounts = discounts.toList(),
        )
    }

    private companion object {
        const val HEADER_TERMINATOR = "UID Nr.:"
        val ITEM_LINE = Regex("^(.*?)\\s+(-?\\d+,\\d{2})\\s+([AB])(\\s+\\*)?$")
        val DISCOUNT_LINE = Regex("^\\s+(.*?)\\s+(-?\\d+,\\d{2})\\s+([AB])(\\s+\\*)?$")
        val QUANTITY_LINE = Regex("^\\s+(\\d+(?:,\\d+)?)\\s+(Stk|kg)\\s+x\\s+(\\d+,\\d{2})")

        fun String.toGermanDouble(): Double? = replace(',', '.').toDoubleOrNull()

        fun String.toGermanCents(): Long? = toGermanDouble()?.let { Math.round(it * 100) }
    }
}

/** Canonical category for a REWE line, mirroring how the OCR parser tags items. */
fun ReweLineItem.categoryName(): String =
    (FoodConceptCatalog.match(name)?.category ?: GroceryCategory.OTHER).name

fun ReweLineItem.foodConceptId(): String? = FoodConceptCatalog.match(name)?.id

/** REWE writes piece counts as "Stk"; the rest of the app uses "pc". */
fun ReweLineItem.normalizedUnit(): String = when (unit.lowercase(Locale.ROOT)) {
    "stk" -> "pc"
    else -> unit.lowercase(Locale.ROOT)
}
