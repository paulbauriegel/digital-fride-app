#!/usr/bin/env python3
"""Capture MLX full-context logits and verify the LiteRT-LM GLM decoder."""

from __future__ import annotations

import argparse
import gc
import json
import math
import time
from pathlib import Path
from typing import Any

import numpy as np

from glm_ocr_decoder import (
    GlmOcrDecoderConfig,
    GlmOcrLiteRtDecoder,
    GlmOcrVisionFeatureDecoder,
    build_single_image_layout,
    load_hugging_face_weights,
    make_kv_cache,
)


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MLX_TRACE = REPO_ROOT / "benchmarks/ocr/reference/mlx-bf16"
DEFAULT_HF_TRACE = REPO_ROOT / "benchmarks/ocr/reference/hf-bf16"
DEFAULT_MODEL = "mlx-community/GLM-OCR-bf16"
DEFAULT_REVISION = "24f15402e83baa0a80eeeaecf5480e172abc6f2e"
DEFAULT_CHECKPOINT = (
    Path.home()
    / ".cache/huggingface/hub/models--zai-org--GLM-OCR/snapshots"
    / "ca5d8b3e287e52589e37c28385d9655ee4372f9d/model.safetensors"
)


def positive_int(value: str) -> int:
    result = int(value)
    if result <= 0:
        raise argparse.ArgumentTypeError("must be greater than zero")
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    subcommands = parser.add_subparsers(dest="command", required=True)

    capture = subcommands.add_parser(
        "capture-mlx-full-context",
        help="Recompute the entire MLX prompt for each teacher-forced token.",
    )
    capture.add_argument("--trace", type=Path, default=DEFAULT_MLX_TRACE)
    capture.add_argument("--model", default=DEFAULT_MODEL)
    capture.add_argument("--revision", default=DEFAULT_REVISION)
    capture.add_argument("--steps", type=positive_int, default=8)
    capture.add_argument("--output", type=Path)

    verify = subcommands.add_parser(
        "verify",
        help="Verify float prefill and external KV-cached decode parity.",
    )
    verify.add_argument("--mlx-trace", type=Path, default=DEFAULT_MLX_TRACE)
    verify.add_argument("--hf-trace", type=Path, default=DEFAULT_HF_TRACE)
    verify.add_argument("--checkpoint", type=Path, default=DEFAULT_CHECKPOINT)
    verify.add_argument("--steps", type=positive_int, default=8)
    verify.add_argument("--cache-length", type=positive_int, default=1_536)
    verify.add_argument("--device", choices=("auto", "mps", "cpu"), default="auto")
    verify.add_argument("--dtype", choices=("bfloat16", "float32"), default="bfloat16")
    verify.add_argument("--report", type=Path)
    verify.add_argument(
        "--require-full-context",
        action="store_true",
        help="Fail unless the MLX full-context logit file covers all requested steps.",
    )
    return parser.parse_args()


def trace_tokens(trace: Path) -> list[int]:
    steps = json.loads((trace / "decode_steps.json").read_text(encoding="utf-8"))
    return [int(step["token_id"]) for step in steps]


def numeric_metrics(reference: np.ndarray, candidate: np.ndarray) -> dict[str, float]:
    left = reference.astype(np.float64, copy=False).reshape(-1)
    right = candidate.astype(np.float64, copy=False).reshape(-1)
    difference = right - left
    denominator = float(np.linalg.norm(left) * np.linalg.norm(right))
    return {
        "cosine_similarity": (
            float(np.dot(left, right) / denominator) if denominator else float("nan")
        ),
        "maximum_absolute_error": float(np.max(np.abs(difference))),
        "mean_absolute_error": float(np.mean(np.abs(difference))),
        "root_mean_square_error": float(math.sqrt(np.mean(difference**2))),
    }


def capture_mlx_full_context(args: argparse.Namespace) -> Path:
    import mlx.core as mx
    from mlx_vlm import load

    trace = args.trace.expanduser().resolve()
    output_path = (
        args.output.expanduser().resolve()
        if args.output
        else trace / "full_context_logits.npz"
    )
    with np.load(trace / "trace.npz") as arrays:
        prompt_ids_np = arrays["input_ids"].astype(np.int32)
        prompt_embeddings_np = arrays["fused_embeddings"].astype(np.float32)
        prompt_positions_np = arrays["position_ids"].astype(np.int32)
        rope_delta = int(arrays["rope_deltas"].reshape(-1)[0])
    teacher_tokens = trace_tokens(trace)
    step_count = min(args.steps, len(teacher_tokens))

    print(f"[mlx-full] loading {args.model}@{args.revision}", flush=True)
    model, _ = load(args.model, revision=args.revision)
    prompt_ids = mx.array(prompt_ids_np)
    prompt_embeddings = mx.array(prompt_embeddings_np).astype(
        model.language_model.model.norm.weight.dtype,
    )
    prompt_positions = mx.array(prompt_positions_np)
    all_logits: list[np.ndarray] = []
    argmax_tokens: list[int] = []
    started = time.perf_counter()

    for step in range(step_count):
        prefix = teacher_tokens[:step]
        if prefix:
            prefix_ids = mx.array([prefix], dtype=prompt_ids.dtype)
            prefix_embeddings = model.language_model.model.embed_tokens(prefix_ids)
            inputs = mx.concatenate((prompt_ids, prefix_ids), axis=1)
            embeddings = mx.concatenate(
                (prompt_embeddings, prefix_embeddings),
                axis=1,
            )
            text_positions = (
                mx.arange(len(prefix), dtype=prompt_ids.dtype)
                + prompt_ids.shape[1]
                + rope_delta
            )
            text_positions = mx.broadcast_to(
                text_positions.reshape(1, 1, -1),
                (3, 1, len(prefix)),
            )
            positions = mx.concatenate((prompt_positions, text_positions), axis=-1)
        else:
            inputs = prompt_ids
            embeddings = prompt_embeddings
            positions = prompt_positions

        model_output = model.language_model(
            inputs,
            inputs_embeds=embeddings,
            position_ids=positions,
            cache=None,
        )
        logits = model_output.logits[:, -1, :]
        mx.eval(logits)
        logits_np = np.asarray(logits.astype(mx.float32))
        all_logits.append(logits_np)
        argmax_tokens.append(int(mx.argmax(logits, axis=-1).item()))
        print(
            f"[mlx-full] step {step + 1}/{step_count}: "
            f"argmax={argmax_tokens[-1]} teacher={teacher_tokens[step]}",
            flush=True,
        )
        mx.clear_cache()

    np.savez_compressed(
        output_path,
        logits=np.concatenate(all_logits, axis=0),
        argmax_tokens=np.asarray(argmax_tokens, dtype=np.int64),
        teacher_tokens=np.asarray(teacher_tokens[:step_count], dtype=np.int64),
    )
    metadata = {
        "model": args.model,
        "revision": args.revision,
        "source_trace": str(trace),
        "steps": step_count,
        "rope_delta": rope_delta,
        "elapsed_seconds": time.perf_counter() - started,
        "argmax_matches_teacher": [
            left == right
            for left, right in zip(
                argmax_tokens,
                teacher_tokens[:step_count],
                strict=True,
            )
        ],
    }
    output_path.with_suffix(".json").write_text(
        json.dumps(metadata, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"[mlx-full] wrote {output_path}", flush=True)
    return output_path


def select_device(requested: str) -> str:
    import torch

    if requested == "auto":
        return "mps" if torch.backends.mps.is_available() else "cpu"
    if requested == "mps" and not torch.backends.mps.is_available():
        raise RuntimeError("MPS was requested but is unavailable")
    return requested


def load_trace_array(trace: Path, name: str) -> np.ndarray:
    with np.load(trace / "trace.npz") as arrays:
        return arrays[name].copy()


def verify(args: argparse.Namespace) -> Path:
    import torch

    mlx_trace = args.mlx_trace.expanduser().resolve()
    hf_trace = args.hf_trace.expanduser().resolve()
    checkpoint = args.checkpoint.expanduser().resolve()
    device = select_device(args.device)
    dtype = torch.bfloat16 if args.dtype == "bfloat16" else torch.float32

    mlx_prompt = load_trace_array(mlx_trace, "fused_embeddings")
    mlx_positions = load_trace_array(mlx_trace, "position_ids")
    prompt_tokens_np = load_trace_array(mlx_trace, "input_ids")
    vision_features_np = load_trace_array(mlx_trace, "vision_features")
    image_grid_np = load_trace_array(mlx_trace, "image_grid_thw")
    mlx_prefill_logits = load_trace_array(mlx_trace, "prefill_last_logits")
    hf_prefill_logits = load_trace_array(hf_trace, "prefill_last_logits")
    prompt_length = mlx_prompt.shape[1]
    golden_rope_delta = load_trace_array(mlx_trace, "rope_deltas")
    config = GlmOcrDecoderConfig(max_seq_len=args.cache_length)
    prompt_tokens_cpu = torch.from_numpy(prompt_tokens_np).to(torch.long)
    layout = build_single_image_layout(
        prompt_tokens_cpu,
        torch.from_numpy(image_grid_np),
        image_token_id=config.image_token_id,
        spatial_merge_size=config.spatial_merge_size,
    )
    rope_delta = int(layout.rope_delta.item())
    if args.cache_length <= prompt_length + args.steps:
        raise ValueError("cache length must exceed prompt length plus decode steps")

    print(
        f"[litert-authoring] building float decoder on {device} ({args.dtype})",
        flush=True,
    )
    model = GlmOcrLiteRtDecoder(
        config,
        decode_rope_delta=0,
    ).to(device=device, dtype=dtype)
    print(f"[litert-authoring] loading {checkpoint}", flush=True)
    load_hugging_face_weights(model, checkpoint)
    multimodal_model = GlmOcrVisionFeatureDecoder(
        model,
        image_token_start=layout.image_token_start,
        image_token_count=layout.image_token_count,
    )
    cache = make_kv_cache(
        config,
        max_seq_len=args.cache_length,
        dtype=dtype,
        device=device,
    )
    prompt_tokens = prompt_tokens_cpu.to(device=device)
    vision_features = torch.from_numpy(vision_features_np).to(
        device=device,
        dtype=dtype,
    )
    positions = layout.position_ids.to(device=device, dtype=torch.long)
    rope_delta_tensor = layout.rope_delta.to(device=device, dtype=torch.long)
    input_pos = torch.arange(prompt_length, device=device, dtype=torch.int32)

    with torch.inference_mode():
        text_embeddings = model.tok_embedding(prompt_tokens)
        image_end = layout.image_token_start + layout.image_token_count
        candidate_fused = torch.cat(
            (
                text_embeddings[:, : layout.image_token_start],
                vision_features.unsqueeze(0),
                text_embeddings[:, image_end:],
            ),
            dim=1,
        )
    candidate_fused_np = candidate_fused.float().cpu().numpy()

    started = time.perf_counter()
    print(f"[litert-authoring] prefill {prompt_length} fused embeddings", flush=True)
    with torch.inference_mode():
        output = multimodal_model(
            tokens=prompt_tokens,
            vision_features=vision_features,
            position_ids=positions,
            rope_delta=rope_delta_tensor,
            input_pos=input_pos,
            kv_cache=cache,
        )
    prefill_seconds = time.perf_counter() - started
    current_logits = output["logits"][:, -1, :].float().cpu().numpy()
    cache = output["kv_cache"]

    report: dict[str, Any] = {
        "contract": {
            "prompt_length": prompt_length,
            "cache_length": args.cache_length,
            "rope_delta": rope_delta,
            "dtype": args.dtype,
            "device": device,
            "layers": config.num_layers,
            "kv_layout": "[batch, sequence, kv_heads, head_dim]",
        },
        "multimodal_bridge": {
            "image_token_start": layout.image_token_start,
            "image_token_count": layout.image_token_count,
            "grid_thw": list(layout.grid_thw),
            "fused_embeddings_against_mlx": numeric_metrics(
                mlx_prompt,
                candidate_fused_np,
            ),
            "position_ids_exact": bool(
                np.array_equal(
                    mlx_positions,
                    layout.position_ids.cpu().numpy(),
                ),
            ),
            "rope_delta": rope_delta,
            "golden_rope_delta": int(golden_rope_delta.reshape(-1)[0]),
            "rope_delta_exact": bool(
                np.array_equal(
                    golden_rope_delta,
                    layout.rope_delta.cpu().numpy(),
                ),
            ),
        },
        "prefill": {
            "seconds": prefill_seconds,
            "against_mlx": numeric_metrics(mlx_prefill_logits, current_logits),
            "against_hf": numeric_metrics(hf_prefill_logits, current_logits),
            "candidate_argmax": int(current_logits.argmax(axis=-1)[0]),
            "mlx_argmax": int(mlx_prefill_logits.argmax(axis=-1)[0]),
            "hf_argmax": int(hf_prefill_logits.argmax(axis=-1)[0]),
        },
    }

    cache_metrics: dict[str, Any] = {}
    with np.load(mlx_trace / "prefill_cache.npz") as golden_cache:
        for layer, entry in enumerate(cache.caches):
            for kind, candidate in (
                ("keys", entry.k_cache),
                ("values", entry.v_cache),
            ):
                name = f"layer_{layer:02d}_{kind}"
                golden = golden_cache[name]
                candidate_np = (
                    candidate[:, :prompt_length]
                    .permute(0, 2, 1, 3)
                    .float()
                    .cpu()
                    .numpy()
                )
                cache_metrics[name] = numeric_metrics(golden, candidate_np)
    cache_cosines = [
        metrics["cosine_similarity"] for metrics in cache_metrics.values()
    ]
    report["prefill_cache"] = {
        "minimum_cosine_similarity": min(cache_cosines),
        "mean_cosine_similarity": float(np.mean(cache_cosines)),
        "tensors": cache_metrics,
    }

    teacher_tokens = trace_tokens(mlx_trace)
    step_count = min(args.steps, len(teacher_tokens))
    full_context_path = mlx_trace / "full_context_logits.npz"
    full_context_logits: np.ndarray | None = None
    if full_context_path.is_file():
        with np.load(full_context_path) as full_context:
            saved = full_context["logits"]
            if saved.shape[0] >= step_count:
                full_context_logits = saved[:step_count].copy()
    if args.require_full_context and full_context_logits is None:
        raise RuntimeError(
            f"{full_context_path} does not cover {step_count} steps; run "
            "capture-mlx-full-context first",
        )

    recorded_steps = json.loads(
        (mlx_trace / "decode_steps.json").read_text(encoding="utf-8"),
    )
    decode_rows = []
    decode_started = time.perf_counter()
    for step in range(step_count):
        teacher = teacher_tokens[step]
        candidate_argmax = int(current_logits.argmax(axis=-1)[0])
        row: dict[str, Any] = {
            "step": step,
            "teacher_token": teacher,
            "candidate_argmax": candidate_argmax,
            "argmax_matches_teacher": candidate_argmax == teacher,
        }
        if full_context_logits is not None:
            row["against_mlx_full_context"] = numeric_metrics(
                full_context_logits[step : step + 1],
                current_logits,
            )
            row["mlx_full_context_argmax"] = int(
                full_context_logits[step].argmax(),
            )
        else:
            top_k = recorded_steps[step]["top_k"]
            token_ids = np.asarray(
                [int(item["token_id"]) for item in top_k],
                dtype=np.int64,
            )
            golden_values = np.asarray(
                [float(item["logit"]) for item in top_k],
                dtype=np.float32,
            )
            row["against_mlx_recorded_top_k"] = numeric_metrics(
                golden_values,
                current_logits[0, token_ids],
            )
        decode_rows.append(row)
        print(
            f"[litert-authoring] decode {step + 1}/{step_count}: "
            f"argmax={candidate_argmax} teacher={teacher}",
            flush=True,
        )
        if step + 1 == step_count:
            break
        token = torch.tensor([[teacher]], device=device, dtype=torch.long)
        token_position = torch.tensor(
            [prompt_length + step],
            device=device,
            dtype=torch.int32,
        )
        with torch.inference_mode():
            output = multimodal_model(
                tokens=token,
                input_pos=token_position,
                kv_cache=cache,
                rope_delta=rope_delta_tensor,
            )
        cache = output["kv_cache"]
        current_logits = output["logits"][:, -1, :].float().cpu().numpy()

    decode_seconds = time.perf_counter() - decode_started
    report["decode"] = {
        "steps": step_count,
        "seconds": decode_seconds,
        "tokens_per_second": (
            (step_count - 1) / decode_seconds if step_count > 1 else None
        ),
        "all_argmax_match_teacher": all(
            row["argmax_matches_teacher"] for row in decode_rows
        ),
        "full_context_logits_used": full_context_logits is not None,
        "rows": decode_rows,
    }
    report["gates"] = {
        "prefill_argmax": (
            report["prefill"]["candidate_argmax"]
            == report["prefill"]["mlx_argmax"]
        ),
        "prefill_cosine_at_least_0_999": (
            report["prefill"]["against_mlx"]["cosine_similarity"] >= 0.999
        ),
        "cache_cosine_at_least_0_98": min(cache_cosines) >= 0.98,
        "decode_argmax_all_match": report["decode"]["all_argmax_match_teacher"],
        "vision_fusion_cosine_at_least_0_999": (
            report["multimodal_bridge"]["fused_embeddings_against_mlx"][
                "cosine_similarity"
            ]
            >= 0.999
        ),
        "multimodal_positions_exact": (
            report["multimodal_bridge"]["position_ids_exact"]
            and report["multimodal_bridge"]["rope_delta_exact"]
        ),
    }
    report["passed"] = all(report["gates"].values())

    output_path = (
        args.report.expanduser().resolve()
        if args.report
        else mlx_trace / "litert_lm_decoder_parity.json"
    )
    output_path.write_text(
        json.dumps(report, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"[litert-authoring] report: {output_path}", flush=True)
    print(
        "[litert-authoring] "
        + ("PASS" if report["passed"] else "FAIL")
        + f"; prefill cosine="
        f"{report['prefill']['against_mlx']['cosine_similarity']:.8f}; "
        f"cache min cosine={min(cache_cosines):.8f}",
        flush=True,
    )
    del cache, multimodal_model, model
    gc.collect()
    if device == "mps":
        torch.mps.empty_cache()
    if not report["passed"]:
        raise SystemExit(1)
    return output_path


def main() -> None:
    args = parse_args()
    if args.command == "capture-mlx-full-context":
        capture_mlx_full_context(args)
    else:
        verify(args)


if __name__ == "__main__":
    main()
