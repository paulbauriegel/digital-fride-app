package com.example.groceriesapp.data

import com.example.groceriesapp.data.network.EdekaFormattedReceipt
import com.example.groceriesapp.data.network.EdekaFormattedReceiptLine
import com.example.groceriesapp.data.network.EdekaReceiptLineType
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * EDEKA's digital receipt is a *rendered* receipt: `lineItems` is the printed line sequence rather
 * than a list of products, and `SEPARATOR` items mark where one rung-up position ends and the next
 * begins. A position spans one to three lines:
 *
 * ```
 * 2.000 x 0,29€                       <- multiplier line: price-less, describes the line below
 * Harry Dopp.Bröt.            0,58€   <- product line: right side is the position total
 * Coupon App Tafeltrauben    -0,95€   <- DISCOUNT line: applies to the position above
 * SEPARATOR
 * ```
 *
 * So the mapping has to walk blocks, not lines. Treating every line as its own product — as this
 * did before — invented items named "2.000 x 0,29€" with no price, priced the two-roll position as
 * one, and silently dropped every coupon.
 */
fun EdekaFormattedReceipt.toReceiptItems(): List<ReceiptItem> = positions()
    .mergeIdenticalPositions()
    .map { position ->
        ReceiptItem(
            name = position.name,
            quantity = position.quantity,
            unit = position.unit,
            priceCents = position.netCents,
        )
    }

/**
 * The printed receipt, one line per row, for the manual-review fallback. Multiplier and coupon
 * lines are kept verbatim — they are what a reader checks the parsed quantities against — while
 * `SEPARATOR` rows are dropped, because they carry no text and only ever printed as boundaries.
 */
fun EdekaFormattedReceipt.toRawText(): String = buildString {
    lines
        .filter { it.type != EdekaReceiptLineType.SEPARATOR }
        .forEach { line ->
            append(line.left)
            if (line.right.isNotBlank()) {
                if (line.left.isNotBlank()) append("  ")
                append(line.right)
            }
            appendLine()
        }
}.trimEnd()

/** One rung-up position: the product line plus the multiplier and coupons printed around it. */
internal data class EdekaPosition(
    val name: String,
    val quantity: Double,
    /** `"kg"` for weighed goods, null for a plain piece count — see [multiplierUnit]. */
    val unit: String?,
    /** Position total as printed, before coupons; null when the price could not be read. */
    val grossCents: Int?,
    /** Coupons booked against this position, as a non-positive value. */
    val discountCents: Int,
) {
    /** What was actually paid for the position — coupons included, so the items sum to the total. */
    val netCents: Int?
        get() = grossCents?.plus(discountCents)
}

private data class EdekaMultiplier(val quantity: Double, val unitPriceCents: Int?)

internal fun EdekaFormattedReceipt.positions(): List<EdekaPosition> {
    val positions = mutableListOf<EdekaPosition>()
    var pendingMultiplier: EdekaMultiplier? = null
    // Coupons belong to the position printed above them, which is always inside the same block.
    var blockStart = 0

    fun applyDiscount(cents: Int) {
        // Prefer the current block's own position; a coupon printed after the separator still
        // belongs to the purchase before it, so falling back keeps the receipt total honest.
        val target = positions.indices.lastOrNull { it >= blockStart }
            ?: positions.indices.lastOrNull()
            ?: return
        positions[target] = positions[target].let {
            it.copy(discountCents = it.discountCents - cents.absoluteValueCents())
        }
    }

    lines.forEach { line ->
        when {
            line.type == EdekaReceiptLineType.SEPARATOR -> {
                // A multiplier without a product line after it describes nothing; let it go.
                pendingMultiplier = null
                blockStart = positions.size
            }

            line.type == EdekaReceiptLineType.DISCOUNT ->
                parseEdekaMoney(line.right)?.let(::applyDiscount)

            else -> {
                val multiplier = parseMultiplier(line)
                val cents = parseEdekaMoney(line.right)
                when {
                    multiplier != null -> pendingMultiplier = multiplier
                    line.left.isBlank() -> Unit
                    // A negative amount on a product line is a rebate, not a purchase. Only fold it
                    // into the position above when there is one in this block: a standalone negative
                    // line ("Pfandrückgabe") is its own thing and must not discount a neighbour.
                    cents != null && cents < 0 && positions.size > blockStart -> applyDiscount(cents)
                    else -> {
                        positions += EdekaPosition(
                            name = line.left.normalizedName(),
                            quantity = multiplierQuantity(pendingMultiplier),
                            unit = multiplierUnit(pendingMultiplier),
                            grossCents = cents,
                            discountCents = 0,
                        )
                        pendingMultiplier = null
                    }
                }
            }
        }
    }
    return positions
}

/**
 * Lines that repeat verbatim — EDEKA rings two cartons of the same soya drink up as two positions
 * of 2,49 € rather than one "2 x" position — collapse into a single item with the quantities and
 * prices summed. Only fully identical positions merge: a differing price or coupon means the two
 * lines are not interchangeable, and a wrong merge would misreport what was bought.
 */
private fun List<EdekaPosition>.mergeIdenticalPositions(): List<EdekaPosition> =
    groupBy { listOf(it.name, it.unit, it.grossCents, it.discountCents) }
        .values
        .map { group ->
            group.first().copy(
                quantity = group.sumOf(EdekaPosition::quantity),
                grossCents = group.first().grossCents?.let { it * group.size },
                discountCents = group.first().discountCents * group.size,
            )
        }

/**
 * `2.000 x 0,29€` or `1.164 x 4,15€` — count or weight times the unit price, printed on its own
 * line above the product. Quantities always carry three decimals, so the separator is never a
 * thousands grouping and `,`/`.` can both be read as the decimal point.
 */
private val MULTIPLIER_LINE = Regex(
    """^(\d{1,4}(?:[.,]\d{1,3})?)\s*[x×*]\s*(\d{1,5}(?:[.,]\d{1,2})?)\s*(?:€|EUR)?$""",
    RegexOption.IGNORE_CASE,
)

private fun parseMultiplier(line: EdekaFormattedReceiptLine): EdekaMultiplier? {
    // The multiplier is the price-less line of the block; a row carrying its own total is a product
    // whose name merely happens to look like a multiplier.
    if (line.right.isNotBlank()) return null
    val match = MULTIPLIER_LINE.matchEntire(line.left.trim()) ?: return null
    val quantity = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    if (!quantity.isFinite() || quantity <= 0.0) return null
    return EdekaMultiplier(quantity, parseEdekaMoney(match.groupValues[2]))
}

private fun multiplierQuantity(multiplier: EdekaMultiplier?): Double = multiplier?.quantity ?: 1.0

/**
 * A whole multiplier counts pieces (`2.000 x 0,29€` is two rolls); a fractional one is a weight in
 * kilograms priced per kilogram (`1.164 x 4,15€` is 1.164 kg of grapes at 4,15 €/kg), which is the
 * only fractional quantity an EDEKA till prints. Pieces stay unit-less, matching how the rest of
 * the app treats countable items.
 */
private fun multiplierUnit(multiplier: EdekaMultiplier?): String? {
    val quantity = multiplier?.quantity ?: return null
    return if (quantity % 1.0 == 0.0) null else "kg"
}

/** Collapses the abbreviations' irregular spacing (`"Harry  Dopp.Bröt."`) without rewording them. */
private fun String.normalizedName(): String = trim().replace(WHITESPACE_RUN, " ")

private val WHITESPACE_RUN = Regex("""\s+""")

private fun Int.absoluteValueCents(): Int = if (this < 0) -this else this

internal fun parseEdekaMoney(value: String): Int? {
    val stripped = value
        .replace("€", "")
        .replace("EUR", "", ignoreCase = true)
        .replace(" ", "")
        .trim()
    val normalized = if (',' in stripped) {
        stripped.replace(".", "").replace(',', '.')
    } else {
        stripped
    }
    return runCatching {
        BigDecimal(normalized).movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .intValueExact()
    }.getOrNull()
}
