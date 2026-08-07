package com.example.groceriesapp.vision.ocr

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDeviceOcrBenchmarkTest {
    @Test
    fun benchmarkProductionRecognizers() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        val image = File(requireNotNull(arguments.getString(ARG_IMAGE_PATH)) {
            "Pass -e $ARG_IMAGE_PATH with an app-readable receipt image path."
        })
        require(image.isFile) { "Benchmark image does not exist: $image" }
        val warmups = arguments.getString(ARG_WARMUPS)?.toInt() ?: DEFAULT_WARMUPS
        val runs = arguments.getString(ARG_RUNS)?.toInt() ?: DEFAULT_RUNS
        val selectedEngine = arguments.getString(ARG_ENGINE) ?: ENGINE_BOTH
        require(warmups >= 0)
        require(runs > 0)
        require(
            selectedEngine in setOf(
                ENGINE_BOTH,
                ENGINE_MLKIT,
                ENGINE_LLAMA_CPP,
                ENGINE_LITERT_CPU,
                ENGINE_LITERT_GPU,
            ),
        )

        val input = ReceiptOcrInput(
            tilePaths = listOf(image.absolutePath),
            previewPath = image.absolutePath,
        )
        val engines = JSONObject()
        val report = JSONObject()
            .put("image_path", image.absolutePath)
            .put("image_size_bytes", image.length())
            .put("warmups", warmups)
            .put("runs", runs)
            .put("selected_engine", selectedEngine)
            .put("engines", engines)
        val output = File(context.cacheDir, OUTPUT_FILE)
        if (selectedEngine == ENGINE_BOTH || selectedEngine == ENGINE_MLKIT) {
            engines.put(
                ENGINE_MLKIT,
                benchmark(
                    recognizer = MlKitReceiptOcrRecognizer(),
                    input = input,
                    warmups = warmups,
                    runs = runs,
                ),
            )
            output.writeText(report.toString(2))
        }
        if (selectedEngine == ENGINE_BOTH || selectedEngine == ENGINE_LLAMA_CPP) {
            engines.put(
                ENGINE_LLAMA_CPP,
                benchmark(
                    recognizer = GlmOcrReceiptRecognizer(context),
                    input = input,
                    warmups = warmups,
                    runs = runs,
                ),
            )
            output.writeText(report.toString(2))
        }
        if (selectedEngine == ENGINE_LITERT_CPU || selectedEngine == ENGINE_LITERT_GPU) {
            val backend = if (selectedEngine == ENGINE_LITERT_GPU) {
                GlmOcrLiteRtVisionBackend.GPU
            } else {
                GlmOcrLiteRtVisionBackend.CPU
            }
            engines.put(
                selectedEngine,
                benchmark(
                    recognizer = GlmOcrLiteRtReceiptRecognizer(
                        context = context,
                        visionBackend = backend,
                    ),
                    input = input,
                    warmups = warmups,
                    runs = runs,
                ),
            )
            output.writeText(report.toString(2))
        }
        instrumentation.sendStatus(
            0,
            android.os.Bundle().apply {
                putString("benchmark_output", output.absolutePath)
            },
        )
    }

    private suspend fun benchmark(
        recognizer: ReceiptOcrRecognizer,
        input: ReceiptOcrInput,
        warmups: Int,
        runs: Int,
    ): JSONObject {
        val warmupSeconds = JSONArray()
        repeat(warmups) {
            val (_, seconds) = timedRecognize(recognizer, input)
            warmupSeconds.put(seconds)
        }
        val runSeconds = JSONArray()
        val outputs = JSONArray()
        repeat(runs) {
            val (result, seconds) = timedRecognize(recognizer, input)
            runSeconds.put(seconds)
            outputs.put(result.rawText)
        }
        return JSONObject()
            .put("warmup_seconds", warmupSeconds)
            .put("run_seconds", runSeconds)
            .put("outputs", outputs)
    }

    private suspend fun timedRecognize(
        recognizer: ReceiptOcrRecognizer,
        input: ReceiptOcrInput,
    ): Pair<ReceiptOcrResult, Double> {
        val started = SystemClock.elapsedRealtimeNanos()
        val result = recognizer.recognize(input)
        val elapsedSeconds = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000_000.0
        return result to elapsedSeconds
    }

    private companion object {
        const val ARG_IMAGE_PATH = "imagePath"
        const val ARG_ENGINE = "engine"
        const val ARG_WARMUPS = "warmups"
        const val ARG_RUNS = "runs"
        const val ENGINE_BOTH = "both"
        const val ENGINE_MLKIT = "mlkit"
        const val ENGINE_LLAMA_CPP = "llama_cpp"
        const val ENGINE_LITERT_CPU = "litert_cpu"
        const val ENGINE_LITERT_GPU = "litert_gpu"
        const val DEFAULT_WARMUPS = 1
        const val DEFAULT_RUNS = 3
        const val OUTPUT_FILE = "on_device_ocr_benchmark.json"
    }
}
