#!/usr/bin/env python3
"""Export the fixed-shape GLM-OCR pixel vision tower for LiteRT."""

from __future__ import annotations

import argparse
from pathlib import Path

import torch
import torch.nn.functional as F

from transformers.models.glm_ocr.modeling_glm_ocr import (
    apply_rotary_pos_emb_vision,
)


DEFAULT_SNAPSHOT = (
    Path.home()
    / ".cache/huggingface/hub/models--zai-org--GLM-OCR/snapshots"
    / "ca5d8b3e287e52589e37c28385d9655ee4372f9d"
)


class FixedVisionPatchEmbed(torch.nn.Module):
    """Expose the Conv3D patch embedding as a CPU-friendly signature."""

    def __init__(self, visual: torch.nn.Module):
        super().__init__()
        self.patch_embed = visual.patch_embed

    def forward(self, pixel_values: torch.Tensor) -> torch.Tensor:
        return self.patch_embed(pixel_values)


def fixed_position_embeddings(
    visual: torch.nn.Module,
    grid_height: int,
    grid_width: int,
) -> tuple[torch.Tensor, torch.Tensor]:
    merge = visual.spatial_merge_size
    height_ids = torch.arange(grid_height).reshape(
        grid_height // merge,
        merge,
        1,
        1,
    ).expand(-1, -1, grid_width // merge, merge)
    width_ids = torch.arange(grid_width).reshape(
        1,
        1,
        grid_width // merge,
        merge,
    ).expand(grid_height // merge, merge, -1, -1)
    position_ids = torch.stack(
        (height_ids.reshape(-1), width_ids.reshape(-1)),
        dim=-1,
    )
    rotary_emb = visual.rotary_pos_emb(position_ids)
    embedding = torch.cat((rotary_emb, rotary_emb), dim=-1)
    return embedding.cos(), embedding.sin()


class FixedVisionBlock(torch.nn.Module):
    """Expose one transformer block so CPU/GPU placement is explicit."""

    def __init__(
        self,
        block: torch.nn.Module,
        position_cos: torch.Tensor,
        position_sin: torch.Tensor,
        attention_query_chunk_size: int,
    ):
        super().__init__()
        self.block = block
        self.attention_query_chunk_size = attention_query_chunk_size
        self.register_buffer("position_cos", position_cos, persistent=False)
        self.register_buffer("position_sin", position_sin, persistent=False)

    def forward(self, hidden_states: torch.Tensor) -> torch.Tensor:
        block = self.block
        residual = hidden_states
        normalized = block.norm1(hidden_states)
        sequence_length = normalized.shape[0]
        query, key, value = (
            block.attn.qkv(normalized)
            .reshape(sequence_length, 3, block.attn.num_heads, -1)
            .permute(1, 0, 2, 3)
            .unbind(0)
        )
        query = block.attn.q_norm(query)
        key = block.attn.k_norm(key)
        query, key = apply_rotary_pos_emb_vision(
            query,
            key,
            self.position_cos,
            self.position_sin,
        )
        query = query.transpose(0, 1).unsqueeze(0)
        key = key.transpose(0, 1).unsqueeze(0)
        value = value.transpose(0, 1).unsqueeze(0)
        transposed_key = key.transpose(-2, -1)
        attended_chunks = []
        for start in range(0, sequence_length, self.attention_query_chunk_size):
            query_chunk = query[
                :,
                :,
                start : start + self.attention_query_chunk_size,
                :,
            ]
            attention = F.softmax(
                torch.matmul(query_chunk, transposed_key) * block.attn.scaling,
                dim=-1,
            )
            attended_chunks.append(torch.matmul(attention, value))
        attended = torch.cat(attended_chunks, dim=2).transpose(1, 2)
        hidden_states = residual + block.attn.proj(
            attended.reshape(sequence_length, -1)
        )
        return hidden_states + block.mlp(block.norm2(hidden_states))


class FixedVisionHead(torch.nn.Module):
    """Apply the post-transformer normalization and spatial merger."""

    def __init__(self, visual: torch.nn.Module):
        super().__init__()
        self.post_layernorm = visual.post_layernorm
        self.downsample = visual.downsample
        self.merger = visual.merger
        self.spatial_merge_size = visual.spatial_merge_size
        self.out_hidden_size = visual.config.out_hidden_size

    def forward(self, hidden_states: torch.Tensor) -> torch.Tensor:
        hidden_states = self.post_layernorm(hidden_states)
        merge = self.spatial_merge_size
        hidden_states = hidden_states.view(
            -1,
            merge,
            merge,
            hidden_states.shape[-1],
        )
        hidden_states = hidden_states.permute(0, 3, 1, 2)
        hidden_states = self.downsample(hidden_states).view(
            -1,
            self.out_hidden_size,
        )
        return self.merger(hidden_states)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, default=DEFAULT_SNAPSHOT)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--grid-height", type=int, default=68)
    parser.add_argument("--grid-width", type=int, default=46)
    parser.add_argument("--attention-query-chunk-size", type=int, default=391)
    parser.add_argument("--lightweight-conversion", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.grid_height % 2 or args.grid_width % 2:
        raise ValueError("grid dimensions must be divisible by the merge size (2)")
    if args.attention_query_chunk_size <= 0:
        raise ValueError("attention query chunk size must be greater than zero")
    patch_count = args.grid_height * args.grid_width
    feature_count = patch_count // 4
    if feature_count != 782:
        raise ValueError(
            f"grid produces {feature_count} features; decoder requires exactly 782"
        )

    from transformers.models.glm_ocr.modeling_glm_ocr import (
        GlmOcrForConditionalGeneration,
    )
    from litert_torch._convert import interface as converter_interface

    print(f"[vision] loading {args.checkpoint}", flush=True)
    full_model = GlmOcrForConditionalGeneration.from_pretrained(
        args.checkpoint,
        dtype=torch.float32,
        low_cpu_mem_usage=True,
    )
    visual = full_model.model.visual
    patch_model = FixedVisionPatchEmbed(visual).eval()
    position_cos, position_sin = fixed_position_embeddings(
        visual,
        args.grid_height,
        args.grid_width,
    )
    block_models = [
        FixedVisionBlock(
            block,
            position_cos,
            position_sin,
            args.attention_query_chunk_size,
        ).eval()
        for block in visual.blocks
    ]
    head_model = FixedVisionHead(visual).eval()
    patch_hidden_size = visual.config.hidden_size
    del full_model

    sample = torch.zeros((patch_count, 1_176), dtype=torch.float32)
    converter = converter_interface.Converter()
    converter.add_signature(
        "patch_embed_68x46",
        patch_model,
        sample_args=(sample,),
    )
    hidden_sample = torch.zeros(
        (patch_count, patch_hidden_size),
        dtype=torch.float32,
    )
    for index, block_model in enumerate(block_models):
        converter.add_signature(
            f"vision_block_{index:02d}_68x46",
            block_model,
            sample_args=(hidden_sample,),
        )
    converter.add_signature(
        "vision_head_68x46",
        head_model,
        sample_args=(hidden_sample,),
    )
    print("[vision] converting", flush=True)
    edge_model = converter.convert(
        lightweight_conversion=args.lightweight_conversion,
        runtime_constant_folding=False if args.lightweight_conversion else None,
    )
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    edge_model.export(str(output))
    print(f"[vision] wrote {output}", flush=True)


if __name__ == "__main__":
    main()
