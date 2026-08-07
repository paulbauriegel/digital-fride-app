#!/usr/bin/env python3
"""LiteRT-LM-targeted, reauthored GLM-OCR text decoder.

The implementation intentionally uses converter-friendly primitive PyTorch
operators.  When LiteRT Torch is installed it uses the Generative API's
external KV-cache types and dynamic-update-slice implementation.  A small
fallback cache implementation keeps numerical parity runnable on macOS, where
the LiteRT Torch converter is not supported.
"""

from __future__ import annotations

import dataclasses
from pathlib import Path
from typing import Any, Iterable

import torch
from safetensors import safe_open
from torch import nn
from torch.nn import functional as F

try:
    from litert_torch.generative.layers import kv_cache as litert_kv
    from litert_torch.generative.layers import model_config as litert_config
except ImportError:
    litert_kv = None
    litert_config = None


@dataclasses.dataclass(frozen=True)
class GlmOcrDecoderConfig:
    vocab_size: int = 59_392
    hidden_size: int = 1_536
    intermediate_size: int = 4_608
    num_layers: int = 16
    num_attention_heads: int = 16
    num_key_value_heads: int = 8
    head_dim: int = 128
    rms_norm_eps: float = 1e-5
    rope_theta: float = 10_000.0
    mrope_section: tuple[int, int, int] = (16, 24, 24)
    max_seq_len: int = 1_536
    pad_token_id: int = 59_246
    eos_token_ids: tuple[int, int] = (59_246, 59_253)
    image_token_id: int = 59_280
    spatial_merge_size: int = 2

    def validate(self) -> None:
        if self.num_attention_heads % self.num_key_value_heads:
            raise ValueError("query heads must be divisible by key/value heads")
        if sum(self.mrope_section) != self.head_dim // 2:
            raise ValueError("M-RoPE sections must cover head_dim / 2 pairs")


@dataclasses.dataclass
class FallbackKVCacheEntry:
    k_cache: torch.Tensor
    v_cache: torch.Tensor


@dataclasses.dataclass
class FallbackKVCache:
    caches: tuple[FallbackKVCacheEntry, ...]

    def flatten(self) -> list[torch.Tensor]:
        result: list[torch.Tensor] = []
        for entry in self.caches:
            result.extend((entry.k_cache, entry.v_cache))
        return result


def make_kv_cache(
    config: GlmOcrDecoderConfig,
    *,
    batch_size: int = 1,
    max_seq_len: int | None = None,
    dtype: torch.dtype = torch.float32,
    device: torch.device | str | None = None,
    use_litert_types: bool = False,
) -> Any:
    """Construct an external cache with LiteRT's [B, T, N, H] layout."""
    cache_len = max_seq_len or config.max_seq_len
    if use_litert_types:
        if litert_kv is None:
            raise RuntimeError("LiteRT Torch is not installed")
        return litert_kv.KVCache.from_model_config(
            cache_len,
            get_litert_model_config(config),
            dtype=dtype,
            device=device,
            batch_size=batch_size,
        )

    shape = (
        batch_size,
        cache_len,
        config.num_key_value_heads,
        config.head_dim,
    )
    entries = tuple(
        FallbackKVCacheEntry(
            torch.zeros(shape, dtype=dtype, device=device),
            torch.zeros(shape, dtype=dtype, device=device),
        )
        for _ in range(config.num_layers)
    )
    return FallbackKVCache(entries)


def get_litert_model_config(
    config: GlmOcrDecoderConfig | None = None,
) -> Any:
    """Build the official LiteRT Generative API config used by the converter."""
    if litert_config is None:
        raise RuntimeError(
            "LiteRT Torch is required for conversion. Install v0.9.1 on Linux.",
        )
    config = config or GlmOcrDecoderConfig()
    attention = litert_config.AttentionConfig(
        num_heads=config.num_attention_heads,
        head_dim=config.head_dim,
        num_query_groups=config.num_key_value_heads,
        rotary_base=int(config.rope_theta),
        rotary_percentage=1.0,
        qkv_use_bias=False,
        output_proj_use_bias=False,
        causal_mask_value=-2.3819763e38,
    )
    feed_forward = litert_config.FeedForwardConfig(
        type=litert_config.FeedForwardType.GATED,
        activation=litert_config.ActivationConfig(
            litert_config.ActivationType.SILU,
        ),
        intermediate_size=config.intermediate_size,
    )
    norm = litert_config.NormalizationConfig(
        type=litert_config.NormalizationType.RMS_NORM,
        epsilon=config.rms_norm_eps,
        enable_hlfb=False,
    )
    block = litert_config.TransformerBlockConfig(
        attn_config=attention,
        ff_config=feed_forward,
        pre_attention_norm_config=norm,
        post_attention_norm_config=norm,
    )
    return litert_config.ModelConfig(
        vocab_size=config.vocab_size,
        num_layers=config.num_layers,
        max_seq_len=config.max_seq_len,
        embedding_dim=config.hidden_size,
        block_configs=block,
        final_norm_config=norm,
        lm_head_use_bias=False,
        lm_head_share_weight_with_embedding=False,
        enable_hlfb=False,
    )


class GlmOcrRMSNorm(nn.Module):
    def __init__(self, size: int, epsilon: float) -> None:
        super().__init__()
        self.weight = nn.Parameter(torch.ones(size))
        self.epsilon = epsilon

    def forward(self, value: torch.Tensor) -> torch.Tensor:
        input_dtype = value.dtype
        value_float = value.float()
        variance = value_float.square().mean(dim=-1, keepdim=True)
        normalized = value_float * torch.rsqrt(variance + self.epsilon)
        return normalized.to(input_dtype) * self.weight


def linear_in_input_dtype(
    layer: nn.Linear,
    value: torch.Tensor,
) -> torch.Tensor:
    """Run a linear layer in activation dtype with compact stored weights."""
    weight = layer.weight
    if weight.dtype != value.dtype:
        weight = weight.to(value.dtype)
    bias = layer.bias
    if bias is not None and bias.dtype != value.dtype:
        bias = bias.to(value.dtype)
    return F.linear(value, weight, bias)


def build_mrope(
    position_ids: torch.Tensor,
    *,
    head_dim: int,
    theta: float,
    sections: tuple[int, int, int],
    dtype: torch.dtype,
) -> tuple[torch.Tensor, torch.Tensor]:
    """Build GLM-OCR's interleaved temporal/height/width rotary values."""
    if position_ids.ndim == 2:
        position_ids = position_ids.unsqueeze(0).expand(3, -1, -1)
    if position_ids.ndim != 3 or position_ids.shape[0] != 3:
        raise ValueError(
            "position_ids must have shape [3, batch, sequence] or [batch, sequence]",
        )

    frequency_count = head_dim // 2
    exponent = torch.arange(
        frequency_count,
        dtype=torch.float32,
        device=position_ids.device,
    )
    inverse_frequency = 1.0 / (theta ** (2.0 * exponent / head_dim))
    frequencies = (
        position_ids.float().unsqueeze(-1) * inverse_frequency.reshape(1, 1, 1, -1)
    )
    chunks = frequencies.split(sections, dim=-1)
    selected = torch.cat(
        tuple(chunk[index % 3] for index, chunk in enumerate(chunks)),
        dim=-1,
    )
    selected = selected.repeat_interleave(2, dim=-1)
    return selected.cos().to(dtype), selected.sin().to(dtype)


def apply_mrope(
    value: torch.Tensor,
    cos: torch.Tensor,
    sin: torch.Tensor,
) -> torch.Tensor:
    """Apply GLM-OCR's even/odd rotary pairing to [B, T, N, H]."""
    cos = cos.unsqueeze(2)
    sin = sin.unsqueeze(2)
    even = value[..., 0::2]
    odd = value[..., 1::2]
    rotated = torch.stack((-odd, even), dim=-1).flatten(-2)
    return value * cos + rotated * sin


@dataclasses.dataclass(frozen=True)
class SingleImageLayout:
    """Host-side multimodal layout passed to the static LiteRT signatures."""

    image_token_start: int
    image_token_count: int
    grid_thw: tuple[int, int, int]
    sequence_length: int
    position_ids: torch.Tensor
    rope_delta: torch.Tensor


def build_single_image_layout(
    tokens: torch.Tensor,
    image_grid_thw: torch.Tensor,
    *,
    image_token_id: int = 59_280,
    spatial_merge_size: int = 2,
) -> SingleImageLayout:
    """Build GLM-OCR positions for one contiguous image placeholder run.

    This deliberately runs on the host. LiteRT signatures remain static while
    the derived position tensor and scalar delta are explicit runtime inputs.
    """
    if tokens.ndim != 2 or tokens.shape[0] != 1:
        raise ValueError("single-image layout currently requires tokens shaped [1, T]")
    if image_grid_thw.numel() != 3:
        raise ValueError("image_grid_thw must contain exactly [T, H, W]")
    image_indices = torch.nonzero(
        tokens[0] == image_token_id,
        as_tuple=False,
    ).flatten()
    if image_indices.numel() == 0:
        raise ValueError(f"prompt contains no image token {image_token_id}")
    expected = torch.arange(
        int(image_indices[0]),
        int(image_indices[0]) + image_indices.numel(),
        device=image_indices.device,
    )
    if not torch.equal(image_indices, expected):
        raise ValueError("image placeholders must form one contiguous run")

    grid = tuple(int(value) for value in image_grid_thw.reshape(-1).tolist())
    grid_t = grid[0]
    grid_h = grid[1] // spatial_merge_size
    grid_w = grid[2] // spatial_merge_size
    image_count = grid_t * grid_h * grid_w
    if image_count != image_indices.numel():
        raise ValueError(
            f"grid produces {image_count} features but prompt has "
            f"{image_indices.numel()} image placeholders",
        )

    start = int(image_indices[0])
    prefix = torch.arange(start, dtype=tokens.dtype, device=tokens.device)
    prefix = prefix.reshape(1, -1).expand(3, -1)
    temporal = torch.arange(grid_t, dtype=tokens.dtype, device=tokens.device)
    height = (
        torch.arange(grid_h, dtype=tokens.dtype, device=tokens.device) + start
    )
    width = (
        torch.arange(grid_w, dtype=tokens.dtype, device=tokens.device) + start
    )
    temporal_grid, height_grid, width_grid = torch.meshgrid(
        temporal,
        height,
        width,
        indexing="ij",
    )
    vision_positions = torch.stack(
        (temporal_grid + start, height_grid, width_grid),
        dim=0,
    ).reshape(3, -1)

    suffix_count = tokens.shape[1] - start - image_count
    suffix_start = start + max(grid_h, grid_w)
    suffix = (
        torch.arange(suffix_count, dtype=tokens.dtype, device=tokens.device)
        + suffix_start
    )
    suffix = suffix.reshape(1, -1).expand(3, -1)
    position_ids = torch.cat((prefix, vision_positions, suffix), dim=1).unsqueeze(1)
    rope_delta = (
        position_ids.max() + 1 - tokens.shape[1]
    ).reshape(1, 1)
    return SingleImageLayout(
        image_token_start=start,
        image_token_count=image_count,
        grid_thw=grid,
        sequence_length=tokens.shape[1],
        position_ids=position_ids,
        rope_delta=rope_delta,
    )


def _update_cache(
    entry: Any,
    input_pos: torch.Tensor,
    keys: torch.Tensor,
    values: torch.Tensor,
) -> Any:
    if litert_kv is not None and isinstance(entry, litert_kv.KVCacheEntry):
        return litert_kv.update(entry, input_pos, keys, values)
    positions = input_pos.to(torch.long)
    return FallbackKVCacheEntry(
        entry.k_cache.index_copy(1, positions, keys),
        entry.v_cache.index_copy(1, positions, values),
    )


class GlmOcrAttention(nn.Module):
    def __init__(self, config: GlmOcrDecoderConfig) -> None:
        super().__init__()
        self.config = config
        self.q_proj = nn.Linear(
            config.hidden_size,
            config.num_attention_heads * config.head_dim,
            bias=False,
        )
        self.k_proj = nn.Linear(
            config.hidden_size,
            config.num_key_value_heads * config.head_dim,
            bias=False,
        )
        self.v_proj = nn.Linear(
            config.hidden_size,
            config.num_key_value_heads * config.head_dim,
            bias=False,
        )
        self.o_proj = nn.Linear(
            config.num_attention_heads * config.head_dim,
            config.hidden_size,
            bias=False,
        )

    def forward(
        self,
        value: torch.Tensor,
        cos: torch.Tensor,
        sin: torch.Tensor,
        mask: torch.Tensor,
        input_pos: torch.Tensor,
        cache_entry: Any,
    ) -> tuple[torch.Tensor, Any]:
        batch, sequence, _ = value.shape
        query = linear_in_input_dtype(self.q_proj, value).reshape(
            batch,
            sequence,
            self.config.num_attention_heads,
            self.config.head_dim,
        )
        key = linear_in_input_dtype(self.k_proj, value).reshape(
            batch,
            sequence,
            self.config.num_key_value_heads,
            self.config.head_dim,
        )
        current_value = linear_in_input_dtype(self.v_proj, value).reshape(
            batch,
            sequence,
            self.config.num_key_value_heads,
            self.config.head_dim,
        )
        query = apply_mrope(query, cos, sin)
        key = apply_mrope(key, cos, sin)

        updated_cache = _update_cache(
            cache_entry,
            input_pos,
            key,
            current_value,
        )
        key = updated_cache.k_cache
        current_value = updated_cache.v_cache
        groups = self.config.num_attention_heads // self.config.num_key_value_heads
        key = key.repeat_interleave(groups, dim=2)
        current_value = current_value.repeat_interleave(groups, dim=2)

        query = query.transpose(1, 2)
        key = key.transpose(1, 2)
        current_value = current_value.transpose(1, 2)
        scores = torch.matmul(query, key.transpose(-2, -1))
        scores = scores * (self.config.head_dim**-0.5)
        scores = scores + mask
        probabilities = torch.softmax(scores, dim=-1, dtype=torch.float32).to(
            query.dtype,
        )
        attended = torch.matmul(probabilities, current_value)
        attended = attended.transpose(1, 2).reshape(batch, sequence, -1)
        return linear_in_input_dtype(self.o_proj, attended), updated_cache


class GlmOcrMLP(nn.Module):
    def __init__(self, config: GlmOcrDecoderConfig) -> None:
        super().__init__()
        self.gate_up_proj = nn.Linear(
            config.hidden_size,
            config.intermediate_size * 2,
            bias=False,
        )
        self.down_proj = nn.Linear(
            config.intermediate_size,
            config.hidden_size,
            bias=False,
        )

    def forward(self, value: torch.Tensor) -> torch.Tensor:
        gate, up = linear_in_input_dtype(self.gate_up_proj, value).chunk(
            2,
            dim=-1,
        )
        return linear_in_input_dtype(self.down_proj, F.silu(gate) * up)


class GlmOcrDecoderBlock(nn.Module):
    def __init__(self, config: GlmOcrDecoderConfig) -> None:
        super().__init__()
        self.self_attn = GlmOcrAttention(config)
        self.mlp = GlmOcrMLP(config)
        self.input_layernorm = GlmOcrRMSNorm(
            config.hidden_size,
            config.rms_norm_eps,
        )
        self.post_attention_layernorm = GlmOcrRMSNorm(
            config.hidden_size,
            config.rms_norm_eps,
        )
        self.post_self_attn_layernorm = GlmOcrRMSNorm(
            config.hidden_size,
            config.rms_norm_eps,
        )
        self.post_mlp_layernorm = GlmOcrRMSNorm(
            config.hidden_size,
            config.rms_norm_eps,
        )

    def forward(
        self,
        value: torch.Tensor,
        cos: torch.Tensor,
        sin: torch.Tensor,
        mask: torch.Tensor,
        input_pos: torch.Tensor,
        cache_entry: Any,
    ) -> tuple[torch.Tensor, Any]:
        residual = value
        value, cache_entry = self.self_attn(
            self.input_layernorm(value),
            cos,
            sin,
            mask,
            input_pos,
            cache_entry,
        )
        value = residual + self.post_self_attn_layernorm(value)

        residual = value
        value = self.mlp(self.post_attention_layernorm(value))
        value = residual + self.post_mlp_layernorm(value)
        return value, cache_entry


class GlmOcrLiteRtDecoder(nn.Module):
    """GLM-OCR decoder with LiteRT-LM prefill/decode signature semantics."""

    def __init__(
        self,
        config: GlmOcrDecoderConfig | None = None,
        *,
        decode_rope_delta: int = 0,
    ) -> None:
        super().__init__()
        self.glm_config = config or GlmOcrDecoderConfig()
        self.glm_config.validate()
        self.config = (
            get_litert_model_config(self.glm_config)
            if litert_config is not None
            else self.glm_config
        )
        self.decode_rope_delta = decode_rope_delta
        self.compute_dtype: torch.dtype | None = None
        self.prefill_last_logits_only = False
        self.tok_embedding = nn.Embedding(
            self.glm_config.vocab_size,
            self.glm_config.hidden_size,
            padding_idx=self.glm_config.pad_token_id,
        )
        self.transformer_blocks = nn.ModuleList(
            GlmOcrDecoderBlock(self.glm_config)
            for _ in range(self.glm_config.num_layers)
        )
        self.final_norm = GlmOcrRMSNorm(
            self.glm_config.hidden_size,
            self.glm_config.rms_norm_eps,
        )
        self.lm_head = nn.Linear(
            self.glm_config.hidden_size,
            self.glm_config.vocab_size,
            bias=False,
        )

    def _causal_mask(
        self,
        input_pos: torch.Tensor,
        cache_length: int,
        dtype: torch.dtype,
    ) -> torch.Tensor:
        key_positions = torch.arange(
            cache_length,
            device=input_pos.device,
        ).reshape(1, -1)
        blocked = key_positions > input_pos.reshape(-1, 1)
        zeros = torch.zeros(blocked.shape, device=input_pos.device, dtype=torch.float32)
        negative = torch.full_like(zeros, -2.3819763e38)
        return torch.where(blocked, negative, zeros).reshape(
            1,
            1,
            input_pos.numel(),
            cache_length,
        ).to(dtype)

    @torch.inference_mode()
    def forward(
        self,
        tokens: torch.Tensor | None,
        input_pos: torch.Tensor,
        kv_cache: Any,
        input_embeds: torch.Tensor | None = None,
        position_ids: torch.Tensor | None = None,
        rope_delta: torch.Tensor | None = None,
        mask: torch.Tensor | None = None,
        export_config: Any | None = None,
    ) -> dict[str, Any]:
        if input_embeds is None:
            if tokens is None:
                raise ValueError("tokens or input_embeds must be provided")
            value = self.tok_embedding(tokens)
        else:
            value = input_embeds
        value = value.to(self.compute_dtype or self.final_norm.weight.dtype)

        if position_ids is None:
            rope_positions = input_pos
            if input_pos.numel() == 1:
                if rope_delta is None:
                    rope_positions = rope_positions + self.decode_rope_delta
                else:
                    rope_positions = rope_positions + rope_delta.reshape(-1)[0]
            position_ids = rope_positions.reshape(1, -1).expand(
                value.shape[0],
                -1,
            )

        cos, sin = build_mrope(
            position_ids,
            head_dim=self.glm_config.head_dim,
            theta=self.glm_config.rope_theta,
            sections=self.glm_config.mrope_section,
            dtype=value.dtype,
        )
        if mask is None:
            mask = self._causal_mask(
                input_pos,
                kv_cache.caches[0].k_cache.shape[1],
                value.dtype,
            )

        updated_entries = []
        for block, cache_entry in zip(
            self.transformer_blocks,
            kv_cache.caches,
            strict=True,
        ):
            value, cache_entry = block(
                value,
                cos,
                sin,
                mask,
                input_pos,
                cache_entry,
            )
            updated_entries.append(cache_entry)

        cache_type = type(kv_cache)
        updated_cache = cache_type(tuple(updated_entries))
        if (
            export_config is not None
            and input_pos.numel() > 1
            and not export_config.output_logits_on_prefill
        ):
            return {"kv_cache": updated_cache}
        logits = linear_in_input_dtype(self.lm_head, self.final_norm(value))
        if self.prefill_last_logits_only and input_pos.numel() > 1:
            logits = logits[:, -1:, :]
        return {"logits": logits, "kv_cache": updated_cache}


class GlmOcrVisionFeatureDecoder(nn.Module):
    """Joins precomputed vision features to decoder prefill and cached decode.

    The image span is static per exported signature. Position IDs and RoPE
    delta are explicit inputs derived from the actual grid by
    ``build_single_image_layout``.
    """

    def __init__(
        self,
        decoder: GlmOcrLiteRtDecoder,
        *,
        image_token_start: int,
        image_token_count: int,
    ) -> None:
        super().__init__()
        self.decoder = decoder
        self.image_token_start = image_token_start
        self.image_token_count = image_token_count
        self.config = decoder.config

    @torch.inference_mode()
    def forward(
        self,
        tokens: torch.Tensor,
        input_pos: torch.Tensor,
        kv_cache: Any,
        vision_features: torch.Tensor | None = None,
        position_ids: torch.Tensor | None = None,
        rope_delta: torch.Tensor | None = None,
        mask: torch.Tensor | None = None,
        export_config: Any | None = None,
    ) -> dict[str, Any]:
        input_embeds = None
        if vision_features is not None:
            if vision_features.ndim == 2:
                vision_features = vision_features.unsqueeze(0)
            text_embeddings = self.decoder.tok_embedding(tokens)
            image_end = self.image_token_start + self.image_token_count
            input_embeds = torch.cat(
                (
                    text_embeddings[:, : self.image_token_start],
                    vision_features.to(text_embeddings.dtype),
                    text_embeddings[:, image_end:],
                ),
                dim=1,
            ).to(
                self.decoder.compute_dtype
                or self.decoder.final_norm.weight.dtype,
            )
        output = self.decoder(
            tokens=tokens,
            input_pos=input_pos,
            kv_cache=kv_cache,
            input_embeds=input_embeds,
            position_ids=position_ids,
            rope_delta=rope_delta,
            mask=mask,
            export_config=export_config,
        )
        if rope_delta is not None:
            output["rope_delta"] = rope_delta
        return output


def _copy_parameter(
    destination: nn.Parameter,
    source: torch.Tensor,
    *,
    name: str,
) -> None:
    if destination.shape != source.shape:
        raise ValueError(
            f"Shape mismatch for {name}: {tuple(source.shape)} -> "
            f"{tuple(destination.shape)}",
        )
    destination.data.copy_(source.to(destination.device, destination.dtype))


def load_hugging_face_weights(
    model: GlmOcrLiteRtDecoder,
    checkpoint: Path | str,
) -> None:
    """Load only the 16 deployable text layers from the HF safetensors file."""
    checkpoint = Path(checkpoint).expanduser().resolve()
    if checkpoint.is_dir():
        files = sorted(checkpoint.glob("*.safetensors"))
    else:
        files = [checkpoint]
    if not files:
        raise FileNotFoundError(f"No safetensors files found at {checkpoint}")

    tensor_to_file: dict[str, Path] = {}
    for file in files:
        with safe_open(file, framework="pt", device="cpu") as handle:
            tensor_to_file.update({name: file for name in handle.keys()})

    handles: dict[Path, Any] = {}

    def tensor(name: str) -> torch.Tensor:
        file = tensor_to_file.get(name)
        if file is None:
            raise KeyError(f"Missing checkpoint tensor: {name}")
        if file not in handles:
            handles[file] = safe_open(file, framework="pt", device="cpu")
        return handles[file].get_tensor(name)

    _copy_parameter(
        model.tok_embedding.weight,
        tensor("model.language_model.embed_tokens.weight"),
        name="tok_embedding.weight",
    )
    _copy_parameter(
        model.final_norm.weight,
        tensor("model.language_model.norm.weight"),
        name="final_norm.weight",
    )
    _copy_parameter(
        model.lm_head.weight,
        tensor("lm_head.weight"),
        name="lm_head.weight",
    )
    for index, block in enumerate(model.transformer_blocks):
        prefix = f"model.language_model.layers.{index}"
        mappings: Iterable[tuple[nn.Parameter, str]] = (
            (block.self_attn.q_proj.weight, f"{prefix}.self_attn.q_proj.weight"),
            (block.self_attn.k_proj.weight, f"{prefix}.self_attn.k_proj.weight"),
            (block.self_attn.v_proj.weight, f"{prefix}.self_attn.v_proj.weight"),
            (block.self_attn.o_proj.weight, f"{prefix}.self_attn.o_proj.weight"),
            (block.mlp.gate_up_proj.weight, f"{prefix}.mlp.gate_up_proj.weight"),
            (block.mlp.down_proj.weight, f"{prefix}.mlp.down_proj.weight"),
            (block.input_layernorm.weight, f"{prefix}.input_layernorm.weight"),
            (
                block.post_attention_layernorm.weight,
                f"{prefix}.post_attention_layernorm.weight",
            ),
            (
                block.post_self_attn_layernorm.weight,
                f"{prefix}.post_self_attn_layernorm.weight",
            ),
            (
                block.post_mlp_layernorm.weight,
                f"{prefix}.post_mlp_layernorm.weight",
            ),
        )
        for destination, name in mappings:
            _copy_parameter(destination, tensor(name), name=name)
    model.eval()
