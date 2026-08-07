package com.example.groceriesapp.vision.ocr

enum class OcrEngine(
    val preferenceValue: String,
    val displayName: String,
) {
    ML_KIT("ml_kit", "ML Kit"),
    GLM_OCR("glm_ocr", "GLM-OCR"),
    GLM_OCR_LITERT("glm_ocr_litert", "GLM-OCR LiteRT"),
    ;

    companion object {
        fun fromPreference(value: String?): OcrEngine =
            entries.firstOrNull { it.preferenceValue == value } ?: ML_KIT
    }
}
