package com.example.groceriesapp.vision.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter

class GlmOcrLiteRtReceiptRecognizer(
    context: Context,
    private val modelManager: GlmOcrLiteRtModelManager =
        GlmOcrLiteRtModelManager(context),
    private val visionBackend: GlmOcrLiteRtVisionBackend =
        GlmOcrLiteRtVisionBackend.CPU,
    private val gpuBackend: CompiledModel.GpuOptions.Backend =
        CompiledModel.GpuOptions.Backend.OPENCL,
    private val gpuBlockStart: Int = DEFAULT_GPU_BLOCK_START,
) : ReceiptOcrRecognizer {
    private val appContext = context.applicationContext

    init {
        require(gpuBlockStart in 0..VISION_BLOCK_COUNT)
    }

    override val inputSource: ReceiptOcrInputSource
        get() = ReceiptOcrInputSource.CURRENT_EDIT

    override suspend fun recognize(input: ReceiptOcrInput): ReceiptOcrResult =
        withContext(Dispatchers.Default) {
            val image = input.previewPath
                ?.let(::File)
                ?.takeIf(File::isFile)
                ?: input.tilePaths.singleOrNull()?.let(::File)?.takeIf(File::isFile)
                ?: error("GLM-OCR LiteRT needs a complete receipt preview image.")
            modelManager.requireReady()

            val pixelValues = preprocess(image)
            ensureActive()
            val visionFeatures = runVision(pixelValues)
            ensureActive()
            val tokenIds = runDecoder(visionFeatures)
            val text = GlmOcrByteLevelDecoder(appContext).decode(tokenIds).trim()
            check(text.isNotBlank()) { "GLM-OCR LiteRT returned no text." }
            ReceiptOcrResult(
                rawText = normalizeGlmOcrText(text),
                regions = emptyList(),
            )
        }

    internal fun encodeVisionForDiagnostics(image: File): FloatArray {
        modelManager.requireReady()
        return runVision(preprocess(image)).toFloatArray()
    }

    private fun runVision(pixelValues: ByteBuffer): ByteBuffer {
        val patchEmbeddings = runPatchEmbedCpu(pixelValues)
        val interpreter = Interpreter(
            modelManager.visionFile,
            Interpreter.Options().setNumThreads(INFERENCE_THREADS).setUseXNNPACK(true),
        )
        val compiledModel = when (visionBackend) {
            GlmOcrLiteRtVisionBackend.CPU -> null
            GlmOcrLiteRtVisionBackend.COMPILED_CPU ->
                CompiledModel.create(
                    modelManager.visionFile.absolutePath,
                    CompiledModel.Options(Accelerator.CPU),
                )
            GlmOcrLiteRtVisionBackend.GPU ->
                CompiledModel.create(
                    modelManager.visionFile.absolutePath,
                    gpuOptions(),
                )
        }
        try {
            var hiddenStates = patchEmbeddings
            repeat(VISION_BLOCK_COUNT) { block ->
                hiddenStates = if (
                    compiledModel != null &&
                    (
                        visionBackend == GlmOcrLiteRtVisionBackend.COMPILED_CPU ||
                            block >= gpuBlockStart
                    )
                ) {
                    runCompiledSignature(
                        model = compiledModel,
                        signatureIndex = VISION_BLOCK_SIGNATURE_START_INDEX + block,
                        input = hiddenStates,
                        outputFloatCount = PATCH_COUNT * PATCH_HIDDEN_SIZE,
                    )
                } else {
                    runCpuSignature(
                        interpreter = interpreter,
                        signature = visionBlockSignature(block),
                        input = hiddenStates,
                        outputFloatCount = PATCH_COUNT * PATCH_HIDDEN_SIZE,
                    )
                }
            }
            return runCpuSignature(
                interpreter = interpreter,
                signature = VISION_HEAD_SIGNATURE,
                input = hiddenStates,
                outputFloatCount = VISION_TOKEN_COUNT * HIDDEN_SIZE,
            )
        } finally {
            compiledModel?.close()
            interpreter.close()
        }
    }

    private fun runPatchEmbedCpu(pixelValues: ByteBuffer): ByteBuffer {
        val output = directFloatBuffer(PATCH_COUNT * PATCH_HIDDEN_SIZE)
        Interpreter(
            modelManager.visionFile,
            Interpreter.Options().setNumThreads(INFERENCE_THREADS).setUseXNNPACK(true),
        ).use { interpreter ->
            interpreter.runSignature(
                mapOf(interpreter.getSignatureInputs(PATCH_SIGNATURE).single() to pixelValues),
                mapOf(interpreter.getSignatureOutputs(PATCH_SIGNATURE).single() to output),
                PATCH_SIGNATURE,
            )
        }
        return output.rewound()
    }

    private fun gpuOptions(): CompiledModel.Options {
        val cacheDirectory = File(appContext.cacheDir, "litert_gpu").apply {
            check(isDirectory || mkdirs()) { "Could not create the LiteRT GPU cache." }
        }
        return CompiledModel.Options(Accelerator.GPU, Accelerator.CPU).apply {
            val cacheKey = buildString {
                append("fp32-buffer-blocks-")
                append(gpuBackend.name.lowercase())
                append('-')
                append(modelManager.visionFile.name)
                append('-')
                append(modelManager.visionFile.length())
                append('-')
                append(modelManager.visionFile.lastModified())
            }
            gpuOptions = if (gpuBackend == CompiledModel.GpuOptions.Backend.OPENCL) {
                CompiledModel.GpuOptions(
                    backend = gpuBackend,
                    precision = CompiledModel.GpuOptions.Precision.FP32,
                    bufferStorageType = CompiledModel.GpuOptions.BufferStorageType.BUFFER,
                    preferTextureWeights = false,
                    constantTensorSharing = true,
                    serializationDir = cacheDirectory.absolutePath,
                    modelCacheKey = cacheKey,
                    serializeProgramCache = true,
                )
            } else {
                CompiledModel.GpuOptions(
                    backend = gpuBackend,
                    precision = CompiledModel.GpuOptions.Precision.FP32,
                    serializationDir = cacheDirectory.absolutePath,
                    modelCacheKey = cacheKey,
                    serializeProgramCache = true,
                )
            }
        }
    }

    private fun runCompiledSignature(
        model: CompiledModel,
        signatureIndex: Int,
        input: ByteBuffer,
        outputFloatCount: Int,
    ): ByteBuffer {
        val inputs = model.createInputBuffers(signatureIndex)
        val outputs = model.createOutputBuffers(signatureIndex)
        check(inputs.size == 1 && outputs.size == 1) {
            "The LiteRT vision model has an unexpected input/output contract."
        }
        try {
            val inputBuffer = inputs.single()
            val outputBuffer = outputs.single()
            inputBuffer.writeFloat(input.toFloatArray())
            model.run(inputs, outputs, signatureIndex)
            return directFloatBuffer(outputFloatCount).apply {
                asFloatBuffer().put(outputBuffer.readFloat())
                rewind()
            }
        } finally {
            inputs.forEach(AutoCloseable::close)
            outputs.forEach(AutoCloseable::close)
        }
    }

    private fun runCpuSignature(
        interpreter: Interpreter,
        signature: String,
        input: ByteBuffer,
        outputFloatCount: Int,
    ): ByteBuffer {
        val output = directFloatBuffer(outputFloatCount)
        interpreter.runSignature(
            mapOf(interpreter.getSignatureInputs(signature).single() to input.rewound()),
            mapOf(interpreter.getSignatureOutputs(signature).single() to output),
            signature,
        )
        return output.rewound()
    }

    private fun visionBlockSignature(block: Int): String =
        "vision_block_${block.toString().padStart(2, '0')}_68x46"

    private suspend fun runDecoder(visionFeatures: ByteBuffer): List<Int> {
        val options = Interpreter.Options()
            .setNumThreads(INFERENCE_THREADS)
            .setUseXNNPACK(true)
        return Interpreter(modelManager.decoderFile, options).use { interpreter ->
            val caches = buildMap {
                repeat(LAYER_COUNT) { layer ->
                    put("kv_cache_k_$layer", directFloatBuffer(CACHE_FLOAT_COUNT))
                    put("kv_cache_v_$layer", directFloatBuffer(CACHE_FLOAT_COUNT))
                }
            }
            val ropeDelta = directIntBuffer(1).apply { putInt(ROPE_DELTA) }.rewound()
            val logits = directFloatBuffer(VOCAB_SIZE)
            val prefillInputs = HashMap<String, Any>(caches.size + 5).apply {
                putAll(caches)
                put("tokens", intBuffer(promptTokens()))
                put("input_pos", intBuffer(IntArray(PREFILL_LENGTH) { it }))
                put("vision_features", visionFeatures.rewound())
                put("position_ids", intBuffer(positionIds()))
                put("rope_delta", ropeDelta)
            }
            val prefillOutputs = HashMap<String, Any>(caches.size + 2).apply {
                putAll(caches)
                put("logits", logits)
                put("rope_delta", ropeDelta)
            }
            interpreter.runSignature(
                prefillInputs.rewoundValues(),
                prefillOutputs.rewoundValues(),
                PREFILL_SIGNATURE,
            )

            val generated = ArrayList<Int>(MAX_OUTPUT_TOKENS)
            var token = argmax(logits)
            Log.i(LOG_TAG, "Decoder first token=$token backend=$visionBackend")
            var position = PREFILL_LENGTH
            while (token !in STOP_TOKENS && generated.size < MAX_OUTPUT_TOKENS) {
                currentCoroutineContext().ensureActive()
                generated += token
                val decodeLogits = directFloatBuffer(VOCAB_SIZE)
                val decodeInputs = HashMap<String, Any>(caches.size + 3).apply {
                    putAll(caches)
                    put("tokens", intBuffer(intArrayOf(token)))
                    put("input_pos", intBuffer(intArrayOf(position)))
                    put("rope_delta", ropeDelta)
                }
                val decodeOutputs = HashMap<String, Any>(caches.size + 2).apply {
                    putAll(caches)
                    put("logits", decodeLogits)
                    put("rope_delta", ropeDelta)
                }
                interpreter.runSignature(
                    decodeInputs.rewoundValues(),
                    decodeOutputs.rewoundValues(),
                    DECODE_SIGNATURE,
                )
                token = argmax(decodeLogits)
                if (generated.size < TOKEN_LOG_LIMIT) {
                    Log.i(LOG_TAG, "Decoder token ${generated.size}=$token")
                }
                position += 1
            }
            Log.i(
                LOG_TAG,
                "Decoder completed tokens=${generated.size} stopToken=$token backend=$visionBackend",
            )
            generated
        }
    }

    private fun preprocess(image: File): ByteBuffer {
        check(OpenCVLoader.initLocal()) { "OpenCV could not initialize." }
        val bitmap = requireNotNull(BitmapFactory.decodeFile(image.absolutePath)) {
            "Could not decode the receipt preview."
        }
        val rgba = Mat()
        val rgb = Mat()
        val resized = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.resize(
                rgb,
                resized,
                Size(INPUT_WIDTH.toDouble(), INPUT_HEIGHT.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_CUBIC,
            )
            val pixels = ByteArray(INPUT_WIDTH * INPUT_HEIGHT * CHANNELS)
            resized.get(0, 0, pixels)
            return directFloatBuffer(PATCH_COUNT * PATCH_VECTOR_SIZE).apply {
                for (gridY in 0 until MERGED_GRID_HEIGHT) {
                    for (gridX in 0 until MERGED_GRID_WIDTH) {
                        for (mergeY in 0 until MERGE_SIZE) {
                            for (mergeX in 0 until MERGE_SIZE) {
                                for (channel in 0 until CHANNELS) {
                                    repeat(TEMPORAL_PATCH_SIZE) {
                                        for (patchY in 0 until PATCH_SIZE) {
                                            val y = (gridY * MERGE_SIZE + mergeY) *
                                                PATCH_SIZE + patchY
                                            for (patchX in 0 until PATCH_SIZE) {
                                                val x = (gridX * MERGE_SIZE + mergeX) *
                                                    PATCH_SIZE + patchX
                                                val raw = pixels[
                                                    (y * INPUT_WIDTH + x) * CHANNELS + channel
                                                ].toInt() and 0xff
                                                putFloat(
                                                    (raw / 255f - IMAGE_MEAN[channel]) /
                                                        IMAGE_STD[channel],
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                rewind()
            }
        } finally {
            bitmap.recycle()
            rgba.release()
            rgb.release()
            resized.release()
        }
    }

    private fun promptTokens(): IntArray =
        IntArray(PREFILL_LENGTH).also { tokens ->
            PROMPT_PREFIX.copyInto(tokens)
            tokens.fill(IMAGE_TOKEN, IMAGE_TOKEN_START, IMAGE_TOKEN_START + VISION_TOKEN_COUNT)
            PROMPT_SUFFIX.copyInto(tokens, IMAGE_TOKEN_START + VISION_TOKEN_COUNT)
        }

    private fun positionIds(): IntArray {
        val result = IntArray(3 * PREFILL_LENGTH)
        repeat(3) { axis ->
            for (index in 0 until IMAGE_TOKEN_START) {
                result[axis * PREFILL_LENGTH + index] = index
            }
        }
        var imageIndex = IMAGE_TOKEN_START
        for (height in 0 until MERGED_GRID_HEIGHT) {
            for (width in 0 until MERGED_GRID_WIDTH) {
                result[imageIndex] = IMAGE_TOKEN_START
                result[PREFILL_LENGTH + imageIndex] = IMAGE_TOKEN_START + height
                result[2 * PREFILL_LENGTH + imageIndex] = IMAGE_TOKEN_START + width
                imageIndex += 1
            }
        }
        repeat(3) { axis ->
            PROMPT_SUFFIX.indices.forEach { suffixIndex ->
                result[axis * PREFILL_LENGTH + imageIndex + suffixIndex] =
                    SUFFIX_POSITION_START + suffixIndex
            }
        }
        return result
    }

    private fun argmax(logits: ByteBuffer): Int {
        val values = logits.rewound().asFloatBuffer()
        var bestIndex = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (index in 0 until values.capacity()) {
            val value = values[index]
            if (value > bestValue) {
                bestValue = value
                bestIndex = index
            }
        }
        return bestIndex
    }

    private companion object {
        const val INPUT_WIDTH = 644
        const val INPUT_HEIGHT = 952
        const val CHANNELS = 3
        const val PATCH_SIZE = 14
        const val TEMPORAL_PATCH_SIZE = 2
        const val MERGE_SIZE = 2
        const val MERGED_GRID_HEIGHT = 34
        const val MERGED_GRID_WIDTH = 23
        const val PATCH_COUNT = 3_128
        const val PATCH_VECTOR_SIZE = 1_176
        const val PATCH_HIDDEN_SIZE = 1_024
        const val VISION_BLOCK_COUNT = 24
        const val VISION_TOKEN_COUNT = 782
        const val HIDDEN_SIZE = 1_536
        const val PREFILL_LENGTH = 798
        const val IMAGE_TOKEN_START = 5
        const val IMAGE_TOKEN = 59_280
        const val LAYER_COUNT = 16
        const val CACHE_FLOAT_COUNT = 1 * 1_536 * 8 * 128
        const val VOCAB_SIZE = 59_392
        const val INFERENCE_THREADS = 4
        const val ROPE_DELTA = -748
        const val SUFFIX_POSITION_START = 39
        const val MAX_OUTPUT_TOKENS = 512
        const val TOKEN_LOG_LIMIT = 8
        const val LOG_TAG = "GlmOcrLiteRt"
        const val PATCH_SIGNATURE = "patch_embed_68x46"
        const val VISION_BLOCK_SIGNATURE_START_INDEX = 1
        const val VISION_HEAD_SIGNATURE = "vision_head_68x46"
        const val DEFAULT_GPU_BLOCK_START = 23
        const val PREFILL_SIGNATURE = "prefill_798_vision_features"
        const val DECODE_SIGNATURE = "decode_with_rope_delta"
        val STOP_TOKENS = setOf(59_246, 59_253)
        val PROMPT_PREFIX = intArrayOf(59_248, 59_250, 59_253, 10, 59_256)
        val PROMPT_SUFFIX = intArrayOf(
            59_257,
            3_649,
            7_404,
            49_600,
            58,
            59_277,
            59_254,
            10,
            59_267,
            59_268,
            10,
        )
        val IMAGE_MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val IMAGE_STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }
}

enum class GlmOcrLiteRtVisionBackend {
    CPU,
    COMPILED_CPU,
    GPU,
}

private class GlmOcrByteLevelDecoder(context: Context) {
    private val vocab = context.assets.open("glm_ocr_vocab.b64")
        .bufferedReader()
        .useLines { lines ->
            lines.map { Base64.decode(it, Base64.DEFAULT).decodeToString() }.toList()
        }
    private val byteDecoder = buildByteDecoder()

    fun decode(ids: List<Int>): String {
        val bytes = ByteArrayOutputStream()
        ids.asSequence()
            .filter { it in vocab.indices && it < FIRST_SPECIAL_TOKEN }
            .map(vocab::get)
            .forEach { token ->
                token.codePoints().forEach { codePoint ->
                    byteDecoder[codePoint]?.let(bytes::write)
                }
            }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun buildByteDecoder(): Map<Int, Int> {
        val bytes = (33..126).toMutableList().apply {
            addAll(161..172)
            addAll(174..255)
        }
        val codePoints = bytes.toMutableList()
        var extra = 0
        for (byte in 0..255) {
            if (byte !in bytes) {
                bytes += byte
                codePoints += 256 + extra
                extra += 1
            }
        }
        return codePoints.zip(bytes).toMap()
    }

    private companion object {
        const val FIRST_SPECIAL_TOKEN = 59_246
    }
}

private fun directFloatBuffer(floatCount: Int): ByteBuffer =
    ByteBuffer.allocateDirect(floatCount * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())

private fun directIntBuffer(intCount: Int): ByteBuffer =
    ByteBuffer.allocateDirect(intCount * Int.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())

private fun intBuffer(values: IntArray): ByteBuffer =
    directIntBuffer(values.size).apply {
        values.forEach(::putInt)
        rewind()
    }

private fun ByteBuffer.rewound(): ByteBuffer = apply { rewind() }

private fun ByteBuffer.toFloatArray(): FloatArray {
    val values = duplicate()
        .order(ByteOrder.nativeOrder())
        .apply { rewind() }
        .asFloatBuffer()
    return FloatArray(values.remaining()).also(values::get)
}

private fun Map<String, Any>.rewoundValues(): Map<String, Any> =
    onEach { (_, value) -> (value as? ByteBuffer)?.rewind() }
