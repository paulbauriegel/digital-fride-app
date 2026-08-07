package com.example.groceriesapp.vision.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.google.ai.edge.litert.CompiledModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.tensorflow.lite.Interpreter

@RunWith(AndroidJUnit4::class)
class GlmOcrLiteRtOnDeviceTest {
    @Test
    fun captureVisionOutputForSelectedBackend() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        val image = File(requireNotNull(arguments.getString(ARG_IMAGE_PATH)) {
            "Pass -e $ARG_IMAGE_PATH with an app-readable receipt image path."
        })
        val backend = GlmOcrLiteRtVisionBackend.valueOf(
            (arguments.getString(ARG_VISION_BACKEND) ?: "GPU").uppercase(),
        )
        val gpuBackend = CompiledModel.GpuOptions.Backend.valueOf(
            (arguments.getString(ARG_GPU_BACKEND) ?: "OPENCL").uppercase(),
        )
        val gpuBlockStart = arguments.getString(ARG_GPU_BLOCK_START)?.toInt() ?: 23
        val values = GlmOcrLiteRtReceiptRecognizer(
            context = context,
            visionBackend = backend,
            gpuBackend = gpuBackend,
            gpuBlockStart = gpuBlockStart,
        ).encodeVisionForDiagnostics(image)
        val output = File(
            context.cacheDir,
            "glm_ocr_vision_${backend.name.lowercase()}_" +
                "${gpuBackend.name.lowercase()}_blocks_$gpuBlockStart.f32",
        )
        val bytes = ByteBuffer.allocate(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { asFloatBuffer().put(values) }
            .array()
        output.writeBytes(bytes)
        instrumentation.sendStatus(
            0,
            android.os.Bundle().apply {
                putString("vision_output", output.absolutePath)
                putInt("vision_float_count", values.size)
            },
        )
    }

    @Test
    fun preprocessingNativeLibraryIsLoadable() {
        assertTrue(OpenCVLoader.initLocal())
        val mat = Mat()
        try {
            assertTrue(mat.empty())
        } finally {
            mat.release()
        }
    }

    @Test
    fun modelsCompileForLiteRtGpu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = GlmOcrLiteRtModelManager(context)
        assertTrue(manager.state().ready)

        assertEquals(
            "gpuCompiled=true",
            GlmOcrLiteRtGpuProbe.probe(manager.visionFile.absolutePath),
        )
        assertEquals(
            "gpuCompiled=true",
            GlmOcrLiteRtGpuProbe.probe(manager.decoderFile.absolutePath),
        )
    }

    @Test
    fun modelsAreOwnedAndLoadableByLiteRt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = GlmOcrLiteRtModelManager(context)
        assertTrue(manager.state().ready)

        Interpreter(manager.visionFile, Interpreter.Options().setNumThreads(1)).use {
            val expected = buildList {
                add("patch_embed_68x46")
                repeat(24) { block ->
                    add("vision_block_${block.toString().padStart(2, '0')}_68x46")
                }
                add("vision_head_68x46")
            }
            assertArrayEquals(expected.sorted().toTypedArray(), it.signatureKeys.sortedArray())
        }
        Interpreter(manager.decoderFile, Interpreter.Options().setNumThreads(1)).use {
            assertArrayEquals(
                arrayOf("decode_with_rope_delta", "prefill_798_vision_features"),
                it.signatureKeys.sortedArray(),
            )
        }
    }

    private companion object {
        const val ARG_IMAGE_PATH = "imagePath"
        const val ARG_VISION_BACKEND = "visionBackend"
        const val ARG_GPU_BACKEND = "gpuBackend"
        const val ARG_GPU_BLOCK_START = "gpuBlockStart"
    }
}
