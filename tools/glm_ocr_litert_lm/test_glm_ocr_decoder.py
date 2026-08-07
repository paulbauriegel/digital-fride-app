#!/usr/bin/env python3
"""Structural tests for the GLM-OCR LiteRT-LM decoder."""

from __future__ import annotations

import unittest

import torch

from glm_ocr_decoder import (
    GlmOcrDecoderConfig,
    GlmOcrLiteRtDecoder,
    GlmOcrVisionFeatureDecoder,
    build_single_image_layout,
    make_kv_cache,
)


class GlmOcrDecoderTest(unittest.TestCase):
    def setUp(self) -> None:
        torch.manual_seed(7)
        self.config = GlmOcrDecoderConfig(
            vocab_size=64,
            hidden_size=32,
            intermediate_size=64,
            num_layers=2,
            num_attention_heads=4,
            num_key_value_heads=2,
            head_dim=8,
            mrope_section=(1, 1, 2),
            max_seq_len=16,
            pad_token_id=0,
            eos_token_ids=(1, 2),
        )
        self.model = GlmOcrLiteRtDecoder(self.config).float().eval()

    def run_full_context(self, tokens: torch.Tensor) -> torch.Tensor:
        positions = torch.arange(tokens.shape[1], dtype=torch.int32)
        cache = make_kv_cache(self.config)
        return self.model(
            tokens=tokens,
            input_pos=positions,
            kv_cache=cache,
        )["logits"]

    def test_cached_decode_matches_full_context(self) -> None:
        tokens = torch.tensor([[5, 8, 13, 21, 34, 55]], dtype=torch.long)
        prompt_length = 4
        cache = make_kv_cache(self.config)
        prefill = self.model(
            tokens=tokens[:, :prompt_length],
            input_pos=torch.arange(prompt_length, dtype=torch.int32),
            kv_cache=cache,
        )
        cache = prefill["kv_cache"]
        full = self.run_full_context(tokens[:, :prompt_length])
        torch.testing.assert_close(
            prefill["logits"][:, -1],
            full[:, -1],
            atol=1e-5,
            rtol=1e-5,
        )

        for index in range(prompt_length, tokens.shape[1]):
            cached = self.model(
                tokens=tokens[:, index : index + 1],
                input_pos=torch.tensor([index], dtype=torch.int32),
                kv_cache=cache,
            )
            cache = cached["kv_cache"]
            full = self.run_full_context(tokens[:, : index + 1])
            torch.testing.assert_close(
                cached["logits"][:, -1],
                full[:, -1],
                atol=1e-5,
                rtol=1e-5,
            )

    def test_multiaxis_position_changes_rotary_result(self) -> None:
        tokens = torch.tensor([[5, 8, 13, 21]], dtype=torch.long)
        flat_positions = torch.arange(4).reshape(1, 4).expand(3, -1, -1)
        grid_positions = flat_positions.clone()
        grid_positions[1, 0] = torch.tensor([0, 0, 1, 1])
        grid_positions[2, 0] = torch.tensor([0, 1, 0, 1])

        flat = self.model(
            tokens=tokens,
            input_pos=torch.arange(4, dtype=torch.int32),
            position_ids=flat_positions,
            kv_cache=make_kv_cache(self.config),
        )["logits"]
        grid = self.model(
            tokens=tokens,
            input_pos=torch.arange(4, dtype=torch.int32),
            position_ids=grid_positions,
            kv_cache=make_kv_cache(self.config),
        )["logits"]
        self.assertFalse(torch.equal(flat, grid))

    def test_single_image_layout_and_feature_fusion(self) -> None:
        image_token = 63
        tokens = torch.tensor(
            [[5, image_token, image_token, image_token, image_token, 8]],
            dtype=torch.long,
        )
        layout = build_single_image_layout(
            tokens,
            torch.tensor([[1, 4, 4]]),
            image_token_id=image_token,
            spatial_merge_size=2,
        )
        self.assertEqual(layout.image_token_start, 1)
        self.assertEqual(layout.image_token_count, 4)
        self.assertEqual(layout.position_ids.tolist(), [
            [[0, 1, 1, 1, 1, 3]],
            [[0, 1, 1, 2, 2, 3]],
            [[0, 1, 2, 1, 2, 3]],
        ])
        self.assertEqual(layout.rope_delta.item(), -2)

        bridge = GlmOcrVisionFeatureDecoder(
            self.model,
            image_token_start=layout.image_token_start,
            image_token_count=layout.image_token_count,
        )
        vision_features = torch.randn(4, self.config.hidden_size)
        bridged = bridge(
            tokens=tokens,
            input_pos=torch.arange(tokens.shape[1], dtype=torch.int32),
            kv_cache=make_kv_cache(self.config),
            vision_features=vision_features,
            position_ids=layout.position_ids,
            rope_delta=layout.rope_delta,
        )
        text_embeddings = self.model.tok_embedding(tokens)
        fused = torch.cat(
            (
                text_embeddings[:, :1],
                vision_features.unsqueeze(0),
                text_embeddings[:, 5:],
            ),
            dim=1,
        )
        direct = self.model(
            tokens=None,
            input_embeds=fused,
            input_pos=torch.arange(tokens.shape[1], dtype=torch.int32),
            kv_cache=make_kv_cache(self.config),
            position_ids=layout.position_ids,
            rope_delta=layout.rope_delta,
        )
        torch.testing.assert_close(bridged["logits"], direct["logits"])
        self.assertEqual(bridged["rope_delta"].item(), -2)


if __name__ == "__main__":
    unittest.main()
