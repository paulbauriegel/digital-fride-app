package com.example.groceriesapp.vision.ocr

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class GlmOcrLiteRtModelPart {
    VISION,
    DECODER,
}

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
                "Install both GLM-OCR LiteRT model files in Settings first."
            }
        }

    suspend fun import(part: GlmOcrLiteRtModelPart, uri: Uri): GlmOcrLiteRtModelState =
        withContext(Dispatchers.IO) {
            check(externalDirectory.mkdirs() || externalDirectory.isDirectory) {
                "Could not create the LiteRT model directory."
            }
            val target = externalDirectory.resolve(
                when (part) {
                    GlmOcrLiteRtModelPart.VISION -> VISION_FILE
                    GlmOcrLiteRtModelPart.DECODER -> DECODER_FILE
                },
            )
            val minimumBytes = when (part) {
                GlmOcrLiteRtModelPart.VISION -> MIN_VISION_BYTES
                GlmOcrLiteRtModelPart.DECODER -> MIN_DECODER_BYTES
            }
            val temporary = externalDirectory.resolve("${target.name}.importing")
            try {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temporary).buffered(COPY_BUFFER_BYTES).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("Could not open the selected LiteRT model.")
                require(temporary.length() >= minimumBytes && hasTfliteHeader(temporary)) {
                    "The selected file is not the expected GLM-OCR LiteRT model."
                }
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                temporary.delete()
            }
            state()
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
        private const val COPY_BUFFER_BYTES = 1_048_576
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
