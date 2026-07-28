# GLM-OCR to regular LiteRT

This is an experimental component exporter for the standard LiteRT `.tflite` runtime. It does not
use LiteRT-LM and does not produce a `.litertlm` package.

GLM-OCR is autoregressive and multimodal, so the useful regular-LiteRT boundary is a bundle of
fixed-shape graphs:

- `glm_ocr_vision.tflite` converts preprocessed image patches into image embeddings;
- `glm_ocr_embedder.tflite` converts token IDs into text embeddings;
- `glm_ocr_decoder.tflite` converts assembled image/text embeddings into next-token logits.

The exporter also writes the Hugging Face processor/tokenizer files and a `manifest.json` containing
the fixed image grid, prompt tokens, multimodal positions, tensor contracts, and special token IDs.

## Important baseline limitation

The decoder graph deliberately has no KV cache. The Android caller must update the generated token,
rerun the embedder, insert the vision embeddings at `image_token_indices`, build the causal additive
mask, and invoke the full decoder again for every output token. This is intended to establish
PyTorch/LiteRT numerical parity before implementing a faster cache-aware decoder.

The graphs are fixed to the calibration image's processed patch grid and the selected
`--sequence-length`. Runtime receipt images must be resized/padded so preprocessing produces that
same grid.

## Requirements

The official LiteRT Torch Converter supports Linux and Python 3.10 or newer. Native x86-64 Linux
with at least 32 GB RAM and ample temporary disk space is recommended. The unquantized component
bundle is expected to be large.

## Native Linux

```bash
python3.12 -m venv .venv
source .venv/bin/activate
pip install -r tools/glm_ocr_litert/requirements.txt

python tools/glm_ocr_litert/convert_glm_ocr_to_litert.py \
  --image benchmarks/ocr/fixtures/hellweg_b3febdc3_edited_preview.jpg \
  --output-dir tools/glm_ocr_litert/output/hellweg-fp16 \
  --sequence-length 1536
```

Use `--component vision`, `--component embedder`, or `--component decoder` to isolate conversion
failures or reduce peak memory.

## Docker from macOS

The converter is Linux-only. The included image can run under Docker's `linux/amd64` emulation, but
native Linux is substantially faster and less memory constrained:

```bash
docker build --platform linux/amd64 \
  -t groceries-glm-ocr-litert \
  -f tools/glm_ocr_litert/Dockerfile .

docker run --rm --platform linux/amd64 \
  -v "$PWD:/workspace" \
  -v "$HOME/.cache/huggingface:/root/.cache/huggingface" \
  groceries-glm-ocr-litert \
  --image /workspace/benchmarks/ocr/fixtures/hellweg_b3febdc3_edited_preview.jpg \
  --output-dir /workspace/tools/glm_ocr_litert/output/hellweg-fp16
```

Do not treat successful FlatBuffer creation as validation. Before app integration, compare the
vision embeddings and next-token logits between PyTorch and LiteRT, then run an end-to-end greedy
decode comparison on the calibration receipt.
