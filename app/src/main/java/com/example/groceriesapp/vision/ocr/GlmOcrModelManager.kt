package com.example.groceriesapp.vision.ocr

import android.content.Context
import java.io.File
import java.io.IOException

data class GlmOcrModelState(
    val decoderName: String?,
    val decoderSizeBytes: Long?,
    val projectorName: String?,
    val projectorSizeBytes: Long?,
    val runtimeAvailable: Boolean,
) {
    val modelsReady: Boolean
        get() = decoderSizeBytes != null && projectorSizeBytes != null

    val ready: Boolean
        get() = modelsReady && runtimeAvailable
}

class GlmOcrModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val modelDirectory = File(appContext.filesDir, MODEL_DIRECTORY)

    val decoderFile: File
        get() = File(modelDirectory, DECODER_FILE)

    val projectorFile: File
        get() = File(modelDirectory, PROJECTOR_FILE)

    val runtimeExecutable: File
        get() = File(appContext.applicationInfo.nativeLibraryDir, RUNTIME_FILE)

    fun state(): GlmOcrModelState =
        GlmOcrModelState(
            decoderName = validModel(decoderFile)?.name,
            decoderSizeBytes = validModel(decoderFile)?.length(),
            projectorName = validModel(projectorFile)?.name,
            projectorSizeBytes = validModel(projectorFile)?.length(),
            runtimeAvailable = runtimeExecutable.isFile && runtimeExecutable.canExecute(),
        )

    fun requireReady(): GlmOcrModelState =
        state().also { current ->
            check(current.runtimeAvailable) {
                "The bundled GLM-OCR runtime is unavailable on this device. GLM-OCR requires arm64."
            }
            check(current.modelsReady) {
                "The GLM-OCR decoder and multimodal projector are not installed on this device."
            }
        }

    private fun validModel(file: File): File? =
        file.takeIf { it.isFile && it.length() >= MIN_GGUF_BYTES && hasGgufHeader(it) }

    private companion object {
        const val MODEL_DIRECTORY = "models/glm-ocr"
        const val DECODER_FILE = "GLM-OCR.gguf"
        const val PROJECTOR_FILE = "mmproj-GLM-OCR.gguf"
        const val RUNTIME_FILE = "libllama_mtmd_cli.so"
        const val MIN_GGUF_BYTES = 1_048_576L
    }
}

internal fun hasGgufHeader(file: File): Boolean =
    try {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            input.read(header) == header.size &&
                header.contentEquals(
                    byteArrayOf(
                        'G'.code.toByte(),
                        'G'.code.toByte(),
                        'U'.code.toByte(),
                        'F'.code.toByte(),
                    ),
                )
        }
    } catch (_: IOException) {
        false
    }
