#!/usr/bin/env python3
"""Capture and compare GLM-OCR MLX and Hugging Face PyTorch traces."""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import math
import os
import platform
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

import numpy as np


SCRIPT_DIR = Path(__file__).resolve().parent
# Personal receipt photos stay out of Git: put fixtures into the ignored private/ folder (see README).
DEFAULT_IMAGE = SCRIPT_DIR / "private" / "hellweg_b3febdc3_edited_preview.jpg"
DEFAULT_OUTPUT_ROOT = SCRIPT_DIR / "reference"
DEFAULT_MLX_MODEL = "mlx-community/GLM-OCR-bf16"
DEFAULT_HF_MODEL = "zai-org/GLM-OCR"
DEFAULT_PROMPT = "Text Recognition:"
TRACE_FORMAT_VERSION = 1


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Capture greedy GLM-OCR component traces and compare backends.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    for name in ("capture-mlx", "capture-hf"):
        capture = subparsers.add_parser(name)
        capture.add_argument("--image", type=Path, default=DEFAULT_IMAGE)
        capture.add_argument("--prompt", default=DEFAULT_PROMPT)
        capture.add_argument("--max-tokens", type=positive_int, default=4096)
        capture.add_argument("--top-k", type=positive_int, default=10)
        capture.add_argument("--output-dir", type=Path)
        capture.add_argument("--revision")

    subparsers.choices["capture-mlx"].add_argument(
        "--model",
        default=DEFAULT_MLX_MODEL,
    )
    hf = subparsers.choices["capture-hf"]
    hf.add_argument("--model", default=DEFAULT_HF_MODEL)
    hf.add_argument(
        "--device",
        choices=("auto", "mps", "cpu"),
        default="auto",
        help="PyTorch device (default: MPS when available)",
    )

    compare = subparsers.add_parser("compare")
    compare.add_argument("--reference", type=Path, required=True)
    compare.add_argument("--candidate", type=Path, required=True)
    compare.add_argument("--output", type=Path)

    return parser.parse_args()


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be greater than zero")
    return parsed


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_array(value: np.ndarray) -> str:
    contiguous = np.ascontiguousarray(value)
    digest = hashlib.sha256()
    digest.update(str(contiguous.dtype).encode("ascii"))
    digest.update(json.dumps(list(contiguous.shape)).encode("ascii"))
    digest.update(contiguous.tobytes())
    return digest.hexdigest()


def package_version(name: str) -> str | None:
    try:
        return importlib.metadata.version(name)
    except importlib.metadata.PackageNotFoundError:
        return None


def commit_from_snapshot_path(path: str | Path | None) -> str | None:
    if path is None:
        return None
    parts = Path(path).resolve().parts
    try:
        index = parts.index("snapshots")
    except ValueError:
        return None
    return parts[index + 1] if index + 1 < len(parts) else None


def cached_hugging_face_revision(model: str, requested: str | None) -> str | None:
    cache_root = Path(
        os.environ.get(
            "HUGGINGFACE_HUB_CACHE",
            Path.home() / ".cache" / "huggingface" / "hub",
        ),
    )
    repository = cache_root / f"models--{model.replace('/', '--')}"
    ref = repository / "refs" / (requested or "main")
    if ref.is_file():
        value = ref.read_text(encoding="utf-8").strip()
        return value or None
    snapshots = repository / "snapshots"
    if snapshots.is_dir():
        candidates = sorted(path.name for path in snapshots.iterdir() if path.is_dir())
        if len(candidates) == 1:
            return candidates[0]
    return None


def canonical_float_array(value: Any) -> np.ndarray:
    array = np.asarray(value)
    if np.issubdtype(array.dtype, np.floating):
        return array.astype(np.float32, copy=False)
    return array


def tensor_summary(value: np.ndarray) -> dict[str, Any]:
    array = np.asarray(value)
    summary: dict[str, Any] = {
        "shape": list(array.shape),
        "dtype": str(array.dtype),
        "sha256": sha256_array(array),
    }
    if np.issubdtype(array.dtype, np.number) and array.size:
        numeric = array.astype(np.float64, copy=False)
        summary.update(
            {
                "minimum": float(numeric.min()),
                "maximum": float(numeric.max()),
                "mean": float(numeric.mean()),
            },
        )
    return summary


def save_npz(path: Path, arrays: dict[str, np.ndarray]) -> dict[str, Any]:
    normalized = {name: canonical_float_array(value) for name, value in arrays.items()}
    np.savez_compressed(path, **normalized)
    return {name: tensor_summary(value) for name, value in normalized.items()}


def topk_entries(logits: np.ndarray, count: int) -> list[dict[str, float | int]]:
    flat = np.asarray(logits, dtype=np.float32).reshape(-1)
    count = min(count, flat.size)
    indices = np.argpartition(flat, -count)[-count:]
    indices = indices[np.argsort(flat[indices])[::-1]]
    return [
        {"token_id": int(index), "logit": float(flat[index])}
        for index in indices
    ]


def prepare_output_dir(explicit: Path | None, backend: str) -> Path:
    if explicit is not None:
        output = explicit.expanduser().resolve()
    else:
        output = DEFAULT_OUTPUT_ROOT / backend
    output.mkdir(parents=True, exist_ok=False)
    return output


def base_metadata(
    backend: str,
    model: str,
    revision: str | None,
    image: Path,
    prompt: str,
    max_tokens: int,
) -> dict[str, Any]:
    return {
        "trace_format_version": TRACE_FORMAT_VERSION,
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "backend": backend,
        "model": model,
        "requested_revision": revision,
        "image": {
            "path": str(image),
            "size_bytes": image.stat().st_size,
            "sha256": sha256_file(image),
        },
        "prompt": prompt,
        "max_tokens": max_tokens,
        "temperature": 0.0,
        "system": {
            "platform": platform.platform(),
            "machine": platform.machine(),
            "python": platform.python_version(),
        },
    }


def write_trace(
    output_dir: Path,
    metadata: dict[str, Any],
    arrays: dict[str, np.ndarray],
    cache_arrays: dict[str, np.ndarray],
    steps: list[dict[str, Any]],
    output_text: str,
) -> None:
    metadata["arrays"] = save_npz(output_dir / "trace.npz", arrays)
    metadata["prefill_cache"] = save_npz(
        output_dir / "prefill_cache.npz",
        cache_arrays,
    )
    metadata["generated_tokens"] = len(steps)
    metadata["output_sha256"] = sha256_bytes(output_text.encode("utf-8"))

    (output_dir / "decode_steps.json").write_text(
        json.dumps(steps, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (output_dir / "output.txt").write_text(output_text + "\n", encoding="utf-8")
    (output_dir / "metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def mlx_numpy(value: Any) -> np.ndarray:
    import mlx.core as mx

    if hasattr(value, "dtype") and value.dtype == mx.bfloat16:
        value = value.astype(mx.float32)
    return np.asarray(value)


def capture_mlx(args: argparse.Namespace) -> Path:
    try:
        import mlx.core as mx
        from mlx_vlm import load
        from mlx_vlm.models.cache import make_prompt_cache
        from mlx_vlm.prompt_utils import apply_chat_template
        from mlx_vlm.utils import prepare_inputs
    except ImportError as error:
        raise RuntimeError(
            "MLX dependencies are missing. Run with the benchmarks/ocr uv project.",
        ) from error

    image = args.image.expanduser().resolve()
    if not image.is_file():
        raise RuntimeError(f"Image does not exist: {image}")
    output_dir = prepare_output_dir(args.output_dir, "mlx-bf16")
    metadata = base_metadata(
        "mlx-vlm",
        args.model,
        args.revision,
        image,
        args.prompt,
        args.max_tokens,
    )
    metadata["packages"] = {
        name: package_version(name)
        for name in ("mlx", "mlx-vlm", "transformers", "numpy")
    }

    print(f"[mlx] loading {args.model}", flush=True)
    load_started = time.perf_counter()
    model, processor = load(args.model, revision=args.revision)
    metadata["load_seconds"] = time.perf_counter() - load_started
    metadata["resolved_revision"] = (
        commit_from_snapshot_path(getattr(model.config, "_name_or_path", None))
        or cached_hugging_face_revision(args.model, args.revision)
    )

    formatted_prompt = apply_chat_template(
        processor,
        model.config,
        args.prompt,
        num_images=1,
    )
    inputs = prepare_inputs(
        processor,
        images=[str(image)],
        prompts=formatted_prompt,
        image_token_index=getattr(model.config, "image_token_index", None),
        add_special_tokens=True,
    )
    input_ids = inputs["input_ids"]
    pixel_values = inputs["pixel_values"]
    attention_mask = inputs["attention_mask"]
    data_kwargs = {
        key: value
        for key, value in inputs.items()
        if key not in ("input_ids", "pixel_values", "attention_mask")
    }
    image_grid_thw = data_kwargs["image_grid_thw"]
    mm_token_type_ids = data_kwargs.get("mm_token_type_ids")

    trace_started = time.perf_counter()
    vision_pixel_values = pixel_values.astype(
        model.vision_tower.patch_embed.proj.weight.dtype,
    )
    vision_features = model.vision_tower(
        vision_pixel_values,
        image_grid_thw,
        output_hidden_states=False,
    )
    embedding_features = model.get_input_embeddings(
        input_ids,
        pixel_values,
        mask=attention_mask,
        **data_kwargs,
    )
    fused_embeddings = embedding_features.inputs_embeds
    position_ids = model.language_model._position_ids
    rope_deltas = model.language_model._rope_deltas

    prompt_cache = make_prompt_cache(model.language_model)
    prefill = model.language_model(
        input_ids,
        inputs_embeds=fused_embeddings,
        cache=prompt_cache,
    )
    mx.eval(
        vision_features,
        fused_embeddings,
        position_ids,
        rope_deltas,
        prefill.logits,
        *[state for entry in prompt_cache for state in entry.state],
    )

    cache_arrays: dict[str, np.ndarray] = {}
    for layer_index, entry in enumerate(prompt_cache):
        keys, values = entry.state
        cache_arrays[f"layer_{layer_index:02d}_keys"] = mlx_numpy(keys)
        cache_arrays[f"layer_{layer_index:02d}_values"] = mlx_numpy(values)

    arrays = {
        "input_ids": mlx_numpy(input_ids),
        "attention_mask": mlx_numpy(attention_mask),
        "pixel_values": mlx_numpy(pixel_values),
        "vision_pixel_values": mlx_numpy(vision_pixel_values),
        "image_grid_thw": mlx_numpy(image_grid_thw),
        "position_ids": mlx_numpy(position_ids),
        "rope_deltas": mlx_numpy(rope_deltas),
        "vision_features": mlx_numpy(vision_features),
        "fused_embeddings": mlx_numpy(fused_embeddings),
        "prefill_last_logits": mlx_numpy(prefill.logits[:, -1, :]),
    }
    if mm_token_type_ids is not None:
        arrays["mm_token_type_ids"] = mlx_numpy(mm_token_type_ids)

    tokenizer = processor.tokenizer
    eos_ids = {
        int(token_id)
        for token_id in (
            tokenizer.eos_token_id
            if isinstance(tokenizer.eos_token_id, list)
            else [tokenizer.eos_token_id]
        )
        if token_id is not None
    }
    config_eos = getattr(model.config.text_config, "eos_token_id", [])
    eos_ids.update(
        int(token_id)
        for token_id in (config_eos if isinstance(config_eos, list) else [config_eos])
        if token_id is not None
    )

    logits = prefill.logits[:, -1, :]
    steps: list[dict[str, Any]] = []
    generated: list[int] = []
    for step_index in range(args.max_tokens):
        if step_index and step_index % 50 == 0:
            print(f"[mlx] decoded {step_index} tokens", flush=True)
        mx.eval(logits)
        logits_np = mlx_numpy(logits)
        token_id = int(mx.argmax(logits, axis=-1).item())
        steps.append(
            {
                "step": step_index,
                "token_id": token_id,
                "token_text": tokenizer.decode([token_id]),
                "top_k": topk_entries(logits_np, args.top_k),
            },
        )
        generated.append(token_id)
        if token_id in eos_ids:
            break
        decode = model.language_model(
            mx.array([[token_id]], dtype=input_ids.dtype),
            cache=prompt_cache,
        )
        logits = decode.logits[:, -1, :]

    output_text = tokenizer.decode(generated, skip_special_tokens=True).strip()
    metadata["trace_seconds"] = time.perf_counter() - trace_started
    metadata["formatted_prompt"] = formatted_prompt
    metadata["prompt_tokens"] = int(input_ids.shape[1])
    metadata["eos_token_ids"] = sorted(eos_ids)
    metadata["model_config"] = {
        "hidden_size": int(model.config.text_config.hidden_size),
        "num_hidden_layers": int(model.config.text_config.num_hidden_layers),
        "num_attention_heads": int(model.config.text_config.num_attention_heads),
        "num_key_value_heads": int(model.config.text_config.num_key_value_heads),
        "head_dim": int(model.config.text_config.head_dim),
    }

    write_trace(output_dir, metadata, arrays, cache_arrays, steps, output_text)
    print(f"[mlx] trace: {output_dir}", flush=True)
    return output_dir


def torch_numpy(value: Any) -> np.ndarray:
    import torch

    if isinstance(value, torch.Tensor):
        return value.detach().to(device="cpu", dtype=torch.float32).numpy()
    return np.asarray(value)


def select_torch_device(requested: str) -> str:
    import torch

    if requested == "mps":
        if not torch.backends.mps.is_available():
            raise RuntimeError("PyTorch MPS was requested but is unavailable")
        return "mps"
    if requested == "cpu":
        return "cpu"
    return "mps" if torch.backends.mps.is_available() else "cpu"


def dynamic_cache_arrays(cache: Any) -> dict[str, np.ndarray]:
    arrays: dict[str, np.ndarray] = {}
    if hasattr(cache, "layers"):
        layers: Iterable[Any] = cache.layers
        for index, layer in enumerate(layers):
            keys = getattr(layer, "keys", None)
            values = getattr(layer, "values", None)
            if keys is not None and values is not None:
                arrays[f"layer_{index:02d}_keys"] = torch_numpy(keys)
                arrays[f"layer_{index:02d}_values"] = torch_numpy(values)
    elif hasattr(cache, "to_legacy_cache"):
        for index, (keys, values) in enumerate(cache.to_legacy_cache()):
            arrays[f"layer_{index:02d}_keys"] = torch_numpy(keys)
            arrays[f"layer_{index:02d}_values"] = torch_numpy(values)
    if not arrays:
        raise RuntimeError(f"Unsupported Transformers cache type: {type(cache).__name__}")
    return arrays


def capture_hf(args: argparse.Namespace) -> Path:
    try:
        import torch
        from transformers import AutoProcessor, GlmOcrForConditionalGeneration
    except ImportError as error:
        raise RuntimeError(
            "PyTorch dependencies are missing. Run uv with --extra pytorch.",
        ) from error

    image = args.image.expanduser().resolve()
    if not image.is_file():
        raise RuntimeError(f"Image does not exist: {image}")
    output_dir = prepare_output_dir(args.output_dir, "hf-bf16")
    metadata = base_metadata(
        "huggingface-pytorch",
        args.model,
        args.revision,
        image,
        args.prompt,
        args.max_tokens,
    )
    metadata["packages"] = {
        name: package_version(name)
        for name in ("torch", "transformers", "accelerate", "numpy")
    }
    device = select_torch_device(args.device)
    metadata["device"] = device
    load_options = {"revision": args.revision} if args.revision else {}

    print(f"[hf] loading processor {args.model}", flush=True)
    processor = AutoProcessor.from_pretrained(args.model, **load_options)
    print(f"[hf] loading BF16 model {args.model} on {device}", flush=True)
    load_started = time.perf_counter()
    model = GlmOcrForConditionalGeneration.from_pretrained(
        args.model,
        dtype=torch.bfloat16,
        low_cpu_mem_usage=True,
        **load_options,
    ).eval()
    model.to(device)
    metadata["load_seconds"] = time.perf_counter() - load_started
    metadata["resolved_revision"] = (
        commit_from_snapshot_path(getattr(model.config, "_name_or_path", None))
        or cached_hugging_face_revision(args.model, args.revision)
    )

    messages = [
        {
            "role": "user",
            "content": [
                {"type": "image", "url": str(image)},
                {"type": "text", "text": args.prompt},
            ],
        },
    ]
    cpu_batch = processor.apply_chat_template(
        messages,
        tokenize=True,
        add_generation_prompt=True,
        enable_thinking=False,
        return_dict=True,
        return_tensors="pt",
    )
    formatted_prompt = processor.apply_chat_template(
        messages,
        tokenize=False,
        add_generation_prompt=True,
        enable_thinking=False,
    )
    batch = {
        key: value.to(device) if isinstance(value, torch.Tensor) else value
        for key, value in cpu_batch.items()
    }
    input_ids = batch["input_ids"]
    attention_mask = batch["attention_mask"]
    pixel_values = batch["pixel_values"]
    vision_pixel_values = pixel_values.to(torch.bfloat16)
    image_grid_thw = batch["image_grid_thw"]
    mm_token_type_ids = batch["mm_token_type_ids"]

    trace_started = time.perf_counter()
    with torch.inference_mode():
        vision_features = model.model.visual(
            vision_pixel_values,
            grid_thw=image_grid_thw,
        ).pooler_output
        text_embeddings = model.get_input_embeddings()(input_ids)
        image_mask = input_ids == int(model.config.image_token_id)
        fused_embeddings = text_embeddings.masked_scatter(
            image_mask.unsqueeze(-1).expand_as(text_embeddings),
            vision_features.to(text_embeddings.dtype).reshape(-1),
        )
        position_ids, rope_deltas = model.model.get_rope_index(
            input_ids=input_ids,
            mm_token_type_ids=mm_token_type_ids,
            image_grid_thw=image_grid_thw,
            attention_mask=attention_mask,
        )
        prefill = model.model.language_model(
            inputs_embeds=fused_embeddings,
            attention_mask=attention_mask,
            position_ids=position_ids,
            use_cache=True,
        )
        prefill_logits = model.lm_head(prefill.last_hidden_state[:, -1:, :])
        cache = prefill.past_key_values
        cache_arrays = dynamic_cache_arrays(cache)

        arrays = {
            "input_ids": torch_numpy(input_ids).astype(np.int64),
            "attention_mask": torch_numpy(attention_mask).astype(np.int64),
            "pixel_values": torch_numpy(pixel_values),
            "vision_pixel_values": torch_numpy(vision_pixel_values),
            "image_grid_thw": torch_numpy(image_grid_thw).astype(np.int64),
            "mm_token_type_ids": torch_numpy(mm_token_type_ids).astype(np.int64),
            "position_ids": torch_numpy(position_ids).astype(np.int64),
            "rope_deltas": torch_numpy(rope_deltas).astype(np.int64),
            "vision_features": torch_numpy(vision_features),
            "fused_embeddings": torch_numpy(fused_embeddings),
            "prefill_last_logits": torch_numpy(prefill_logits[:, -1, :]),
        }

        eos_ids = {
            int(token_id)
            for token_id in (
                model.config.text_config.eos_token_id
                if isinstance(model.config.text_config.eos_token_id, list)
                else [model.config.text_config.eos_token_id]
            )
        }
        print("[hf] running the official generate() cache path", flush=True)
        generation_batch = {
            key: value.to(device) if isinstance(value, torch.Tensor) else value
            for key, value in cpu_batch.items()
        }
        model.model.rope_deltas = None
        generation = model.generate(
            **generation_batch,
            max_new_tokens=args.max_tokens,
            do_sample=False,
            return_dict_in_generate=True,
            output_logits=True,
        )
        prompt_length = int(input_ids.shape[1])
        generated = [
            int(token_id)
            for token_id in generation.sequences[0, prompt_length:].tolist()
        ]
        steps = []
        for step_index, (token_id, logits) in enumerate(
            zip(generated, generation.logits),
        ):
            logits_np = torch_numpy(logits)
            steps.append(
                {
                    "step": step_index,
                    "token_id": token_id,
                    "token_text": processor.tokenizer.decode([token_id]),
                    "top_k": topk_entries(logits_np, args.top_k),
                },
            )
        if generation.logits:
            arrays["generation_first_logits"] = torch_numpy(generation.logits[0])

    output_text = processor.tokenizer.decode(
        generated,
        skip_special_tokens=True,
    ).strip()
    metadata["trace_seconds"] = time.perf_counter() - trace_started
    metadata["formatted_prompt"] = formatted_prompt
    metadata["prompt_tokens"] = int(input_ids.shape[1])
    metadata["eos_token_ids"] = sorted(eos_ids)
    metadata["model_config"] = {
        "hidden_size": int(model.config.text_config.hidden_size),
        "num_hidden_layers": int(model.config.text_config.num_hidden_layers),
        "num_attention_heads": int(model.config.text_config.num_attention_heads),
        "num_key_value_heads": int(model.config.text_config.num_key_value_heads),
        "head_dim": int(model.config.text_config.head_dim),
    }
    write_trace(output_dir, metadata, arrays, cache_arrays, steps, output_text)
    print(f"[hf] trace: {output_dir}", flush=True)
    return output_dir


def numeric_metrics(reference: np.ndarray, candidate: np.ndarray) -> dict[str, Any]:
    ref = reference.astype(np.float64, copy=False).reshape(-1)
    cand = candidate.astype(np.float64, copy=False).reshape(-1)
    difference = cand - ref
    ref_norm = float(np.linalg.norm(ref))
    cand_norm = float(np.linalg.norm(cand))
    denominator = ref_norm * cand_norm
    cosine = float(np.dot(ref, cand) / denominator) if denominator else None
    return {
        "maximum_absolute_error": float(np.max(np.abs(difference))),
        "mean_absolute_error": float(np.mean(np.abs(difference))),
        "root_mean_square_error": float(math.sqrt(np.mean(difference**2))),
        "cosine_similarity": cosine,
    }


def compare_npz(reference_path: Path, candidate_path: Path) -> dict[str, Any]:
    report: dict[str, Any] = {}
    with np.load(reference_path) as reference, np.load(candidate_path) as candidate:
        names = sorted(set(reference.files) | set(candidate.files))
        for name in names:
            if name not in reference:
                report[name] = {"status": "candidate_only"}
                continue
            if name not in candidate:
                report[name] = {"status": "reference_only"}
                continue
            ref = reference[name]
            cand = candidate[name]
            entry: dict[str, Any] = {
                "reference_shape": list(ref.shape),
                "candidate_shape": list(cand.shape),
                "reference_dtype": str(ref.dtype),
                "candidate_dtype": str(cand.dtype),
            }
            if ref.shape != cand.shape:
                entry["status"] = "shape_mismatch"
            elif np.issubdtype(ref.dtype, np.integer) and np.issubdtype(
                cand.dtype,
                np.integer,
            ):
                entry["status"] = "exact" if np.array_equal(ref, cand) else "different"
                entry["different_elements"] = int(np.count_nonzero(ref != cand))
            else:
                entry["status"] = "compared"
                entry.update(numeric_metrics(ref, cand))
            report[name] = entry
    return report


def normalized_text(value: str) -> str:
    return "\n".join(line.rstrip() for line in value.strip().splitlines())


def capture_token_ids(path: Path) -> list[int]:
    steps = json.loads(path.read_text(encoding="utf-8"))
    return [int(step["token_id"]) for step in steps]


def common_prefix_length(left: list[int], right: list[int]) -> int:
    count = 0
    for left_token, right_token in zip(left, right):
        if left_token != right_token:
            break
        count += 1
    return count


def edit_distance(left: list[Any] | str, right: list[Any] | str) -> int:
    if len(left) < len(right):
        left, right = right, left
    previous = list(range(len(right) + 1))
    for left_index, left_value in enumerate(left, start=1):
        current = [left_index]
        for right_index, right_value in enumerate(right, start=1):
            current.append(
                min(
                    current[-1] + 1,
                    previous[right_index] + 1,
                    previous[right_index - 1] + (left_value != right_value),
                ),
            )
        previous = current
    return previous[-1]


def compare_traces(args: argparse.Namespace) -> Path:
    reference = args.reference.expanduser().resolve()
    candidate = args.candidate.expanduser().resolve()
    for directory in (reference, candidate):
        for required in (
            "metadata.json",
            "trace.npz",
            "prefill_cache.npz",
            "decode_steps.json",
            "output.txt",
        ):
            if not (directory / required).is_file():
                raise RuntimeError(f"Missing {required} in {directory}")

    reference_tokens = capture_token_ids(reference / "decode_steps.json")
    candidate_tokens = capture_token_ids(candidate / "decode_steps.json")
    reference_text = (reference / "output.txt").read_text(encoding="utf-8")
    candidate_text = (candidate / "output.txt").read_text(encoding="utf-8")
    normalized_reference = normalized_text(reference_text)
    normalized_candidate = normalized_text(candidate_text)
    token_edits = edit_distance(reference_tokens, candidate_tokens)
    character_edits = edit_distance(normalized_reference, normalized_candidate)
    reference_lines = normalized_reference.splitlines()
    candidate_lines = normalized_candidate.splitlines()
    report = {
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "reference": str(reference),
        "candidate": str(candidate),
        "trace": compare_npz(reference / "trace.npz", candidate / "trace.npz"),
        "prefill_cache": compare_npz(
            reference / "prefill_cache.npz",
            candidate / "prefill_cache.npz",
        ),
        "decode": {
            "reference_tokens": len(reference_tokens),
            "candidate_tokens": len(candidate_tokens),
            "common_prefix_tokens": common_prefix_length(
                reference_tokens,
                candidate_tokens,
            ),
            "exact_token_sequence": reference_tokens == candidate_tokens,
            "edit_distance": token_edits,
            "normalized_edit_distance": token_edits / max(1, len(reference_tokens)),
        },
        "text": {
            "exact": reference_text == candidate_text,
            "normalized_exact": normalized_reference == normalized_candidate,
            "character_edit_distance": character_edits,
            "character_error_rate": character_edits
            / max(1, len(normalized_reference)),
            "same_position_lines": sum(
                left == right for left, right in zip(reference_lines, candidate_lines)
            ),
            "reference_lines": len(reference_lines),
            "candidate_lines": len(candidate_lines),
            "reference_sha256": sha256_bytes(reference_text.encode("utf-8")),
            "candidate_sha256": sha256_bytes(candidate_text.encode("utf-8")),
        },
    }
    output = (
        args.output.expanduser().resolve()
        if args.output
        else candidate / "comparison-to-mlx.json"
    )
    output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"[compare] report: {output}", flush=True)
    return output


def main() -> int:
    args = parse_args()
    if args.command == "capture-mlx":
        capture_mlx(args)
    elif args.command == "capture-hf":
        capture_hf(args)
    elif args.command == "compare":
        compare_traces(args)
    else:
        raise AssertionError(args.command)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
