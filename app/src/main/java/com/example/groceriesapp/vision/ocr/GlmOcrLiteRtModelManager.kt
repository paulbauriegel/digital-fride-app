package com.example.groceriesapp.vision.ocr

import android.content.Context
import java.io.File

data class GlmOcrLiteRtModelState(
    val visionSizeBytes: Long?,
    val decoderSizeBytes: Long?,
) {
    val ready: Boolean
        get() = visionSizeBytes != null && decoderSizeBytes != null
}

class GlmOcrLiteRtModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val externalDirectory = requireNotNull(appContext.getExternalFilesDir(null))
        .resolve(MODEL_DIRECTORY)
    private val internalDirectory = appContext.filesDir.resolve(MODEL_DIRECTORY)

    val visionFile: File
        get() = locate(VISION_FILE)

    val decoderFile: File
        get() = locate(DECODER_FILE)

    fun state(): GlmOcrLiteRtModelState =
        GlmOcrLiteRtModelState(
            visionSizeBytes = validModel(visionFile, MIN_VISION_BYTES)?.length(),
            decoderSizeBytes = validModel(decoderFile, MIN_DECODER_BYTES)?.length(),
        )

    fun requireReady(): GlmOcrLiteRtModelState =
        state().also {
            check(it.ready) {
                "The GLM-OCR LiteRT model files are not installed on this device."
            }
        }

    private fun locate(name: String): File =
        externalDirectory.resolve(name).takeIf(File::isFile)
            ?: internalDirectory.resolve(name)

    private fun validModel(file: File, minimumBytes: Long): File? =
        file.takeIf { it.isFile && it.length() >= minimumBytes && hasTfliteHeader(it) }

    companion object {
        const val MODEL_DIRECTORY = "models/glm-ocr-litert"
        const val VISION_FILE = "glm_ocr_vision_68x46_fp32.tflite"
        const val DECODER_FILE = "glm_ocr_multimodal_decoder_combined_fp16.tflite"
        private const val MIN_VISION_BYTES = 1_500_000_000L
        private const val MIN_DECODER_BYTES = 1_400_000_000L
    }
}

internal fun hasTfliteHeader(file: File): Boolean =
    runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(8)
            input.read(header) == header.size &&
                header.copyOfRange(4, 8).contentEquals("TFL3".encodeToByteArray())
        }
    }.getOrDefault(false)
