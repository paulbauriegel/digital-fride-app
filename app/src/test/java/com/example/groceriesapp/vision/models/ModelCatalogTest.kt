package com.example.groceriesapp.vision.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class ModelCatalogTest {
    private fun catalog(destDir: String = "models/granite", format: Int = 1) = """
        {"formatVersion": $format, "models": [
          {"id": "granite", "name": "Granite", "license": "Apache-2.0", "source": "https://hf.co/x",
           "files": [{"url": "https://hf.co/x/resolve/abc/g.litertlm", "sha256": "ABC", "bytes": 42,
                      "destDir": "$destDir", "destName": "g.litertlm"}]},
          {"id": "future_model", "name": "Not known to this app", "files": []}
        ]}
    """.trimIndent()

    @Test
    fun `parses known models and skips unknown ones`() {
        val parsed = ModelCatalog.parse(catalog())
        assertEquals(1, parsed.models.size)
        val spec = parsed.spec(DownloadableModel.GRANITE)!!
        assertEquals(42L, spec.totalBytes)
        assertEquals("abc", spec.files.single().sha256)
        assertNull(parsed.spec(DownloadableModel.GLM_OCR))
    }

    @Test
    fun `rejects paths that escape the app directory`() {
        assertThrows(IllegalArgumentException::class.java) { ModelCatalog.parse(catalog(destDir = "../shared_prefs")) }
        assertThrows(IllegalArgumentException::class.java) { ModelCatalog.parse(catalog(destDir = "/sdcard")) }
    }

    @Test
    fun `rejects a newer catalog format`() {
        assertThrows(IllegalArgumentException::class.java) { ModelCatalog.parse(catalog(format = 99)) }
    }

    @Test
    fun `bundled catalog matches what the model managers expect`() {
        val asset = listOf("src/main/assets/model_catalog.json", "app/src/main/assets/model_catalog.json")
            .map(::File).first { it.isFile }
        val parsed = ModelCatalog.parse(asset.readText())
        val paths = DownloadableModel.entries.associateWith { m -> parsed.spec(m)!!.files.map { "${it.destDir}/${it.destName}" } }
        assertEquals(listOf("models/glm-ocr/GLM-OCR.gguf", "models/glm-ocr/mmproj-GLM-OCR.gguf"), paths[DownloadableModel.GLM_OCR])
        assertEquals(
            listOf(
                "models/glm-ocr-litert/glm_ocr_vision_68x46_fp32.tflite",
                "models/glm-ocr-litert/glm_ocr_multimodal_decoder_combined_fp16.tflite",
            ),
            paths[DownloadableModel.GLM_OCR_LITERT],
        )
        assertEquals(listOf("models/granite/granite-4.0-350m_q8_ekv1280.litertlm"), paths[DownloadableModel.GRANITE])
        parsed.models.flatMap { it.files }.forEach { assertEquals(64, it.sha256.length) }
    }
}
