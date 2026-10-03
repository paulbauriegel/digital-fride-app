package com.example.groceriesapp.data.recipes

import com.example.groceriesapp.vision.ocr.ReceiptOcrInput
import com.example.groceriesapp.vision.ocr.ReceiptOcrRecognizer
import java.io.File

/** What a recipe scan produced: the OCR text always, a structured draft when it parsed. */
data class RecipeScanResult(
    val recipe: Recipe?,
    val rawText: String,
    val imageFile: File,
)

/**
 * A recipe awaiting the user's review before it is saved — from a scan (with photo and OCR
 * text) or a link import (recipe only). [recipe] is null when the text was read but did not
 * parse as a recipe; the review screen then starts from an empty form and shows the raw text.
 */
data class RecipeReviewDraft(
    val recipe: Recipe?,
    val rawText: String?,
    val imageFile: File?,
    val sourceLabel: String,
)

/**
 * Photo → OCR → structured recipe. Uses the app's receipt OCR abstraction, so the engine the
 * user picked in Settings (ML Kit or GLM-OCR) reads cookbook pages too. A single JPEG is passed
 * as both the one tile and the preview: the ML Kit recognizer reads tiles, the GLM recognizers
 * read the preview, and neither needs anything else.
 */
class RecipeScanner(
    private val ocr: ReceiptOcrRecognizer,
    private val extractor: RecipeTextExtractor,
) {
    suspend fun scan(imageFile: File, nowEpochMillis: Long): RecipeScanResult {
        require(imageFile.isFile) { "Scan image does not exist: ${imageFile.path}" }
        val result = ocr.recognize(
            ReceiptOcrInput(tilePaths = listOf(imageFile.path), previewPath = imageFile.path),
        )
        val text = result.rawText.trim()
        val recipe = if (text.isBlank()) null else extractor.extract(text, nowEpochMillis)
        return RecipeScanResult(recipe = recipe, rawText = text, imageFile = imageFile)
    }
}
