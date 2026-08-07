package com.example.groceriesapp.vision.ocr

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel

internal object GlmOcrLiteRtGpuProbe {
    fun probe(modelPath: String): String {
        val options = CompiledModel.Options(Accelerator.GPU, Accelerator.CPU).apply {
            gpuOptions = CompiledModel.GpuOptions(
                backend = CompiledModel.GpuOptions.Backend.OPENCL,
            )
        }
        CompiledModel.create(modelPath, options).use {
            return "gpuCompiled=true"
        }
    }
}
