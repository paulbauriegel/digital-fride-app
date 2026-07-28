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
