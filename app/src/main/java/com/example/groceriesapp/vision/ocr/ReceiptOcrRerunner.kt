package com.example.groceriesapp.vision.ocr

import com.example.groceriesapp.data.DigitalReceipt
import com.example.groceriesapp.data.ReceiptItem
import com.example.groceriesapp.data.ReceiptStatus
import com.example.groceriesapp.data.ReceiptTextRegion
import com.example.groceriesapp.vision.editing.ReceiptImageEditor
import com.example.groceriesapp.vision.parsing.ParsedLineItem
import com.example.groceriesapp.vision.parsing.ReceiptParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReceiptOcrRerunner(
    private val parser: ReceiptParser = ReceiptParser(),
    private val recognizer: ReceiptOcrRecognizer = MlKitReceiptOcrRecognizer(),
    private val projectRegions: suspend (
        DigitalReceipt,
        List<ReceiptTextRegion>,
    ) -> List<ReceiptTextRegion> = { receipt, regions ->
        ReceiptImageEditor().projectRegionsForCurrentEdit(receipt, regions)
    },
) {
    suspend fun rerun(receipt: DigitalReceipt): DigitalReceipt {
        val useCurrentEdit = recognizer.inputSource == ReceiptOcrInputSource.CURRENT_EDIT
        val inputTilePaths = if (useCurrentEdit) {
            receipt.tilePaths.ifEmpty { receipt.sourceTilePaths }
        } else {
            receipt.sourceTilePaths.ifEmpty { receipt.tilePaths }
        }
        val inputPreviewPath = if (useCurrentEdit) {
            receipt.previewPath ?: receipt.sourcePreviewPath
        } else {
            receipt.sourcePreviewPath ?: receipt.previewPath
        }
        require(inputTilePaths.isNotEmpty()) { "This receipt has no stitched image to OCR." }

        val ocr = recognizer.recognize(
            ReceiptOcrInput(
                tilePaths = inputTilePaths,
                previewPath = inputPreviewPath,
            ),
        )
        val parsed = withContext(Dispatchers.Default) { parser.parse(ocr.rawText) }
        val recognizedRegions = ocr.regions.map(OcrRegion::toReceiptTextRegion)
        val sourceRegions: List<ReceiptTextRegion>
        val displayedRegions: List<ReceiptTextRegion>
        if (useCurrentEdit) {
            sourceRegions = receipt.sourceRegions
            displayedRegions = recognizedRegions
        } else {
            sourceRegions = recognizedRegions
            displayedRegions = projectRegions(receipt, sourceRegions)
        }
        val items = parsed.items.map { item -> item.toReceiptItem(displayedRegions) }

        return receipt.copy(
            merchant = parsed.merchant,
            dateEpochMillis = parsed.purchasedAtEpochMillis,
            totalCents = parsed.totalCents
                ?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                ?.toInt(),
            currency = parsed.currencyCode,
            sourceRegions = sourceRegions,
            rawText = ocr.rawText,
            regions = displayedRegions,
            items = items,
            status = ReceiptStatus.READY,
        )
    }
}

private fun OcrRegion.toReceiptTextRegion(): ReceiptTextRegion =
    ReceiptTextRegion(
        text = text,
        globalLeft = left,
        globalTop = top,
        globalRight = right,
        globalBottom = bottom,
        confidence = confidence,
        sourceTileIndex = sourceTileIndex,
    )

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
