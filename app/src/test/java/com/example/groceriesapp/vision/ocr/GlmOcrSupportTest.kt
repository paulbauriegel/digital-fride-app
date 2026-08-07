package com.example.groceriesapp.vision.ocr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GlmOcrSupportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun unknownPreferenceFallsBackToMlKit() {
        assertEquals(OcrEngine.ML_KIT, OcrEngine.fromPreference(null))
        assertEquals(OcrEngine.ML_KIT, OcrEngine.fromPreference("unknown"))
        assertEquals(OcrEngine.GLM_OCR, OcrEngine.fromPreference("glm_ocr"))
        assertEquals(
            OcrEngine.GLM_OCR_LITERT,
            OcrEngine.fromPreference("glm_ocr_litert"),
        )
    }

    @Test
    fun outputNormalizationPreservesPlainTextAndRemovesMarkdownFence() {
        assertEquals(
            "HELLWEG\nGesamt 21,47 €",
            normalizeGlmOcrText("\nHELLWEG\r\nGesamt 21,47 €\n"),
        )
        assertEquals(
            "HELLWEG\nGesamt 21,47 €",
            normalizeGlmOcrText("```text\nHELLWEG\nGesamt 21,47 €\n```"),
        )
    }

    @Test
    fun ggufValidationChecksFileMagic() {
        val valid = File(temporaryFolder.root, "valid.gguf").apply {
            writeBytes(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), 1))
        }
        val invalid = File(temporaryFolder.root, "invalid.gguf").apply {
            writeText("not a model")
        }

        assertTrue(hasGgufHeader(valid))
        assertFalse(hasGgufHeader(invalid))
    }

    @Test
    fun vulkanCommandOffloadsDecoderAndProjector() {
        val command = commandFor(GlmOcrBackend.VULKAN)

        assertTrue(command.containsAll(listOf("--device", "Vulkan0", "--gpu-layers", "all")))
        assertTrue(command.contains("--mmproj-offload"))
        assertFalse(command.contains("--no-mmproj-offload"))
    }

    @Test
    fun cpuFallbackDisablesAllGpuOffload() {
        val command = commandFor(GlmOcrBackend.CPU)

        assertTrue(command.containsAll(listOf("--device", "none", "--gpu-layers", "0")))
        assertTrue(command.contains("--no-mmproj-offload"))
        assertFalse(command.contains("--mmproj-offload"))
    }

    private fun commandFor(backend: GlmOcrBackend): List<String> =
        buildGlmOcrCommand(
            runtime = File("/runtime"),
            decoder = File("/decoder.gguf"),
            projector = File("/projector.gguf"),
            image = File("/receipt.jpg"),
            threads = 4,
            backend = backend,
        )
}
