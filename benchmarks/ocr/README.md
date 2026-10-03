# Receipt OCR benchmark

`benchmark_ocr.py` runs Tesseract and GLM-OCR against the exact same local receipt image. It does
not upload the receipt to Hugging Face or another service.

## Run both engines

From the repository root:

```bash
uv run --project benchmarks/ocr --python 3.12 benchmarks/ocr/benchmark_ocr.py
```

The first GLM-OCR run downloads `mlx-community/GLM-OCR-bf16` from Hugging Face. Later runs reuse
the local model cache. MLX-VLM uses Metal on Apple Silicon.

Each timestamped directory under `benchmarks/ocr/results/` contains:

- every measured raw OCR output;
- warm-up and measured inference durations;
- the Tesseract executable and version;
- the MLX-VLM version, GLM model ID, prompt, and model-load duration;
- input and output SHA-256 hashes.

Generated result directories are ignored by Git.

## Fixtures and the saved ML Kit reference

Receipt photos are personal data, so they are not in Git. Put them into the ignored
`benchmarks/ocr/private/` folder. The scripts default to
`private/hellweg_b3febdc3_edited_preview.jpg`; pass another image on the command line otherwise.

The saved ML Kit output previously produced on the Android phone for the same receipt lives next to
it, under `benchmarks/ocr/private/references/mlkit/hellweg_b3febdc3_edited_preview/`:

- `input.jpg` is the exact original stitched tile passed to ML Kit.
- `raw.txt` is the saved OCR text.
- `report.json` records the ML Kit dependency, receipt and device provenance, input hashes, region
  count, and mean region confidence.

The default fixture is a later rotated and cropped edit of the same receipt. Compare against
`input.jpg` for strict same-pixel accuracy, or against the default fixture only as a same-receipt
quality reference. The app did not retain inference timing for that run, so the reference
deliberately contains no timing estimate.

## Useful options

Run only the already-installed Tesseract without creating the MLX environment:

```bash
python3 benchmarks/ocr/benchmark_ocr.py --engine tesseract
```

Use a locally built Tesseract release:

```bash
uv run --project benchmarks/ocr --python 3.12 benchmarks/ocr/benchmark_ocr.py \
  --tesseract-bin /absolute/path/to/tesseract
```

Select another image or change the sample counts:

```bash
uv run --project benchmarks/ocr --python 3.12 benchmarks/ocr/benchmark_ocr.py \
  --image /absolute/path/to/receipt.jpg \
  --warmups 1 \
  --runs 5
```

The default Tesseract configuration is `deu+eng` with page segmentation mode 6. The GLM-OCR
configuration follows the model's documented text-recognition path: deterministic generation with
the `Text Recognition:` prompt and a 4096-token output limit.

## GLM-OCR MLX/PyTorch parity traces

`glm_ocr_parity.py` captures component-level tensors, the prefill KV cache, greedy decode tokens,
and final text. Large trace directories are written below `benchmarks/ocr/reference/` and ignored
by Git.

Capture the MLX BF16 golden trace:

```bash
uv run --project benchmarks/ocr --python 3.12 \
  benchmarks/ocr/glm_ocr_parity.py capture-mlx \
  --output-dir benchmarks/ocr/reference/mlx-bf16
```

Capture the official Hugging Face PyTorch BF16 trace:

```bash
uv run --project benchmarks/ocr --extra pytorch --python 3.12 \
  benchmarks/ocr/glm_ocr_parity.py capture-hf \
  --output-dir benchmarks/ocr/reference/hf-bf16
```

Compare the component tensors, KV cache, greedy token sequence, and final text:

```bash
uv run --project benchmarks/ocr --python 3.12 \
  benchmarks/ocr/glm_ocr_parity.py compare \
  --reference benchmarks/ocr/reference/mlx-bf16 \
  --candidate benchmarks/ocr/reference/hf-bf16
```

Floating-point arrays are stored as float32 so NumPy can represent MLX and PyTorch BF16 values in a
common format. The metadata preserves backend versions, model identifiers, revisions, input hashes,
tensor shapes, statistics, and hashes.

These traces are correctness artifacts, not performance benchmarks. The Hugging Face capture runs
an explicit component prefill and then calls `generate()` separately so its official cache and
multimodal position path can be checked. BF16 greedy output can also diverge slightly across
frameworks, so the comparison reports tensor errors, token edit distance, and character error rate
instead of requiring byte-identical text.

## LiteRT-LM decoder migration

The float decoder reauthoring, external KV-cache path, MLX full-context parity harness, and Linux
exporter live under `tools/glm_ocr_litert_lm/`. See that directory's README for the exact capture,
verification, and export commands. The current Android model uses separate patch, 24 vision-block,
and vision-head signatures so CPU/GPU parity can be isolated per block. The production LiteRT
engine uses CPU because the Pixel 7 Pro OpenCL runs diverged from the CPU vision embeddings; the
`litert_gpu` Android benchmark engine remains available for diagnostics.
