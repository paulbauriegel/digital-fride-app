#!/usr/bin/env python3
"""Export GLM-OCR vision-feature prefill and dynamic-RoPE decode signatures."""

from __future__ import annotations

import argparse
from pathlib import Path

import torch

from glm_ocr_decoder import (
    GlmOcrDecoderConfig,
    GlmOcrLiteRtDecoder,
    GlmOcrVisionFeatureDecoder,
    load_hugging_face_weights,
)


class ExportableModule(torch.nn.Module):
    """Capture non-tensor export arguments without importing LoRA utilities."""

    def __init__(self, module: torch.nn.Module, **extra_kwargs) -> None:
        super().__init__()
        self.module = module
        self.extra_kwargs = extra_kwargs

    def forward(self, *args, **kwargs):
        return self.module(*args, **{**kwargs, **self.extra_kwargs})


DEFAULT_SNAPSHOT = (
    Path.home()
    / ".cache/huggingface/hub/models--zai-org--GLM-OCR/snapshots"
    / "ca5d8b3e287e52589e37c28385d9655ee4372f9d"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Export a component TFLite model whose multimodal prefill consumes "
            "vision features, explicit 3D positions, and an image-dependent "
            "RoPE delta."
        ),
    )
    parser.add_argument("--checkpoint", type=Path, default=DEFAULT_SNAPSHOT)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sequence-length", type=int, default=798)
    parser.add_argument("--image-token-start", type=int, default=5)
    parser.add_argument("--image-token-count", type=int, default=782)
    parser.add_argument("--cache-length", type=int, default=1_536)
    parser.add_argument(
        "--signature-mode",
        choices=("both", "prefill", "decode"),
        default="both",
        help="Export combined signatures or one model per signature.",
    )
    parser.add_argument(
        "--dtype",
        choices=("float32", "float16"),
        default="float16",
        help="Weight storage dtype. FP16 storage uses FP32 compute for LiteRT.",
    )
    parser.add_argument(
        "--smoke-test",
        action="store_true",
        help="Convert a tiny random-weight graph with the same multimodal operators.",
    )
    parser.add_argument(
        "--lightweight-conversion",
        action="store_true",
        help=(
            "Handle constants lazily to lower conversion peak memory. This may "
            "bypass converter-side constant folding."
        ),
    )
    parser.add_argument(
        "--runtime-constant-folding",
        choices=("auto", "on", "off"),
        default="auto",
        help=(
            "Control LiteRT runtime constant folding. Auto follows the "
            "converter default; use off with lightweight conversion when "
            "minimizing peak memory."
        ),
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.smoke_test:
        sequence_length = 6
        image_token_start = 1
        image_token_count = 4
        cache_length = 16
        config = GlmOcrDecoderConfig(
            vocab_size=64,
            hidden_size=32,
            intermediate_size=64,
            num_layers=2,
            num_attention_heads=4,
            num_key_value_heads=2,
            head_dim=8,
            mrope_section=(1, 1, 2),
            max_seq_len=cache_length,
            pad_token_id=0,
            eos_token_ids=(1, 2),
            image_token_id=63,
        )
    else:
        sequence_length = args.sequence_length
        image_token_start = args.image_token_start
        image_token_count = args.image_token_count
        cache_length = args.cache_length
        config = GlmOcrDecoderConfig(max_seq_len=cache_length)
    model_dtype = {
        "float32": torch.float32,
        "float16": torch.float16,
    }[args.dtype]

    if image_token_start < 0 or image_token_count <= 0:
        raise ValueError("image token start/count are invalid")
    image_end = image_token_start + image_token_count
    if image_end > sequence_length:
        raise ValueError("image token span exceeds the prefill sequence")
    if sequence_length >= cache_length:
        raise ValueError("prefill sequence must be shorter than the cache")

    try:
        from litert_torch._convert import interface as converter_interface
        from litert_torch.generative.layers import kv_cache as kv_utils
        from litert_torch.generative.utilities import export_config
    except ImportError as error:
        raise RuntimeError(
            "LiteRT Torch v0.9.1 is required and its converter runs on Linux.",
        ) from error

    decoder = GlmOcrLiteRtDecoder(config).to(dtype=model_dtype)
    decoder.prefill_last_logits_only = True
    if model_dtype == torch.float16:
        # LiteRT 2.1 rejects FP16 EMBEDDING_LOOKUP and BATCH_MATMUL. Keep the
        # table and activations in FP32 while storing large linear weights in
        # FP16; linear_in_input_dtype inserts the required casts.
        decoder.tok_embedding.to(dtype=torch.float32)
        decoder.compute_dtype = torch.float32
    if not args.smoke_test:
        load_hugging_face_weights(decoder, args.checkpoint)
    model = GlmOcrVisionFeatureDecoder(
        decoder,
        image_token_start=image_token_start,
        image_token_count=image_token_count,
    ).eval()
    options = export_config.ExportConfig(
        output_logits_on_prefill=True,
        mask_as_input=False,
    )
    exportable = ExportableModule(
        model,
        export_config=options,
    ).eval()
    cache = kv_utils.KVCache.from_model_config(
        cache_length,
        model.config,
        dtype=decoder.compute_dtype or model_dtype,
    )

    converter = converter_interface.Converter()
    if args.signature_mode in ("both", "prefill"):
        converter.add_signature(
            f"prefill_{sequence_length}_vision_features",
            exportable,
            sample_kwargs={
                "tokens": torch.zeros(
                    (1, sequence_length),
                    dtype=torch.int,
                ),
                "input_pos": torch.arange(sequence_length, dtype=torch.int),
                "kv_cache": cache,
                "vision_features": torch.zeros(
                    (image_token_count, config.hidden_size),
                    dtype=decoder.compute_dtype or model_dtype,
                ),
                "position_ids": torch.zeros(
                    (3, 1, sequence_length),
                    dtype=torch.int,
                ),
                "rope_delta": torch.zeros((1, 1), dtype=torch.int),
            },
        )
    if args.signature_mode in ("both", "decode"):
        converter.add_signature(
            "decode_with_rope_delta",
            exportable,
            sample_kwargs={
                "tokens": torch.zeros((1, 1), dtype=torch.int),
                "input_pos": torch.zeros((1,), dtype=torch.int),
                "kv_cache": cache,
                "rope_delta": torch.zeros((1, 1), dtype=torch.int),
            },
        )
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    runtime_constant_folding = {
        "auto": None,
        "on": True,
        "off": False,
    }[args.runtime_constant_folding]
    edge_model = converter.convert(
        lightweight_conversion=args.lightweight_conversion,
        runtime_constant_folding=runtime_constant_folding,
    )
    edge_model.export(str(output))
    print(f"[export] wrote multimodal decoder component: {output}", flush=True)


if __name__ == "__main__":
    main()
