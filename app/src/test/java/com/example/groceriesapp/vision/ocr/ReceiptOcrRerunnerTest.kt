package com.example.groceriesapp.vision.ocr

import com.example.groceriesapp.data.DigitalReceipt
import com.example.groceriesapp.data.ReceiptImageEdit
import com.example.groceriesapp.data.ReceiptStatus
import com.example.groceriesapp.data.ReceiptTextRegion
import com.example.groceriesapp.vision.parsing.ReceiptParser
import com.example.groceriesapp.vision.parsing.RulesReceiptItemExtractor
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReceiptOcrRerunnerTest {
    @Test
    fun originalInputRerunUsesSourceTilesAndProjectsRegionsForCurrentEdit() = runBlocking {
        val originalEdit = ReceiptImageEdit(
            rotationDegrees = 2f,
            cropLeft = 0.1f,
            cropTop = 0.1f,
            cropRight = 0.9f,
            cropBottom = 0.9f,
        )
        val receipt = DigitalReceipt(
            id = "receipt-1",
            merchant = "Old merchant",
            totalCents = 999,
            tilePaths = listOf("edited-tile.jpg"),
            sourceTilePaths = listOf("source-1.jpg", "source-2.jpg"),
            previewPath = "edited-preview.jpg",
            sourcePreviewPath = "source-preview.jpg",
            imageEdit = originalEdit,
            rawText = "Old OCR",
            status = ReceiptStatus.FAILED,
            createdAtEpochMillis = 1234L,
        )
        val ocrResult = ReceiptOcrResult(
            rawText = "HELLWEG\n29.06.2026\nMilch 1,99\nGesamt 1,99",
            regions = listOf(
                OcrRegion(
                    text = "Milch 1,99",
                    left = 10f,
                    top = 20f,
                    right = 80f,
                    bottom = 40f,
                    confidence = 0.9f,
                    sourceTileIndex = 0,
                ),
            ),
        )
        var recognizedInput: ReceiptOcrInput? = null
        val rerunner = ReceiptOcrRerunner(
            itemExtractor = RulesReceiptItemExtractor(ReceiptParser(ZoneOffset.UTC)),
            recognizer = ReceiptOcrRecognizer { input ->
                recognizedInput = input
                ocrResult
            },
            projectRegions = { projectedReceipt, sourceRegions ->
                assertSame(receipt, projectedReceipt)
                sourceRegions.map { region ->
                    region.copy(
                        globalLeft = region.globalLeft + 5f,
                        globalTop = region.globalTop + 7f,
                    )
                }
            },
        )

        val updated = rerunner.rerun(receipt)

        assertEquals(receipt.sourceTilePaths, recognizedInput?.tilePaths)
        assertEquals(receipt.sourcePreviewPath, recognizedInput?.previewPath)
        assertEquals("receipt-1", updated.id)
        assertEquals(1234L, updated.createdAtEpochMillis)
        assertEquals(originalEdit, updated.imageEdit)
        assertEquals(receipt.tilePaths, updated.tilePaths)
        assertEquals("HELLWEG", updated.merchant)
        assertEquals(199, updated.totalCents)
        assertEquals(ReceiptStatus.READY, updated.status)
        assertEquals(ocrResult.rawText, updated.rawText)
        assertEquals(10f, updated.sourceRegions.single().globalLeft)
        assertEquals(15f, updated.regions.single().globalLeft)
        assertEquals(27f, updated.regions.single().globalTop)
        assertEquals("Milch", updated.items.single().name)
        assertEquals(199, updated.items.single().priceCents)
        assertEquals(updated.regions.single().id, updated.items.single().textRegionId)
        assertNotEquals("Old OCR", updated.rawText)
    }

    @Test
    fun currentEditInputRerunUsesDisplayedImageWithoutReprojectingRegions() = runBlocking {
        val receipt = DigitalReceipt(
            tilePaths = listOf("edited-tile.jpg"),
            sourceTilePaths = listOf("source-tile.jpg"),
            previewPath = "edited-preview.jpg",
            sourcePreviewPath = "source-preview.jpg",
            sourceRegions = listOf(
                ReceiptTextRegion(
                    text = "Old source region",
                    globalLeft = 1f,
                    globalTop = 2f,
                    globalRight = 3f,
                    globalBottom = 4f,
                    confidence = 0.5f,
                    sourceTileIndex = 0,
                ),
            ),
        )
        var recognizedInput: ReceiptOcrInput? = null
        var projected = false
        val recognizer = object : ReceiptOcrRecognizer {
            override val inputSource = ReceiptOcrInputSource.CURRENT_EDIT

            override suspend fun recognize(input: ReceiptOcrInput): ReceiptOcrResult {
                recognizedInput = input
                return ReceiptOcrResult(
                    rawText = "HELLWEG\nMilch 1,99\nGesamt 1,99",
                    regions = listOf(
                        OcrRegion(
                            text = "Milch 1,99",
                            left = 10f,
                            top = 20f,
                            right = 80f,
                            bottom = 40f,
                            confidence = 0.9f,
                            sourceTileIndex = 0,
                        ),
                    ),
                )
            }
        }
        val rerunner = ReceiptOcrRerunner(
            recognizer = recognizer,
            projectRegions = { _, regions ->
                projected = true
                regions
            },
        )

        val updated = rerunner.rerun(receipt)

        assertEquals(receipt.tilePaths, recognizedInput?.tilePaths)
        assertEquals(receipt.previewPath, recognizedInput?.previewPath)
        assertEquals(receipt.sourceRegions, updated.sourceRegions)
        assertEquals(10f, updated.regions.single().globalLeft)
        assertEquals(updated.regions.single().id, updated.items.single().textRegionId)
        assertFalse(projected)
    }

    @Test
    fun rerunFallsBackToCurrentTilesWhenNoSourceTilesExist() = runBlocking {
        val receipt = DigitalReceipt(
            tilePaths = listOf("current-tile.jpg"),
        )
        var recognizedInput: ReceiptOcrInput? = null
        val rerunner = ReceiptOcrRerunner(
            recognizer = ReceiptOcrRecognizer { input ->
                recognizedInput = input
                ReceiptOcrResult(rawText = "", regions = emptyList())
            },
            projectRegions = { _, regions -> regions },
        )

        rerunner.rerun(receipt)

        assertEquals(receipt.tilePaths, recognizedInput?.tilePaths)
    }
}
