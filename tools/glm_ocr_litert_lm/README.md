# GLM-OCR decoder for LiteRT-LM

This directory implements decoder migration gates 3 through 5:

1. Reauthor the 16-layer float GLM-OCR text decoder with converter-friendly
   PyTorch operators and verify its fused-embedding prefill against the frozen
   MLX and Hugging Face traces.
2. Externalize all 16 KV caches, decode one token at a time, and compare each
   teacher-forced result against a full-context MLX recomputation.
3. Fuse the vision encoder's 1536-wide output into the image placeholder span,
   derive GLM's 3-axis positions and RoPE delta from the actual image grid, and
   pass that delta explicitly from multimodal prefill to cached decode.

The pixel-to-feature vision encoder remains a separate component. The integrated
prefill consumes its `[vision_tokens, 1536]` output instead of accepting the
already-fused golden tensor. The host layout builder turns `image_grid_thw` and
the prompt's contiguous image placeholder run into explicit `position_ids` and
`rope_delta` inputs. This keeps the cached decoder stateless across images with
different grids.

The implementation matches the model-specific details that the generic Qwen
decoder does not:

- four RMS norms per block;
- separate 2048-wide Q and 1024-wide K/V projections;
- fused `gate_up_proj`;
- GLM's even/odd rotary pairing;
- temporal/height/width M-RoPE sections `[16, 24, 24]`;
- external cache layout `[batch, sequence, 8, 128]`.
- explicit vision-feature prefill and dynamic RoPE-delta decode contracts.

## Verify on macOS

The official LiteRT Torch converter is Linux-only, but the authoring graph and
cache semantics can be checked locally using the existing OCR benchmark
environment.

Capture a bounded full-context MLX teacher trace:

```bash
cd benchmarks/ocr
uv run python ../../tools/glm_ocr_litert_lm/verify_glm_ocr_decoder.py \
  capture-mlx-full-context \
  --steps 8
```

Then run the vision-feature fusion, float prefill, and cached-decode gates:

```bash
cd benchmarks/ocr
uv run --extra pytorch python \
  ../../tools/glm_ocr_litert_lm/verify_glm_ocr_decoder.py verify \
  --steps 8 \
  --require-full-context
```

The report is stored at:

```text
benchmarks/ocr/reference/mlx-bf16/litert_lm_decoder_parity.json
```

The report checks feature fusion, positions, and delta before comparing decoder
logits and caches. Full-context logits are intentionally ignored with the rest
of `benchmarks/ocr/reference/`.

## Export on Linux

Use LiteRT Torch `v0.9.1`, the version against which these scripts were
authored:

For a reproducible macOS/Podman run, including the pinned Linux environment,
model revision, smoke validation, split exports, signature inspection, and
checksums, use:

```bash
model-assets/huggingface/convert-glm-ocr-litert.sh
```

Run the lower-level commands below only when developing the exporter itself.

```bash
python -m venv .venv
. .venv/bin/activate
pip install -r tools/glm_ocr_litert_lm/requirements-linux.txt

python tools/glm_ocr_litert_lm/export_glm_ocr_decoder.py \
  --output-dir build/glm-ocr-litert \
  --format litertlm \
  --quantize dynamic_int8 \
  --cache-length 1536 \
  --prefill-length 128 \
  --prefill-length 512 \
  --prefill-length 798
```

The lower-level command above produces
`glm_ocr_decoder_q8_ekv1536.litertlm`. Full-sequence prefill logits are omitted
for deployment; add `--output-prefill-logits` only for converter parity
debugging. `--quantize none` targets an FP32 package, but its approximately
2.51 GiB intermediate exceeds the normal FlatBuffer size boundary. The
reproducible wrapper uses dynamic INT8 and a stable
`glm_ocr_text_decoder_q8_ekv1536.litertlm` output name.

To reproduce only that text package using the pinned Podman environment:

```bash
model-assets/huggingface/convert-glm-ocr-litert.sh --text-only-litertlm
```

The successful Q8 package is 692,871,520 bytes and contains LiteRT-LM format
1.5 metadata, the compressed Hugging Face tokenizer, the GLM chat template,
and one 691,675,488-byte TFLite model with `prefill_128`, `prefill_512`,
`prefill_798`, and `decode` signatures. Inspect it with:

```bash
litert-lm-peek \
  --litertlm_file \
  build/glm-ocr-litert/glm_ocr_text_decoder_q8_ekv1536.litertlm
```

LiteRT-LM 0.14.0 loaded the package through its CPU/XNNPACK backend and
generated `OK` for the smoke prompt `Reply with only OK.`. That linux/amd64
Podman run proves runtime compatibility but is not a device-performance
measurement. The model remains text-only: it has one-dimensional RoPE with
decode delta zero and does not accept GLM-OCR vision features.

Export the stage-5 multimodal component separately:

```bash
python tools/glm_ocr_litert_lm/export_glm_ocr_multimodal_component.py \
  --output \
    build/glm-ocr-litert/glm_ocr_multimodal_decoder_combined_fp16.tflite \
  --dtype float16 \
  --signature-mode both \
  --lightweight-conversion \
  --runtime-constant-folding off \
  --sequence-length 798 \
  --image-token-start 5 \
  --image-token-count 782 \
  --cache-length 1536
```

It contains `prefill_798_vision_features` and `decode_with_rope_delta`
signatures. Prefill receives the vision features, `position_ids`, and
`rope_delta`; decode receives the same delta. The frozen stitched image happens
to produce `-736`, but that value is no longer baked into the decoder.

The successful local conversion used a dedicated rootless Podman machine:

```bash
podman machine init \
  --cpus 6 \
  --memory 16384 \
  --disk-size 80 \
  glm-ocr-litert
podman machine start glm-ocr-litert
```

The combined artifact is 1,530,554,748 bytes and contains only one shared copy
of the weights. On the successful 16 GiB conversion, the Python process peaked
at 6,102,576 KiB RSS, sampled cgroup usage peaked at 8,587,427,840 bytes, and
the cgroup recorded no OOM. `float16` means FP16 weight storage with FP32
embedding, activation, attention, and rotary computation; LiteRT 2.1 does not
legalize FP16 `EMBEDDING_LOOKUP` or `BATCH_MATMUL` for this graph.

This component is not yet a drop-in high-level LiteRT-LM vision bundle because
the pixel vision encoder is not packaged with it. It can be invoked as a
multi-signature LiteRT model, or packaged after the vision encoder becomes the
next component.

The mixed FP16-storage artifact is the current parity target. Quantization
should be introduced only after its LiteRT runtime output has been captured and
compared with the same report.
