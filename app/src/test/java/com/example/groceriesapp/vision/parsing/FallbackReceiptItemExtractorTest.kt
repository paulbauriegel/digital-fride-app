package com.example.groceriesapp.vision.parsing

import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class FallbackReceiptItemExtractorTest {
    private val rules = RulesReceiptItemExtractor(ReceiptParser(ZoneOffset.UTC))
    private val receiptText = "REWE\n01.08.2026\nMilch 1,19\nGesamt 1,19"

    @Test
    fun primaryFailureFallsBackToRules() = runBlocking {
        val extractor = FallbackReceiptItemExtractor(
            primary = { error("The model is not installed.") },
            rules = rules,
        )

        val result = extractor.extract(receiptText)

        assertEquals("Milch", result.items.single().name)
        assertEquals("REWE", result.merchant)
    }

    @Test
    fun emptyPrimaryResultFallsBackWhenRulesFindItems() = runBlocking {
        val emptyResult = ParsedReceipt(
            merchant = "LLM store",
            purchasedAtEpochMillis = null,
            totalCents = null,
            currencyCode = "EUR",
            items = emptyList(),
        )
        val extractor = FallbackReceiptItemExtractor(
            primary = { emptyResult },
            rules = rules,
        )

        val result = extractor.extract(receiptText)

        assertEquals("Milch", result.items.single().name)
    }

    @Test
    fun emptyPrimaryResultIsKeptWhenRulesFindNothingEither() = runBlocking {
        val emptyResult = ParsedReceipt(
            merchant = "LLM store",
            purchasedAtEpochMillis = null,
            totalCents = null,
            currencyCode = "EUR",
            items = emptyList(),
        )
        val extractor = FallbackReceiptItemExtractor(
            primary = { emptyResult },
            rules = rules,
        )

        val result = extractor.extract("no items here")

        assertSame(emptyResult, result)
    }

    @Test
    fun successfulPrimaryResultPassesThroughUntouched() = runBlocking {
        val primaryResult = ParsedReceipt(
            merchant = "LLM store",
            purchasedAtEpochMillis = 42L,
            totalCents = 999L,
            currencyCode = "EUR",
            items = listOf(
                finishParsedLineItem(
                    name = "Joghurt",
                    quantity = 2.0,
                    unit = "pc",
                    lineTotalCents = 258L,
                ),
            ),
        )
        val extractor = FallbackReceiptItemExtractor(
            primary = { primaryResult },
            rules = rules,
        )

        assertSame(primaryResult, extractor.extract(receiptText))
    }

    @Test
    fun cancellationPropagatesInsteadOfFallingBack() {
        val extractor = FallbackReceiptItemExtractor(
            primary = { throw CancellationException("cancelled") },
            rules = rules,
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { extractor.extract(receiptText) }
        }
    }

    @Test
    fun primaryTimeoutFallsBackToRules() = runBlocking {
        val extractor = FallbackReceiptItemExtractor(
            primary = {
                kotlinx.coroutines.delay(10_000)
                error("unreachable")
            },
            rules = rules,
            primaryTimeoutMillis = 50L,
        )

        val result = extractor.extract(receiptText)

        assertEquals("Milch", result.items.single().name)
    }
}
