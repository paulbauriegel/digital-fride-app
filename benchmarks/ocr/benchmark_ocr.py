#!/usr/bin/env python3
"""Benchmark Tesseract and GLM-OCR on the same local receipt image."""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import platform
import shutil
import statistics
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable


SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_IMAGE = SCRIPT_DIR / "fixtures" / "hellweg_b3febdc3_edited_preview.jpg"
DEFAULT_MODEL = "mlx-community/GLM-OCR-bf16"
DEFAULT_PROMPT = "Text Recognition:"


@dataclass(frozen=True)
class TimedRuns:
    warmup_seconds: list[float]
    run_seconds: list[float]
    outputs: list[str]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run local Tesseract and GLM-OCR inference on one image and save "
            "raw outputs plus a JSON timing report."
        ),
    )
    parser.add_argument(
        "--image",
        type=Path,
        default=DEFAULT_IMAGE,
        help=f"Input image (default: {DEFAULT_IMAGE})",
    )
    parser.add_argument(
        "--engine",
        choices=("both", "tesseract", "glm"),
        default="both",
        help="Engine selection (default: both)",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        help="Result directory (default: benchmarks/ocr/results/<UTC timestamp>)",
    )
    parser.add_argument(
        "--warmups",
        type=non_negative_int,
        default=1,
        help="Unmeasured warm-up runs per engine (default: 1)",
    )
    parser.add_argument(
        "--runs",
        type=positive_int,
        default=3,
        help="Measured runs per engine (default: 3)",
    )
    parser.add_argument(
        "--tesseract-bin",
        default="tesseract",
        help="Tesseract executable name or path (default: tesseract)",
    )
    parser.add_argument(
        "--tesseract-languages",
        default="deu+eng",
        help="Tesseract language expression (default: deu+eng)",
    )
    parser.add_argument(
        "--tesseract-psm",
        type=non_negative_int,
        default=6,
        help="Tesseract page segmentation mode (default: 6)",
    )
    parser.add_argument(
        "--glm-model",
        default=DEFAULT_MODEL,
        help=f"MLX-VLM model ID or local path (default: {DEFAULT_MODEL})",
    )
    parser.add_argument(
        "--glm-prompt",
        default=DEFAULT_PROMPT,
        help=f"GLM-OCR task prompt (default: {DEFAULT_PROMPT!r})",
    )
    parser.add_argument(
        "--glm-max-tokens",
        type=positive_int,
        default=4096,
        help="Maximum generated GLM-OCR tokens (default: 4096)",
    )
    return parser.parse_args()


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be greater than zero")
    return parsed


def non_negative_int(value: str) -> int:
    parsed = int(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("must not be negative")
    return parsed


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_text(value: str) -> str:
    return sha256_bytes(value.encode("utf-8"))


def utc_timestamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def resolve_executable(command: str) -> str:
    explicit = Path(command).expanduser()
    if explicit.parent != Path("."):
        if not explicit.is_file():
            raise RuntimeError(f"Executable does not exist: {explicit}")
        return str(explicit.resolve())
    resolved = shutil.which(command)
    if resolved is None:
        raise RuntimeError(
            f"Could not find {command!r} on PATH. Install it or pass its path "
            "with --tesseract-bin.",
        )
    return resolved


def command_output(command: list[str]) -> str:
    completed = subprocess.run(
        command,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    return completed.stdout.strip()


def run_tesseract(
    executable: str,
    image: Path,
    languages: str,
    psm: int,
) -> str:
    completed = subprocess.run(
        [
            executable,
            str(image),
            "stdout",
            "-l",
            languages,
            "--psm",
            str(psm),
        ],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return completed.stdout.strip()


def load_glm(model_id: str) -> tuple[object, object, float]:
    if sys.version_info < (3, 10):
        raise RuntimeError(
            "GLM-OCR via MLX-VLM requires Python 3.10 or newer. "
            "Run this benchmark with `uv run --python 3.12`.",
        )
    try:
        from mlx_vlm import load
    except ImportError as error:
        raise RuntimeError(
            "mlx-vlm is not installed. From the repository root, run "
            "`uv run --project benchmarks/ocr --python 3.12 "
            "benchmarks/ocr/benchmark_ocr.py`.",
        ) from error

    started = time.perf_counter()
    model, processor = load(model_id)
    return model, processor, time.perf_counter() - started


def make_glm_runner(
    model: object,
    processor: object,
    image: Path,
    prompt: str,
    max_tokens: int,
) -> Callable[[], str]:
    from mlx_vlm import generate
    from mlx_vlm.prompt_utils import apply_chat_template

    formatted_prompt = apply_chat_template(
        processor,
        model.config,
        prompt,
        num_images=1,
    )

    def run() -> str:
        result = generate(
            model,
            processor,
            formatted_prompt,
            image=[str(image)],
            max_tokens=max_tokens,
            temperature=0.0,
            verbose=False,
        )
        text = result if isinstance(result, str) else getattr(result, "text", None)
        if not isinstance(text, str):
            raise RuntimeError(
                f"Unexpected MLX-VLM result type: {type(result).__name__}",
            )
        return text.strip()

    return run


def timed_runs(
    runner: Callable[[], str],
    warmups: int,
    runs: int,
    engine_name: str,
) -> TimedRuns:
    warmup_seconds: list[float] = []
    run_seconds: list[float] = []
    outputs: list[str] = []

    for index in range(warmups):
        print(f"[{engine_name}] warm-up {index + 1}/{warmups}", flush=True)
        started = time.perf_counter()
        runner()
        warmup_seconds.append(time.perf_counter() - started)

    for index in range(runs):
        print(f"[{engine_name}] measured run {index + 1}/{runs}", flush=True)
        started = time.perf_counter()
        outputs.append(runner())
        run_seconds.append(time.perf_counter() - started)

    return TimedRuns(
        warmup_seconds=warmup_seconds,
        run_seconds=run_seconds,
        outputs=outputs,
    )


def save_engine_results(
    output_dir: Path,
    engine_name: str,
    runs: TimedRuns,
    metadata: dict[str, object],
) -> dict[str, object]:
    engine_dir = output_dir / engine_name
    engine_dir.mkdir(parents=True, exist_ok=True)
    output_files: list[str] = []
    output_hashes: list[str] = []

    for index, output in enumerate(runs.outputs, start=1):
        path = engine_dir / f"run_{index:02d}.txt"
        path.write_text(output + "\n", encoding="utf-8")
        output_files.append(str(path.relative_to(output_dir)))
        output_hashes.append(sha256_text(output))

    return {
        **metadata,
        "warmup_seconds": runs.warmup_seconds,
        "run_seconds": runs.run_seconds,
        "median_run_seconds": statistics.median(runs.run_seconds),
        "minimum_run_seconds": min(runs.run_seconds),
        "output_files": output_files,
        "output_sha256": output_hashes,
        "outputs_stable": len(set(output_hashes)) == 1,
    }


def main() -> int:
    args = parse_args()
    image = args.image.expanduser().resolve()
    if not image.is_file():
        raise RuntimeError(f"Input image does not exist: {image}")

    output_dir = (
        args.output_dir.expanduser().resolve()
        if args.output_dir
        else SCRIPT_DIR / "results" / utc_timestamp()
    )
    output_dir.mkdir(parents=True, exist_ok=False)

    report: dict[str, object] = {
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "image": {
            "path": str(image),
            "size_bytes": image.stat().st_size,
            "sha256": sha256_bytes(image.read_bytes()),
        },
        "system": {
            "platform": platform.platform(),
            "machine": platform.machine(),
            "python": platform.python_version(),
        },
        "configuration": {
            "engine": args.engine,
            "warmups": args.warmups,
            "runs": args.runs,
        },
        "engines": {},
    }
    engines = report["engines"]
    assert isinstance(engines, dict)

    if args.engine in ("both", "tesseract"):
        tesseract_bin = resolve_executable(args.tesseract_bin)
        tesseract_version = command_output([tesseract_bin, "--version"]).splitlines()[0]
        tesseract_runner = lambda: run_tesseract(
            tesseract_bin,
            image,
            args.tesseract_languages,
            args.tesseract_psm,
        )
        tesseract_runs = timed_runs(
            tesseract_runner,
            args.warmups,
            args.runs,
            "tesseract",
        )
        engines["tesseract"] = save_engine_results(
            output_dir,
            "tesseract",
            tesseract_runs,
            {
                "version": tesseract_version,
                "executable": tesseract_bin,
                "languages": args.tesseract_languages,
                "page_segmentation_mode": args.tesseract_psm,
            },
        )

    if args.engine in ("both", "glm"):
        print(f"[glm] loading {args.glm_model}", flush=True)
        model, processor, load_seconds = load_glm(args.glm_model)
        glm_runner = make_glm_runner(
            model,
            processor,
            image,
            args.glm_prompt,
            args.glm_max_tokens,
        )
        glm_runs = timed_runs(glm_runner, args.warmups, args.runs, "glm")
        engines["glm"] = save_engine_results(
            output_dir,
            "glm",
            glm_runs,
            {
                "backend": "mlx-vlm",
                "backend_version": importlib.metadata.version("mlx-vlm"),
                "model": args.glm_model,
                "prompt": args.glm_prompt,
                "max_tokens": args.glm_max_tokens,
                "temperature": 0.0,
                "model_load_seconds": load_seconds,
            },
        )

    report_path = output_dir / "report.json"
    report_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Report: {report_path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
