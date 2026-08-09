package com.example.groceriesapp.vision.parsing

import android.content.Context
import android.util.Log
import com.example.groceriesapp.data.OpenAiKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Converts raw receipt OCR text into a [ParsedReceipt]. */
fun interface ReceiptItemExtractor {
    suspend fun extract(rawText: String): ParsedReceipt
}

class RulesReceiptItemExtractor(
    private val parser: ReceiptParser = ReceiptParser(),
) : ReceiptItemExtractor {
    override suspend fun extract(rawText: String): ParsedReceipt =
        withContext(Dispatchers.Default) { parser.parse(rawText) }
}

/**
 * Wraps an LLM extractor so a scan never fails because of it: any error, timeout, or empty result
 * falls back to the built-in rules. The raw OCR text stays authoritative either way.
 */
class FallbackReceiptItemExtractor(
    private val primary: ReceiptItemExtractor,
    private val rules: RulesReceiptItemExtractor = RulesReceiptItemExtractor(),
    private val primaryTimeoutMillis: Long = 180_000L,
) : ReceiptItemExtractor {
    override suspend fun extract(rawText: String): ParsedReceipt {
        val primaryResult = try {
            withTimeout(primaryTimeoutMillis) { primary.extract(rawText) }
        } catch (error: TimeoutCancellationException) {
            Log.w(TAG, "LLM extraction timed out, using built-in rules", error)
            return rules.extract(rawText)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "LLM extraction failed, using built-in rules", error)
            return rules.extract(rawText)
        }
        if (primaryResult.items.isEmpty()) {
            val rulesResult = rules.extract(rawText)
            if (rulesResult.items.isNotEmpty()) {
                Log.w(TAG, "LLM extraction found no items, using built-in rules")
                return rulesResult
            }
        }
        return primaryResult
    }

    private companion object {
        const val TAG = "ItemExtraction"
    }
}

fun receiptItemExtractor(
    context: Context,
    engine: ItemExtractionEngine,
): ReceiptItemExtractor = when (engine) {
    ItemExtractionEngine.BUILT_IN_RULES -> RulesReceiptItemExtractor()
    ItemExtractionEngine.GRANITE ->
        FallbackReceiptItemExtractor(GraniteReceiptItemExtractor(context))
    ItemExtractionEngine.OPENAI ->
        FallbackReceiptItemExtractor(OpenAiReceiptItemExtractor(OpenAiKeyStore(context)))
}
