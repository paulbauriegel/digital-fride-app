package com.example.groceriesapp.vision.parsing

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts receipt data with the on-device Granite 4.0 350M model via LiteRT-LM. The prompt is
 * fixed; failures (model missing, no parseable items) throw so [FallbackReceiptItemExtractor]
 * can revert to the built-in rules.
 */
class GraniteReceiptItemExtractor(
    context: Context,
    private val modelManager: GraniteModelManager = GraniteModelManager(context),
    private val responseParser: GraniteReceiptResponseParser = GraniteReceiptResponseParser(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ReceiptItemExtractor {
    private val appContext = context.applicationContext

    override suspend fun extract(rawText: String): ParsedReceipt =
        withContext(Dispatchers.Default) {
            modelManager.requireReady()
            val responseText = runConversation(buildPrompt(capReceiptText(rawText)))
            Log.d(TAG, "Granite response:\n$responseText")
            val parsed = groundInRawText(responseParser.parse(responseText, zoneId), rawText)
            check(parsed.items.isNotEmpty()) { "Granite returned no parseable items." }
            parsed
        }

    /**
     * A 350M model happily invents merchants and items that appear nowhere on the receipt.
     * Anything without a textual anchor in the OCR text is dropped; if nothing survives, the
     * caller throws and the rules fallback takes over.
     */
    private fun groundInRawText(parsed: ParsedReceipt, rawText: String): ParsedReceipt {
        val haystack = rawText.normalizedForGrounding()
        return parsed.copy(
            merchant = parsed.merchant?.takeIf { it.appearsIn(haystack) },
            items = parsed.items.filter { it.name.appearsIn(haystack) },
        )
    }

    private fun String.appearsIn(haystack: String): Boolean {
        val words = split(' ', '-', '/').map(String::normalizedForGrounding)
        return words.any { word -> word.length >= 3 && haystack.contains(word) }
    }

    private fun runConversation(prompt: String): String =
        Engine(
            EngineConfig(
                modelPath = modelManager.modelFile.absolutePath,
                backend = Backend.CPU(threadCount = THREAD_COUNT),
                maxNumTokens = MODEL_CONTEXT_TOKENS,
                cacheDir = File(appContext.cacheDir, "litertlm-granite").path,
            ),
        ).use { engine ->
            engine.initialize()
            val config = ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
                samplerConfig = SamplerConfig(
                    topK = 1,
                    topP = 1.0,
                    temperature = 0.0,
                    seed = 1,
                ),
            )
            engine.createConversation(config).use { conversation ->
                val response = conversation.sendMessage(prompt)
                response.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString(separator = "") { it.text }
            }
        }

    /**
     * The ekv1280 export shares ~1280 tokens between prompt and answer, so the OCR text is
     * reduced to the lines that can carry receipt data (header, prices, dates, totals, weights)
     * and hard-capped, leaving room for the model's answer.
     */
    private fun capReceiptText(rawText: String): String {
        val lines = rawText.lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter(String::isNotBlank)
            .toList()
        val kept = lines.filterIndexed { index, line ->
            index < HEADER_LINES ||
                ReceiptParser.PRICE_AT_END.containsMatchIn(line) ||
                ReceiptParser.DATE_ANYWHERE.containsMatchIn(line) ||
                ReceiptParser.TOTAL_LABEL.containsMatchIn(line) ||
                WEIGHT_LINE.containsMatchIn(line)
        }
        return kept.joinToString("\n").take(MAX_INPUT_CHARS)
    }

    private fun buildPrompt(receiptText: String): String =
        """
        Read the German receipt text below and answer only with labeled lines in this format:
        MERCHANT: the store name from the receipt, or -
        DATE: the purchase date as yyyy-MM-dd, or -
        TOTAL: the final amount in euro with a comma, or -
        ITEM: product name | quantity | unit | price in euro

        One ITEM line per purchased product, copied from the receipt text. unit is kg, g, l, ml, pc or -. Use quantity 1 if none is shown. A weight line like "0,486 kg x 2,99 EUR/kg" belongs to the product next to it: use 0,486 as quantity, kg as unit, and the printed line price. A line like "2,49 x 3" means quantity 3 and unit pc. Skip Pfand, Rabatt, MwSt, Summe, payment and change lines. Do not invent products that are not in the receipt text.

        Receipt text:
        $receiptText

        Answer:
        """.trimIndent()

    private companion object {
        const val TAG = "ItemExtraction"
        const val SYSTEM_INSTRUCTION =
            "You convert German supermarket receipt OCR text into labeled lines. " +
                "Answer only with the labeled lines, nothing else."
        const val THREAD_COUNT = 3
        const val MODEL_CONTEXT_TOKENS = 1_280
        const val HEADER_LINES = 8
        const val MAX_INPUT_CHARS = 2_000
        val WEIGHT_LINE = Regex("\\d+[.,]\\d+\\s*kg", RegexOption.IGNORE_CASE)
    }
}

private fun String.normalizedForGrounding(): String =
    lowercase(Locale.GERMANY).filter(Char::isLetterOrDigit)
