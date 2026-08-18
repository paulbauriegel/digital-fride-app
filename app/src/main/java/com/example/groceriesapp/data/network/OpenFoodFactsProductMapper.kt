package com.example.groceriesapp.data.network

import java.io.IOException
import java.util.Locale
import kotlin.math.round
import org.json.JSONObject

/**
 * Open Food Facts answered, but has no record for this barcode.
 *
 * Separate from a plain [IOException] because the two need different wording: an unknown pack is a
 * normal outcome the user resolves by typing the name, a failed request is something to retry.
 */
class ProductNotFoundException(val barcode: String) :
    IOException("Open Food Facts has no product for barcode $barcode.")

/**
 * The handful of Open Food Facts fields an inventory row can use.
 *
 * Nutrition, ingredients, labels and images are deliberately absent — the app tracks what is left
 * of a pack, not what is in it.
 */
data class OpenFoodFactsProduct(
    val barcode: String,
    /** Null when Open Food Facts knows the code but carries no usable name for it. */
    val name: String?,
    /** How much a full pack holds, expressed in [unit]; null when nothing parsed. */
    val quantity: Double?,
    /** Canonical: "g", "kg", "ml" or "l" — the units [UnitNormalizer] already recognises. */
    val unit: String?,
)

/**
 * Turns an Open Food Facts product response into [OpenFoodFactsProduct].
 *
 * Kept free of Android and OkHttp types, like [OpenAiReceiptResponseMapper][
 * com.example.groceriesapp.vision.parsing.OpenAiReceiptResponseMapper], so the field precedence and
 * the quantity parsing can be tested on the JVM against real response bodies.
 */
object OpenFoodFactsProductMapper {

    /**
     * @throws ProductNotFoundException when the response carries no product.
     * @throws org.json.JSONException when the body is not JSON at all.
     */
    fun map(json: String, barcode: String): OpenFoodFactsProduct {
        val root = JSONObject(json)
        val product = root.optJSONObject("product")
        if (root.optInt("status", 0) != 1 || product == null) {
            throw ProductNotFoundException(barcode)
        }
        val (quantity, unit) = product.readAmount() ?: (null to null)
        return OpenFoodFactsProduct(
            barcode = product.stringOrNull("code") ?: barcode,
            name = product.readName(),
            quantity = quantity,
            unit = unit,
        )
    }

    /**
     * German first: the app's taxonomy matches on German text, so "Haferdrink" reaches a food
     * concept where "Oat Drink" would not. Brands are the last resort — "Alnatura" alone still
     * beats an empty field the user has to fill in from scratch.
     */
    private fun JSONObject.readName(): String? = stringOrNull("product_name_de")
        ?: stringOrNull("product_name")
        ?: stringOrNull("generic_name_de")
        ?: stringOrNull("generic_name")
        ?: stringOrNull("brands")?.substringBefore(',')?.trim()?.takeIf(String::isNotEmpty)

    /** The structured pair when Open Food Facts has one, else its free-text `quantity`. */
    private fun JSONObject.readAmount(): Pair<Double, String>? {
        val structured = stringOrNull("product_quantity")?.toDoubleOrNull()
        val structuredUnit = stringOrNull("product_quantity_unit")
        if (structured != null && structuredUnit != null) {
            canonicalize(structured, structuredUnit)?.let { return it }
        }
        return parseQuantityText(stringOrNull("quantity"))
    }

    /**
     * Reads the shapes contributors actually type into the `quantity` field: "500 g", "1,5 l",
     * "1L", "6 x 33 cl". Anything else — "1 Packung", "ca. 4 Stück" — yields null, leaving the
     * amount untracked rather than inventing a number.
     */
    internal fun parseQuantityText(text: String?): Pair<Double, String>? {
        val trimmed = text?.trim()?.takeIf(String::isNotEmpty) ?: return null
        MULTIPACK.matchEntire(trimmed)?.let { match ->
            val packs = match.groupValues[1].toDoubleOrNull() ?: return@let
            val each = match.groupValues[2].toDecimal() ?: return@let
            return canonicalize(packs * each, match.groupValues[3])
        }
        val match = SINGLE.matchEntire(trimmed) ?: return null
        val amount = match.groupValues[1].toDecimal() ?: return null
        return canonicalize(amount, match.groupValues[2])
    }

    /**
     * Folds Open Food Facts' unit spellings onto the four the rest of the app understands.
     * Deliberately not in [UnitNormalizer][com.example.groceriesapp.data.recipes.UnitNormalizer]:
     * widening the shared recipe matcher for "cl" and "oz" would change ingredient matching for
     * every recipe to serve one caller.
     */
    private fun canonicalize(amount: Double, rawUnit: String): Pair<Double, String>? {
        if (!amount.isFinite() || amount <= 0.0) return null
        val (factor, unit) = when (rawUnit.trim().lowercase(Locale.ROOT).removeSuffix(".")) {
            "g", "gr", "gramm", "gramme", "grammes", "gram", "grams" -> 1.0 to "g"
            "kg", "kilo", "kilos", "kilogramm", "kilogram" -> 1.0 to "kg"
            "mg" -> 0.001 to "g"
            "oz" -> 28.35 to "g"
            "ml", "milliliter", "millilitre" -> 1.0 to "ml"
            "cl", "centiliter", "centilitre" -> 10.0 to "ml"
            "dl", "deziliter", "decilitre" -> 100.0 to "ml"
            "l", "ltr", "liter", "litre", "liters", "litres" -> 1.0 to "l"
            else -> return null
        }
        // Two decimals: enough for an ounce conversion, short of float noise like 1979.9999999998.
        return round(amount * factor * 100.0) / 100.0 to unit
    }

    /** Open Food Facts is contributor-entered and largely European, so "1,5" means one and a half. */
    private fun String.toDecimal(): Double? = replace(',', '.').toDoubleOrNull()

    /**
     * `optString` returns the literal string "null" for a JSON null on Android, and Open Food Facts
     * fills absent fields with explicit nulls — so a name would read as "null" on device while
     * passing every JVM test.
     */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key).trim().takeIf(String::isNotEmpty)

    private val SINGLE = Regex("""([\d.,]+)\s*([a-zA-Zµ]+)""")
    private val MULTIPACK = Regex("""(\d+)\s*[x×*]\s*([\d.,]+)\s*([a-zA-Zµ]+)""")
}
