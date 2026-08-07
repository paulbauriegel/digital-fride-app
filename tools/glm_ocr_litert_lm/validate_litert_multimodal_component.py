#!/usr/bin/env python3
"""Inspect and exercise a converted GLM-OCR multimodal LiteRT component."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--sequence-length", type=int, required=True)
    parser.add_argument("--image-token-count", type=int, required=True)
    parser.add_argument("--cache-length", type=int, required=True)
    parser.add_argument("--layers", type=int, required=True)
    parser.add_argument("--kv-heads", type=int, required=True)
    parser.add_argument("--head-dim", type=int, required=True)
    parser.add_argument("--report", type=Path)
    parser.add_argument(
        "--decode-only",
        action="store_true",
        help=(
            "Skip the expensive full prefill and exercise cached decode from "
            "an empty cache. Signature inspection still covers both graphs."
        ),
    )
    return parser.parse_args()


def changed(left: np.ndarray, right: np.ndarray) -> bool:
    return not np.array_equal(left, right)


def main() -> None:
    from ai_edge_litert.interpreter import Interpreter

    args = parse_args()
    interpreter = Interpreter(model_path=str(args.model))
    interpreter.allocate_tensors()
    signatures = interpreter.get_signature_list()
    prefill_name = next(
        name for name in signatures if name.startswith("prefill_")
    )
    decode_name = next(
        name for name in signatures if name.startswith("decode")
    )
    prefill = interpreter.get_signature_runner(prefill_name)
    decode = interpreter.get_signature_runner(decode_name)

    cache_shape = (
        1,
        args.cache_length,
        args.kv_heads,
        args.head_dim,
    )
    cache_inputs = {}
    for layer in range(args.layers):
        cache_inputs[f"kv_cache_k_{layer}"] = np.zeros(
            cache_shape,
            dtype=np.float32,
        )
        cache_inputs[f"kv_cache_v_{layer}"] = np.zeros(
            cache_shape,
            dtype=np.float32,
        )

    rope_delta = np.asarray([[-2]], dtype=np.int32)
    if args.decode_only:
        prefill_report = {"skipped": True}
        decode_cache = cache_inputs
    else:
        tokens = np.zeros((1, args.sequence_length), dtype=np.int32)
        input_pos = np.arange(args.sequence_length, dtype=np.int32)
        flat_positions = np.broadcast_to(
            np.arange(args.sequence_length, dtype=np.int32).reshape(1, 1, -1),
            (3, 1, args.sequence_length),
        ).copy()
        grid_positions = flat_positions.copy()
        grid_positions[1, 0] = np.arange(
            args.sequence_length,
            dtype=np.int32,
        ) // 2
        grid_positions[2, 0] = np.arange(
            args.sequence_length,
            dtype=np.int32,
        ) % 2
        zero_vision = np.zeros(
            (args.image_token_count, 32),
            dtype=np.float32,
        )
        # Infer hidden size from the signature rather than adding another CLI
        # flag.
        for detail in interpreter.get_input_details():
            if detail["name"].endswith("vision_features"):
                zero_vision = np.zeros(detail["shape"], dtype=np.float32)
                break
        one_vision = np.ones_like(zero_vision)

        common = {
            "tokens": tokens,
            "input_pos": input_pos,
            "rope_delta": rope_delta,
            **cache_inputs,
        }
        zero_output = prefill(
            **common,
            vision_features=zero_vision,
            position_ids=flat_positions,
        )
        vision_output = prefill(
            **common,
            vision_features=one_vision,
            position_ids=flat_positions,
        )
        position_output = prefill(
            **common,
            vision_features=one_vision,
            position_ids=grid_positions,
        )
        prefill_report = {
            "vision_features_affect_logits": changed(
                zero_output["logits"],
                vision_output["logits"],
            ),
            "position_ids_affect_logits": changed(
                vision_output["logits"],
                position_output["logits"],
            ),
            "rope_delta_round_trips": bool(
                np.array_equal(position_output["rope_delta"], rope_delta),
            ),
        }
        decode_cache = {
            name: value
            for name, value in position_output.items()
            if name.startswith("kv_cache_")
        }
    decode_common = {
        "tokens": np.asarray([[3]], dtype=np.int32),
        "input_pos": np.asarray([args.sequence_length], dtype=np.int32),
        **decode_cache,
    }
    decode_shifted = decode(
        **decode_common,
        rope_delta=rope_delta,
    )
    decode_unshifted = decode(
        **decode_common,
        rope_delta=np.zeros((1, 1), dtype=np.int32),
    )

    report = {
        "model": str(args.model.resolve()),
        "signatures": signatures,
        "prefill": prefill_report,
        "decode": {
            "rope_delta_affects_logits": changed(
                decode_shifted["logits"],
                decode_unshifted["logits"],
            ),
            "rope_delta_round_trips": bool(
                np.array_equal(decode_shifted["rope_delta"], rope_delta),
            ),
        },
    }
    report["passed"] = (
        args.decode_only or all(report["prefill"].values())
    ) and all(report["decode"].values())
    rendered = json.dumps(report, indent=2)
    print(rendered)
    if args.report:
        args.report.write_text(rendered + "\n", encoding="utf-8")
    if not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
