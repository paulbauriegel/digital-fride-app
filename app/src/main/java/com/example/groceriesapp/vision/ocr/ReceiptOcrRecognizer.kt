package com.example.groceriesapp.vision.ocr

import android.content.Context

data class ReceiptOcrInput(
    val tilePaths: List<String>,
    val previewPath: String?,
)

enum class ReceiptOcrInputSource {
    ORIGINAL,
    CURRENT_EDIT,
}

fun interface ReceiptOcrRecognizer {
    val inputSource: ReceiptOcrInputSource
        get() = ReceiptOcrInputSource.ORIGINAL

    suspend fun recognize(input: ReceiptOcrInput): ReceiptOcrResult
}

class MlKitReceiptOcrRecognizer : ReceiptOcrRecognizer {
    override suspend fun recognize(input: ReceiptOcrInput): ReceiptOcrResult =
        ReceiptOcrEngine().use { engine ->
            engine.recognizeTiles(input.tilePaths)
        }
}

fun receiptOcrRecognizer(
    context: Context,
    engine: OcrEngine,
): ReceiptOcrRecognizer =
    when (engine) {
        OcrEngine.ML_KIT -> MlKitReceiptOcrRecognizer()
        OcrEngine.GLM_OCR -> GlmOcrReceiptRecognizer(context)
        OcrEngine.GLM_OCR_LITERT -> GlmOcrLiteRtReceiptRecognizer(context)
    }
