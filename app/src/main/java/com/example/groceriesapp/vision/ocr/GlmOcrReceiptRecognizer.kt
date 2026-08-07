package com.example.groceriesapp.vision.ocr

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

class GlmOcrReceiptRecognizer(
    context: Context,
    private val modelManager: GlmOcrModelManager = GlmOcrModelManager(context),
) : ReceiptOcrRecognizer {
    private val appContext = context.applicationContext

    override val inputSource: ReceiptOcrInputSource
        get() = ReceiptOcrInputSource.CURRENT_EDIT

    override suspend fun recognize(input: ReceiptOcrInput): ReceiptOcrResult {
        val image = input.previewPath
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?: input.tilePaths.singleOrNull()?.let(::File)?.takeIf(File::isFile)
            ?: error("GLM-OCR needs a complete receipt preview image.")
        val state = modelManager.requireReady()
        check(state.ready)
        val rawText = runInference(image)
        check(rawText.isNotBlank()) { "GLM-OCR returned no text." }
        return ReceiptOcrResult(
            rawText = normalizeGlmOcrText(rawText),
            regions = emptyList(),
        )
    }

    private suspend fun runInference(image: File): String =
        try {
            runInference(image, GlmOcrBackend.VULKAN)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (vulkanFailure: Exception) {
            Log.w(TAG, "GLM-OCR Vulkan inference failed; retrying on CPU.", vulkanFailure)
            try {
                runInference(image, GlmOcrBackend.CPU)
            } catch (cpuFailure: Exception) {
                cpuFailure.addSuppressed(vulkanFailure)
                throw cpuFailure
            }
        }

    private suspend fun runInference(
        image: File,
        backend: GlmOcrBackend,
    ): String = coroutineScope {
        val runtime = modelManager.runtimeExecutable
        val nativeDirectory = appContext.applicationInfo.nativeLibraryDir
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS)
        val command = buildGlmOcrCommand(
            runtime = runtime,
            decoder = modelManager.decoderFile,
            projector = modelManager.projectorFile,
            image = image,
            threads = threads,
            backend = backend,
        )
        val process = ProcessBuilder(command)
            .directory(appContext.cacheDir)
            .apply {
                environment()["LD_LIBRARY_PATH"] = nativeDirectory
            }
            .start()
        val stdout = async(Dispatchers.IO) {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        val stderr = async(Dispatchers.IO) {
            process.errorStream.bufferedReader().use { it.readText() }
        }
        try {
            val exitCode = withTimeout(INFERENCE_TIMEOUT_MILLIS) {
                runInterruptible(Dispatchers.IO) { process.waitFor() }
            }
            val output = stdout.await()
            val diagnostics = stderr.await()
            check(exitCode == 0) {
                diagnostics.trim().takeLast(MAX_ERROR_CHARACTERS)
                    .ifBlank { "GLM-OCR exited with code $exitCode." }
            }
            output
        } finally {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            }
        }
    }

    private companion object {
        const val TAG = "GlmOcrRecognizer"
        const val MAX_THREADS = 4
        const val INFERENCE_TIMEOUT_MILLIS = 15L * 60L * 1_000L
        const val PROCESS_STOP_TIMEOUT_SECONDS = 2L
        const val MAX_ERROR_CHARACTERS = 2_000
    }
}

internal enum class GlmOcrBackend {
    VULKAN,
    CPU,
}

internal fun buildGlmOcrCommand(
    runtime: File,
    decoder: File,
    projector: File,
    image: File,
    threads: Int,
    backend: GlmOcrBackend,
): List<String> = buildList {
    addAll(
        listOf(
            runtime.absolutePath,
            "--model",
            decoder.absolutePath,
            "--mmproj",
            projector.absolutePath,
            "--image",
            image.absolutePath,
            "--prompt",
            "Text Recognition:",
            "--ctx-size",
            "4096",
            "--n-predict",
            "2048",
            "--threads",
            threads.toString(),
            "--threads-batch",
            threads.toString(),
            "--temperature",
            "0",
            "--no-warmup",
            "--flash-attn",
            "off",
            "--fit",
            "off",
        ),
    )
    when (backend) {
        GlmOcrBackend.VULKAN -> addAll(
            listOf(
                "--device",
                "Vulkan0",
                "--gpu-layers",
                "all",
                "--mmproj-offload",
            ),
        )

        GlmOcrBackend.CPU -> addAll(
            listOf(
                "--device",
                "none",
                "--gpu-layers",
                "0",
                "--no-mmproj-offload",
            ),
        )
    }
    addAll(listOf("--offline", "--log-colors", "off"))
}

internal fun normalizeGlmOcrText(value: String): String {
    val normalized = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
    if (!normalized.startsWith("```") || !normalized.endsWith("```")) return normalized
    return normalized.lines()
        .drop(1)
        .dropLast(1)
        .joinToString("\n")
        .trim()
}
