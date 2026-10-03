package com.example.groceriesapp.data.recipes

import com.example.groceriesapp.vision.ocr.ReceiptOcrInput
import com.example.groceriesapp.vision.ocr.ReceiptOcrRecognizer
import com.example.groceriesapp.vision.ocr.ReceiptOcrResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecipeScannerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun image(): File = temporaryFolder.newFile("scan.jpg")

    private fun ocrReturning(text: String, seen: MutableList<ReceiptOcrInput> = mutableListOf()) =
        ReceiptOcrRecognizer { input ->
            seen += input
            ReceiptOcrResult(rawText = text, regions = emptyList())
        }

    @Test
    fun `passes the photo as both tile and preview and hands the text to the extractor`() = runBlocking {
        val seen = mutableListOf<ReceiptOcrInput>()
        val file = image()
        val scanner = RecipeScanner(
            ocr = ocrReturning("Pancakes\n2 eggs\n1. Fry.", seen),
            extractor = RulesRecipeTextExtractor(),
        )

        val result = scanner.scan(file, nowEpochMillis = 9L)

        assertEquals(listOf(file.path), seen.single().tilePaths)
        assertEquals(file.path, seen.single().previewPath)
        assertEquals("Pancakes", result.recipe?.title)
        assertEquals(9L, result.recipe?.createdAtEpochMillis)
        assertEquals("Pancakes\n2 eggs\n1. Fry.", result.rawText)
        assertEquals(file, result.imageFile)
    }

    @Test
    fun `blank OCR text skips extraction`() = runBlocking {
        var extractorCalls = 0
        val scanner = RecipeScanner(
            ocr = ocrReturning("   \n "),
            extractor = RecipeTextExtractor { _, _ ->
                extractorCalls++
                null
            },
        )

        val result = scanner.scan(image(), 0L)

        assertNull(result.recipe)
        assertEquals("", result.rawText)
        assertEquals(0, extractorCalls)
    }

    @Test
    fun `a missing image is rejected before OCR runs`() {
        val scanner = RecipeScanner(ocrReturning("x"), RulesRecipeTextExtractor())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { scanner.scan(File(temporaryFolder.root, "missing.jpg"), 0L) }
        }
    }
}
