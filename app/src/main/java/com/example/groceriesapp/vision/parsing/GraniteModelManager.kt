package com.example.groceriesapp.vision.parsing

import android.content.Context
import java.io.File
import java.io.IOException

data class GraniteModelState(
    val modelName: String?,
    val modelSizeBytes: Long?,
) {
    val ready: Boolean
        get() = modelSizeBytes != null
}

class GraniteModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val modelDirectory = File(appContext.filesDir, MODEL_DIRECTORY)

    val modelFile: File
        get() = File(modelDirectory, MODEL_FILE)

    fun state(): GraniteModelState =
        GraniteModelState(
            modelName = validModel(modelFile)?.name,
            modelSizeBytes = validModel(modelFile)?.length(),
        )

    fun requireReady(): GraniteModelState =
        state().also { current ->
            check(current.ready) {
                "The Granite model is not installed on this device."
            }
        }

    private fun validModel(file: File): File? =
        file.takeIf {
            it.isFile && it.length() >= MIN_MODEL_BYTES && hasLitertLmHeader(it)
        }

    companion object {
        const val MODEL_DIRECTORY = "models/granite"
        const val MODEL_FILE = "granite-4.0-350m_q8_ekv1280.litertlm"
        const val MIN_MODEL_BYTES = 100_000_000L
    }
}

internal fun hasLitertLmHeader(file: File): Boolean =
    try {
        file.inputStream().use { input ->
            val header = ByteArray(8)
            input.read(header) == header.size &&
                header.contentEquals("LITERTLM".toByteArray(Charsets.US_ASCII))
        }
    } catch (_: IOException) {
        false
    }
