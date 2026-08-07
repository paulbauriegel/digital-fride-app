package com.example.groceriesapp.vision.ocr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class GlmOcrModelPart {
    DECODER,
    PROJECTOR,
}

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
    private val metadata = appContext.getSharedPreferences(METADATA_PREFERENCES, Context.MODE_PRIVATE)

    val decoderFile: File
        get() = File(modelDirectory, DECODER_FILE)

    val projectorFile: File
        get() = File(modelDirectory, PROJECTOR_FILE)

    val runtimeExecutable: File
        get() = File(appContext.applicationInfo.nativeLibraryDir, RUNTIME_FILE)

    fun state(): GlmOcrModelState =
        GlmOcrModelState(
            decoderName = decoderFile.takeIf(File::isFile)?.let {
                metadata.getString(DECODER_NAME_KEY, null) ?: it.name
            },
            decoderSizeBytes = decoderFile.takeIf(File::isFile)?.length(),
            projectorName = projectorFile.takeIf(File::isFile)?.let {
                metadata.getString(PROJECTOR_NAME_KEY, null) ?: it.name
            },
            projectorSizeBytes = projectorFile.takeIf(File::isFile)?.length(),
            runtimeAvailable = runtimeExecutable.isFile && runtimeExecutable.canExecute(),
        )

    fun requireReady(): GlmOcrModelState =
        state().also { current ->
            check(current.runtimeAvailable) {
                "The bundled GLM-OCR runtime is unavailable on this device. GLM-OCR requires arm64."
            }
            check(current.modelsReady) {
                "Import both the GLM-OCR decoder and multimodal projector in Settings first."
            }
        }

    suspend fun import(part: GlmOcrModelPart, uri: Uri): GlmOcrModelState =
        withContext(Dispatchers.IO) {
            val displayName = queryDisplayName(uri) ?: "Imported GGUF"
            val declaredSize = querySize(uri)
            check(modelDirectory.mkdirs() || modelDirectory.isDirectory) {
                "Could not create the GLM-OCR model directory."
            }
            if (declaredSize != null) {
                require(declaredSize >= MIN_GGUF_BYTES) { "The selected GGUF file is too small." }
                require(modelDirectory.usableSpace >= declaredSize + MIN_FREE_SPACE_BYTES) {
                    "Not enough free storage to import this model."
                }
            }

            val target = when (part) {
                GlmOcrModelPart.DECODER -> decoderFile
                GlmOcrModelPart.PROJECTOR -> projectorFile
            }
            val temporary = File(modelDirectory, "${target.name}.importing")
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
                        output.flush()
                    }
                } ?: error("Could not open the selected model file.")
                require(temporary.length() >= MIN_GGUF_BYTES) {
                    "The selected GGUF file is too small."
                }
                require(hasGgufHeader(temporary)) {
                    "The selected file is not a GGUF model."
                }
                atomicReplace(temporary, target)
                metadata.edit {
                    putString(
                        when (part) {
                            GlmOcrModelPart.DECODER -> DECODER_NAME_KEY
                            GlmOcrModelPart.PROJECTOR -> PROJECTOR_NAME_KEY
                        },
                        displayName,
                    )
                }
            } finally {
                temporary.delete()
            }
            state()
        }

    private fun queryDisplayName(uri: Uri): String? =
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun querySize(uri: Uri): Long? =
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    private fun atomicReplace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private companion object {
        const val MODEL_DIRECTORY = "models/glm-ocr"
        const val DECODER_FILE = "GLM-OCR.gguf"
        const val PROJECTOR_FILE = "mmproj-GLM-OCR.gguf"
        const val RUNTIME_FILE = "libllama_mtmd_cli.so"
        const val METADATA_PREFERENCES = "glm_ocr_models"
        const val DECODER_NAME_KEY = "decoder_name"
        const val PROJECTOR_NAME_KEY = "projector_name"
        const val MIN_GGUF_BYTES = 1_048_576L
        const val MIN_FREE_SPACE_BYTES = 128L * 1_048_576L
        const val COPY_BUFFER_BYTES = 1_048_576
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
