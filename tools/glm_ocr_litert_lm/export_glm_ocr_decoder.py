#!/usr/bin/env python3
"""Export the reauthored GLM-OCR decoder with LiteRT Torch v0.9.1."""

from __future__ import annotations

import argparse
import importlib.machinery
import importlib.util
from pathlib import Path
import sys
import types

from glm_ocr_decoder import (
    GlmOcrDecoderConfig,
    GlmOcrLiteRtDecoder,
    load_hugging_face_weights,
)


DEFAULT_SNAPSHOT = (
    Path.home()
    / ".cache/huggingface/hub/models--zai-org--GLM-OCR/snapshots"
    / "ca5d8b3e287e52589e37c28385d9655ee4372f9d"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Export float GLM-OCR text prefill/decode signatures and optionally "
            "pack them as a LiteRT-LM file."
        ),
    )
    parser.add_argument("--checkpoint", type=Path, default=DEFAULT_SNAPSHOT)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--output-prefix", default="glm_ocr_decoder")
    parser.add_argument(
        "--prefill-length",
        type=int,
        action="append",
        dest="prefill_lengths",
        help="Repeat for multiple token prefill signatures.",
    )
    parser.add_argument("--cache-length", type=int, default=1_536)
    parser.add_argument(
        "--decode-rope-delta",
        type=int,
        default=0,
        help=(
            "Baked M-RoPE delta for decode. Keep 0 for text-only; the current "
            "golden image uses -736."
        ),
    )
    parser.add_argument(
        "--format",
        choices=("tflite", "litertlm"),
        default="tflite",
    )
    parser.add_argument(
        "--quantize",
        choices=(
            "none",
            "fp16",
            "dynamic_int8",
            "weight_only_int8",
            "dynamic_int4_block32",
            "dynamic_int4_block128",
        ),
        default="none",
        help="Keep none for the float parity gate.",
    )
    parser.add_argument(
        "--tokenizer-json",
        type=Path,
        help="Required for --format litertlm; defaults to checkpoint/tokenizer.json.",
    )
    parser.add_argument(
        "--output-prefill-logits",
        action="store_true",
        help=(
            "Export full-sequence prefill logits for parity debugging. "
            "The deployable LiteRT-LM contract omits them."
        ),
    )
    return parser.parse_args()


def install_tensorflow_schema_compat() -> None:
    """Provide the schema-only TensorFlow import expected by LiteRT-Torch 0.9.1."""
    if importlib.util.find_spec("tensorflow") is not None:
        return

    from ai_edge_litert import schema_py_generated

    tensorflow = types.ModuleType("tensorflow")
    tensorflow_lite = types.ModuleType("tensorflow.lite")
    tensorflow_lite_python = types.ModuleType("tensorflow.lite.python")
    for name, module in (
        ("tensorflow", tensorflow),
        ("tensorflow.lite", tensorflow_lite),
        ("tensorflow.lite.python", tensorflow_lite_python),
    ):
        module.__path__ = []
        module.__spec__ = importlib.machinery.ModuleSpec(name, loader=None)
    tensorflow_lite_python.schema_py_generated = schema_py_generated
    tensorflow_lite.python = tensorflow_lite_python
    tensorflow.lite = tensorflow_lite
    sys.modules.update(
        {
            "tensorflow": tensorflow,
            "tensorflow.lite": tensorflow_lite,
            "tensorflow.lite.python": tensorflow_lite_python,
            "tensorflow.lite.python.schema_py_generated": schema_py_generated,
        },
    )


def main() -> None:
    args = parse_args()
    if args.cache_length <= 1:
        raise ValueError("--cache-length must be greater than one")
    prefill_lengths = args.prefill_lengths or [128, 512, 798]
    if any(length <= 0 or length >= args.cache_length for length in prefill_lengths):
        raise ValueError("prefill lengths must be positive and shorter than the cache")

    try:
        install_tensorflow_schema_compat()
        from litert_torch.generative.utilities import converter
        from litert_torch.generative.utilities import export_config
    except ImportError as error:
        raise RuntimeError(
            "LiteRT Torch v0.9.1 with its Generative API is required. "
            "Run this exporter on Linux; numerical verification remains "
            "available on macOS.",
        ) from error

    snapshot = args.checkpoint.expanduser().resolve()
    tokenizer = (
        args.tokenizer_json.expanduser().resolve()
        if args.tokenizer_json
        else snapshot / "tokenizer.json"
    )
    if args.format == "litertlm" and not tokenizer.is_file():
        raise FileNotFoundError(
            f"LiteRT-LM packaging requires a tokenizer JSON: {tokenizer}",
        )
    output_dir = args.output_dir.expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    config = GlmOcrDecoderConfig(max_seq_len=args.cache_length)
    model = GlmOcrLiteRtDecoder(
        config,
        decode_rope_delta=args.decode_rope_delta,
    )
    load_hugging_face_weights(model, snapshot)
    model.eval()
    options = export_config.ExportConfig(
        output_logits_on_prefill=args.output_prefill_logits,
        mask_as_input=False,
    )
    print(
        f"[export] float authoring graph: prefill={prefill_lengths}, "
        f"cache={args.cache_length}, decode_rope_delta={args.decode_rope_delta}",
        flush=True,
    )
    kwargs = {}
    if args.format == "litertlm":
        kwargs.update(
            {
                "hf_tokenizer_model_path": str(tokenizer),
                "stop_token_ids": [59_246, 59_253],
                "llm_model_type": "generic",
            },
        )
        chat_template = snapshot / "chat_template.jinja"
        if chat_template.is_file():
            kwargs["jinja_prompt_template"] = chat_template.read_text(
                encoding="utf-8",
            )
    converter.convert_to_litert(
        model,
        output_path=str(output_dir),
        output_name_prefix=args.output_prefix,
        prefill_seq_len=prefill_lengths,
        kv_cache_max_len=args.cache_length,
        quantize=args.quantize,
        config=model.config,
        export_config=options,
        output_format=args.format,
        **kwargs,
    )
    print(f"[export] wrote {args.format} artifact under {output_dir}", flush=True)


if __name__ == "__main__":
    main()
