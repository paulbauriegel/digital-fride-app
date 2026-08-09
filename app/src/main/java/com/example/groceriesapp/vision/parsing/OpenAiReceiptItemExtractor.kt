package com.example.groceriesapp.vision.parsing

import com.example.groceriesapp.data.OpenAiKeyStore
import com.example.groceriesapp.data.network.OpenAiChatClient
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MissingOpenAiKeyException : IllegalStateException("No OpenAI API key is stored.")

/**
 * Extracts receipt data with OpenAI's gpt-5.6-luna over Chat Completions with a strict JSON
 * schema. The prompt is fixed; failures (no key, network, auth, malformed answer) throw so
 * [FallbackReceiptItemExtractor] can revert to the built-in rules.
 */
class OpenAiReceiptItemExtractor(
    private val keyStore: OpenAiKeyStore,
    private val client: OpenAiChatClient =
        OpenAiChatClient(endpoint = "${keyStore.apiBase()}/chat/completions"),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ReceiptItemExtractor {
    override suspend fun extract(rawText: String): ParsedReceipt {
        val apiKey = keyStore.apiKey() ?: throw MissingOpenAiKeyException()
        val content = withContext(Dispatchers.IO) {
            client.completeStructured(
                apiKey = apiKey,
                model = keyStore.textModel(),
                systemPrompt = SYSTEM_PROMPT,
                userContent = "Receipt OCR text:\n\n$rawText",
                schemaName = "receipt_extraction",
                schema = buildSchema(),
            )
        }
        return OpenAiReceiptResponseMapper.map(content, zoneId)
    }

    companion object {
        val SYSTEM_PROMPT = """
            You extract structured data from German supermarket receipts (REWE, EDEKA, BIO COMPANY, dm, Lidl, Aldi and similar). The input is raw OCR text and may contain recognition errors, split or duplicated fragments, and store noise.

            Rules:
            - Extract every purchased product as one item: its name as printed (cleaned of obvious OCR artifacts), quantity, unit, and line total in euro cents.
            - All money amounts are integers in euro cents: "2,49" means 249.
            - A weight line such as "0,486 kg x 2,99 EUR/kg" refers to the product on the neighbouring line: set that item's quantity to 0.486, unit to "kg", and line_total_cents to the price printed for that product.
            - A count line such as "2 x 1,99" means quantity 2 and unit "pc".
            - If a line shows no quantity, use quantity 1 and unit null.
            - Do not create items for deposit (Pfand), discounts (Rabatt, Coupon, Ersparnis), tax (MwSt, USt), subtotal, payment, change (Rückgeld), or loyalty lines. If a discount clearly belongs to one item, subtract it from that item's line_total_cents instead.
            - date is the purchase date in yyyy-MM-dd format, or null if not found.
            - total_cents is the final amount paid (Summe, Gesamt, Zu zahlen), or null if not found.
            - merchant is the store name, or null if unclear.
        """.trimIndent()

        fun buildSchema(): JSONObject = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("required", JSONArray(listOf("merchant", "date", "total_cents", "items")))
            .put(
                "properties",
                JSONObject()
                    .put(
                        "merchant",
                        JSONObject()
                            .put("type", JSONArray(listOf("string", "null")))
                            .put("description", "Store name, e.g. REWE, BIO COMPANY, EDEKA"),
                    )
                    .put(
                        "date",
                        JSONObject()
                            .put("type", JSONArray(listOf("string", "null")))
                            .put("description", "Purchase date as yyyy-MM-dd"),
                    )
                    .put(
                        "total_cents",
                        JSONObject()
                            .put("type", JSONArray(listOf("integer", "null")))
                            .put("description", "Final amount paid in euro cents"),
                    )
                    .put(
                        "items",
                        JSONObject()
                            .put("type", "array")
                            .put(
                                "items",
                                JSONObject()
                                    .put("type", "object")
                                    .put("additionalProperties", false)
                                    .put(
                                        "required",
                                        JSONArray(
                                            listOf("name", "quantity", "unit", "line_total_cents"),
                                        ),
                                    )
                                    .put(
                                        "properties",
                                        JSONObject()
                                            .put("name", JSONObject().put("type", "string"))
                                            .put("quantity", JSONObject().put("type", "number"))
                                            .put(
                                                "unit",
                                                JSONObject().put(
                                                    "anyOf",
                                                    JSONArray()
                                                        .put(
                                                            JSONObject()
                                                                .put("type", "string")
                                                                .put(
                                                                    "enum",
                                                                    JSONArray(
                                                                        listOf(
                                                                            "kg", "g", "l",
                                                                            "ml", "pc",
                                                                        ),
                                                                    ),
                                                                ),
                                                        )
                                                        .put(JSONObject().put("type", "null")),
                                                ),
                                            )
                                            .put(
                                                "line_total_cents",
                                                JSONObject().put("type", "integer"),
                                            ),
                                    ),
                            ),
                    ),
            )
    }
}

/** Maps the schema-constrained JSON answer onto [ParsedReceipt]. Pure and unit-testable. */
object OpenAiReceiptResponseMapper {
    private val ALLOWED_UNITS = setOf("kg", "g", "l", "ml", "pc")

    fun map(content: String, zoneId: ZoneId): ParsedReceipt {
        val root = JSONObject(content)
        val merchant = root.stringOrNull("merchant")
        val dateEpochMillis = root.stringOrNull("date")?.let { value ->
            try {
                LocalDate.parse(value).atStartOfDay(zoneId).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
        val totalCents = if (root.isNull("total_cents")) null else root.optLong("total_cents")
        val items = root.optJSONArray("items")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.toLineItem()
            }
        }.orEmpty()
        return ParsedReceipt(
            merchant = merchant,
            purchasedAtEpochMillis = dateEpochMillis,
            totalCents = totalCents,
            currencyCode = "EUR",
            items = items,
        )
    }

    private fun JSONObject.toLineItem(): ParsedLineItem? {
        val name = stringOrNull("name")?.trim() ?: return null
        if (name.count(Char::isLetter) < 2) return null
        val cents = optLong("line_total_cents")
        if (cents <= 0) return null
        val quantity = optDouble("quantity").takeIf { it > 0 && it.isFinite() } ?: 1.0
        val unit = stringOrNull("unit")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it in ALLOWED_UNITS }
        return finishParsedLineItem(
            name = name,
            quantity = quantity,
            unit = unit,
            lineTotalCents = cents,
        )
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotEmpty)
}
