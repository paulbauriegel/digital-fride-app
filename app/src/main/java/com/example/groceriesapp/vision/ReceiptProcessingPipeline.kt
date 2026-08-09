package com.example.groceriesapp.vision

import com.example.groceriesapp.data.DigitalReceipt
import com.example.groceriesapp.data.GroceriesRepository
import com.example.groceriesapp.data.ReceiptItem
import com.example.groceriesapp.data.ReceiptStatus
import com.example.groceriesapp.data.ReceiptTextRegion
import com.example.groceriesapp.data.ScanSession
import com.example.groceriesapp.vision.ocr.MlKitReceiptOcrRecognizer
import com.example.groceriesapp.vision.ocr.ReceiptOcrInput
import com.example.groceriesapp.vision.ocr.ReceiptOcrRecognizer
import com.example.groceriesapp.vision.parsing.ParsedLineItem
import com.example.groceriesapp.vision.parsing.ReceiptItemExtractor
import com.example.groceriesapp.vision.parsing.RulesReceiptItemExtractor
import com.example.groceriesapp.vision.stitching.PlanarReceiptStitcher
import java.io.File
import kotlin.math.roundToInt

class ReceiptProcessingPipeline(
    private val repository: GroceriesRepository,
    private val stitcher: PlanarReceiptStitcher = PlanarReceiptStitcher(),
    private val itemExtractor: ReceiptItemExtractor = RulesReceiptItemExtractor(),
    private val ocrRecognizer: ReceiptOcrRecognizer = MlKitReceiptOcrRecognizer(),
) {
    suspend fun process(
        session: ScanSession,
        framePaths: List<String>,
        onPreviewReady: (String) -> Unit = {},
        onProgress: (ProcessingStage, Float) -> Unit,
    ): DigitalReceipt {
        require(framePaths.isNotEmpty()) { "No receipt keyframes were captured" }
        val outputDirectory = File(session.directoryPath)
        onProgress(ProcessingStage.STITCHING, 0f)
        val artifact = stitcher.stitch(
            frameFiles = framePaths.map(::File),
            outputDirectory = outputDirectory,
            onProgress = { onProgress(ProcessingStage.STITCHING, it) },
        )
        onPreviewReady(artifact.previewPath)

        onProgress(ProcessingStage.RECOGNIZING_TEXT, 0f)
        val ocr = ocrRecognizer.recognize(
            ReceiptOcrInput(
                tilePaths = artifact.tilePaths,
                previewPath = artifact.previewPath,
            ),
        )
        onProgress(ProcessingStage.RECOGNIZING_TEXT, 1f)

        onProgress(ProcessingStage.PARSING_ITEMS, 0.25f)
        val parsed = itemExtractor.extract(ocr.rawText)
        val regions = ocr.regions.map { region ->
            ReceiptTextRegion(
                text = region.text,
                globalLeft = region.left,
                globalTop = region.top,
                globalRight = region.right,
                globalBottom = region.bottom,
                confidence = region.confidence,
                sourceTileIndex = region.sourceTileIndex,
            )
        }
        val items = parsed.items.map { item ->
            item.toReceiptItem(regions)
        }
        val receipt = DigitalReceipt(
            id = session.id,
            merchant = parsed.merchant,
            dateEpochMillis = parsed.purchasedAtEpochMillis,
            totalCents = parsed.totalCents?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                ?.toInt(),
            currency = parsed.currencyCode,
            previewPath = artifact.previewPath,
            tilePaths = artifact.tilePaths,
            originalFramePaths = artifact.originalFramePaths,
            rawText = ocr.rawText,
            regions = regions,
            items = items,
            status = ReceiptStatus.READY,
            createdAtEpochMillis = session.createdAtEpochMillis,
        )
        onProgress(ProcessingStage.SAVING, 0.6f)
        repository.saveReceipt(receipt)
        onProgress(ProcessingStage.SAVING, 1f)
        return receipt
    }

    private fun ParsedLineItem.toReceiptItem(
        regions: List<ReceiptTextRegion>,
    ): ReceiptItem {
        val normalizedName = name.normalizedForMatch()
        val sourceRegion = regions.firstOrNull { region ->
            val normalizedRegion = region.text.normalizedForMatch()
            normalizedRegion.contains(normalizedName) ||
                (normalizedRegion.length >= 4 && normalizedName.contains(normalizedRegion))
        }
        return ReceiptItem(
            name = name,
            quantity = quantity,
            unit = unit,
            priceCents = lineTotalCents
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                .toInt(),
            category = category,
            foodConceptId = foodConceptId,
            textRegionId = sourceRegion?.id,
            needsReview = needsReview,
        )
    }

    private fun String.normalizedForMatch(): String = lowercase()
        .filter(Char::isLetterOrDigit)
}

enum class ProcessingStage(
    val progressStart: Float,
    val progressEnd: Float,
) {
    STITCHING(0f, 0.58f),
    RECOGNIZING_TEXT(0.58f, 0.86f),
    PARSING_ITEMS(0.86f, 0.94f),
    SAVING(0.94f, 1f),
}

fun ProcessingStage.overallProgress(stageProgress: Float): Float =
    progressStart + (progressEnd - progressStart) * stageProgress.coerceIn(0f, 1f)
