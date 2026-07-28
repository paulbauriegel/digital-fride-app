#!/usr/bin/env python3
"""Export fixed-shape GLM-OCR components to regular LiteRT .tflite graphs.

This deliberately uses the LiteRT Torch Converter, not LiteRT-LM. The exported
baseline has no KV cache: the Android caller must assemble image/text embeddings
and run the fixed-length decoder again for every generated token.
"""

from __future__ import annotations

import argparse
import gc
import json
import platform
import sys
from pathlib import Path


DEFAULT_MODEL = "zai-org/GLM-OCR"
DEFAULT_PROMPT = "Text Recognition:"
COMPONENTS = ("vision", "embedder", "decoder")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Convert GLM-OCR into fixed-shape regular LiteRT component graphs.",
    )
    parser.add_argument("--image", type=Path, required=True, help="Calibration receipt image")
    parser.add_argument("--output-dir", type=Path, required=True, help="New output directory")
    parser.add_argument(
        "--model",
        default=DEFAULT_MODEL,
        help=f"Source model (default: {DEFAULT_MODEL})",
    )
    parser.add_argument("--revision", help="Optional Hugging Face model revision")
    parser.add_argument("--prompt", default=DEFAULT_PROMPT, help="Fixed OCR prompt")
    parser.add_argument(
        "--sequence-length",
        type=positive_int,
        default=1536,
        help="Fixed decoder sequence length, including image and output tokens (default: 1536)",
    )
    parser.add_argument(
        "--dtype",
        choices=("float16", "float32"),
        default="float16",
        help="Export weight/activation type (default: float16)",
    )
    parser.add_argument(
        "--component",
        choices=("all", *COMPONENTS),
        default="all",
        help="Export all graphs or one graph (default: all)",
    )
    parser.add_argument(
        "--trust-remote-code",
        action="store_true",
        help="Allow custom Hugging Face model code (not required by the pinned upstream model)",
    )
    return parser.parse_args()


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be greater than zero")
    return parsed


def require_linux() -> None:
    if platform.system() != "Linux":
        raise RuntimeError(
            "litert-torch conversion is supported on Linux. Run this script on a Linux host "
            "or with the provided linux/amd64 Docker image.",
        )


def selected_components(selection: str) -> tuple[str, ...]:
    return COMPONENTS if selection == "all" else (selection,)


def run_conversion(args: argparse.Namespace) -> None:
    require_linux()
    image_path = args.image.expanduser().resolve()
    if not image_path.is_file():
        raise RuntimeError(f"Calibration image does not exist: {image_path}")

    output_dir = args.output_dir.expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=False)

    try:
        import torch
        from PIL import Image
        from torch import nn
        from transformers import AutoProcessor, GlmOcrForConditionalGeneration
        import litert_torch
    except ImportError as error:
        raise RuntimeError(
            "Missing conversion dependencies. Install requirements.txt in a Python 3.12 "
            "Linux environment.",
        ) from error

    dtype = torch.float16 if args.dtype == "float16" else torch.float32
    load_options = {
        "revision": args.revision,
        "trust_remote_code": args.trust_remote_code,
    }
    load_options = {key: value for key, value in load_options.items() if value is not None}

    print(f"Loading processor: {args.model}", flush=True)
    processor = AutoProcessor.from_pretrained(args.model, **load_options)
    print(f"Loading model: {args.model}", flush=True)
    model = GlmOcrForConditionalGeneration.from_pretrained(
        args.model,
        torch_dtype=dtype,
        device_map="cpu",
        low_cpu_mem_usage=True,
        **load_options,
    ).eval()

    # Avoid fused attention kernels that cannot be represented by portable LiteRT ops.
    model.config.text_config._attn_implementation = "eager"
    model.config.vision_config._attn_implementation = "eager"
    model.model.language_model.config._attn_implementation = "eager"
    model.model.visual.config._attn_implementation = "eager"

    image = Image.open(image_path).convert("RGB")
    messages = [
        {
            "role": "user",
            "content": [
                {"type": "image", "url": str(image_path)},
                {"type": "text", "text": args.prompt},
            ],
        },
    ]
    batch = processor.apply_chat_template(
        messages,
        tokenize=True,
        add_generation_prompt=True,
        return_dict=True,
        return_tensors="pt",
    )
    required_keys = (
        "input_ids",
        "attention_mask",
        "pixel_values",
        "image_grid_thw",
        "mm_token_type_ids",
    )
    missing_keys = [key for key in required_keys if key not in batch]
    if missing_keys:
        raise RuntimeError(f"Processor did not return required tensors: {', '.join(missing_keys)}")

    prompt_input_ids = batch["input_ids"].to(torch.long)
    prompt_attention_mask = batch["attention_mask"].to(torch.long)
    mm_token_type_ids = batch["mm_token_type_ids"].to(torch.long)
    pixel_values = batch["pixel_values"].to(dtype)
    image_grid_thw = batch["image_grid_thw"].to(torch.long)

    if prompt_input_ids.shape[0] != 1 or image_grid_thw.shape != (1, 3):
        raise RuntimeError("This baseline exporter supports exactly one image and batch size one")

    prompt_length = int(prompt_attention_mask.sum().item())
    if prompt_length >= args.sequence_length:
        raise RuntimeError(
            f"Prompt and image consume {prompt_length} tokens, which leaves no output capacity "
            f"inside --sequence-length {args.sequence_length}",
        )

    image_token_id = int(model.config.image_token_id)
    image_token_indices = torch.nonzero(
        prompt_input_ids[0] == image_token_id,
        as_tuple=False,
    ).flatten()
    expected_image_tokens = int(
        image_grid_thw.prod(dim=-1).item() // model.config.vision_config.spatial_merge_size**2
    )
    if image_token_indices.numel() != expected_image_tokens:
        raise RuntimeError(
            "Image placeholder count does not match the vision encoder output: "
            f"{image_token_indices.numel()} versus {expected_image_tokens}",
        )

    padded_input_ids = torch.full(
        (1, args.sequence_length),
        int(model.config.text_config.pad_token_id),
        dtype=torch.long,
    )
    padded_input_ids[:, :prompt_length] = prompt_input_ids[:, :prompt_length]

    prompt_positions, rope_delta = model.model.get_rope_index(
        input_ids=prompt_input_ids,
        mm_token_type_ids=mm_token_type_ids,
        image_grid_thw=image_grid_thw,
        attention_mask=prompt_attention_mask,
    )
    position_ids = torch.zeros((3, 1, args.sequence_length), dtype=torch.long)
    position_ids[:, :, :prompt_length] = prompt_positions[:, :, :prompt_length]
    generated_positions = (
        torch.arange(prompt_length, args.sequence_length, dtype=torch.long)
        + int(rope_delta[0, 0].item())
    )
    position_ids[:, :, prompt_length:] = generated_positions.view(1, 1, -1).expand(3, 1, -1)

    class FixedVisionEncoder(nn.Module):
        def __init__(self, visual: nn.Module, grid_values: tuple[int, int, int]):
            super().__init__()
            self.visual = visual
            self.grid_values = grid_values

        def forward(self, patches: torch.Tensor) -> torch.Tensor:
            grid = torch.tensor([self.grid_values], dtype=torch.long, device=patches.device)
            return self.visual(patches, grid_thw=grid).pooler_output

    class TokenEmbedder(nn.Module):
        def __init__(self, embedding: nn.Module):
            super().__init__()
            self.embedding = embedding

        def forward(self, input_ids: torch.Tensor) -> torch.Tensor:
            return self.embedding(input_ids)

    class NoCacheDecoderStep(nn.Module):
        def __init__(self, language_model: nn.Module, lm_head: nn.Module):
            super().__init__()
            self.language_model = language_model
            self.lm_head = lm_head

        def forward(
            self,
            inputs_embeds: torch.Tensor,
            positions: torch.Tensor,
            additive_attention_mask: torch.Tensor,
            logit_index: torch.Tensor,
        ) -> torch.Tensor:
            hidden_states = inputs_embeds
            position_embeddings = self.language_model.rotary_emb(
                hidden_states,
                position_ids=positions,
            )
            for layer in self.language_model.layers:
                hidden_states = layer(
                    hidden_states,
                    attention_mask=additive_attention_mask,
                    position_embeddings=position_embeddings,
                    use_cache=False,
                )
            hidden_states = self.language_model.norm(hidden_states)
            selected = torch.index_select(hidden_states, 1, logit_index)
            return self.lm_head(selected)

    def export_component(
        name: str,
        module: nn.Module,
        sample_inputs: tuple[torch.Tensor, ...],
    ) -> None:
        target = output_dir / f"glm_ocr_{name}.tflite"
        print(f"Converting {name} -> {target.name}", flush=True)
        edge_model = litert_torch.convert(module.eval(), sample_inputs)
        edge_model.export(str(target))
        del edge_model
        gc.collect()

    components = selected_components(args.component)
    grid_values = tuple(int(value) for value in image_grid_thw[0].tolist())

    if "vision" in components:
        export_component(
            "vision",
            FixedVisionEncoder(model.model.visual, grid_values),
            (pixel_values,),
        )

    if "embedder" in components:
        export_component(
            "embedder",
            TokenEmbedder(model.get_input_embeddings()),
            (padded_input_ids,),
        )

    if "decoder" in components:
        sample_embeddings = torch.zeros(
            (1, args.sequence_length, model.config.text_config.hidden_size),
            dtype=dtype,
        )
        sample_mask = torch.full(
            (1, 1, args.sequence_length, args.sequence_length),
            torch.finfo(dtype).min,
            dtype=dtype,
        )
        prompt_causal = torch.tril(
            torch.ones((prompt_length, prompt_length), dtype=torch.bool),
        )
        sample_mask[0, 0, :prompt_length, :prompt_length].masked_fill_(prompt_causal, 0)
        sample_logit_index = torch.tensor([prompt_length - 1], dtype=torch.long)
        export_component(
            "decoder",
            NoCacheDecoderStep(model.model.language_model, model.lm_head),
            (sample_embeddings, position_ids, sample_mask, sample_logit_index),
        )

    assets_dir = output_dir / "processor"
    processor.save_pretrained(assets_dir)
    manifest = {
        "format": "regular-litert-component-bundle",
        "format_version": 1,
        "source_model": args.model,
        "source_revision": args.revision,
        "dtype": args.dtype,
        "prompt": args.prompt,
        "sequence_length": args.sequence_length,
        "prompt_length": prompt_length,
        "maximum_generated_tokens": args.sequence_length - prompt_length,
        "image_grid_thw": list(grid_values),
        "pixel_values_shape": list(pixel_values.shape),
        "image_token_id": image_token_id,
        "image_token_indices": image_token_indices.tolist(),
        "prompt_input_ids": prompt_input_ids[0, :prompt_length].tolist(),
        "prompt_mm_token_type_ids": mm_token_type_ids[0, :prompt_length].tolist(),
        "position_ids": position_ids[:, 0, :].tolist(),
        "graphs": {
            name: f"glm_ocr_{name}.tflite"
            for name in components
        },
        "decoder_contract": {
            "uses_kv_cache": False,
            "inputs": [
                "inputs_embeds[1,sequence_length,1536]",
                "position_ids[3,1,sequence_length]",
                "additive_attention_mask[1,1,sequence_length,sequence_length]",
                "logit_index[1]",
            ],
            "output": "logits[1,1,59392]",
        },
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Wrote conversion bundle: {output_dir}", flush=True)


def main() -> int:
    args = parse_args()
    run_conversion(args)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
