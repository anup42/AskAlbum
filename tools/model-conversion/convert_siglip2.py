#!/usr/bin/env python3
"""Convert a pinned Google SigLIP2 checkpoint into two LiteRT encoders.

Development tool only. Model weights and generated artifacts belong under build/ or
another ignored directory and must never be committed.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path

MODEL_ID = "google/siglip2-base-patch16-224"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--revision", required=True, help="Exact 40-character Hugging Face commit")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if len(args.revision) != 40 or any(c not in "0123456789abcdef" for c in args.revision):
        parser.error("--revision must be an exact lowercase 40-character commit")

    import numpy as np
    import sentencepiece as spm
    import torch
    import torch.nn.functional as functional
    import litert_torch
    from huggingface_hub import snapshot_download
    from transformers import AutoModel

    args.output.mkdir(parents=True, exist_ok=True)
    checkpoint = Path(
        snapshot_download(
            MODEL_ID,
            revision=args.revision,
            allow_patterns=["*.json", "*.model", "*.safetensors", "LICENSE*", "README.md"],
        )
    )
    model = AutoModel.from_pretrained(checkpoint, local_files_only=True).eval()

    class ImageEncoder(torch.nn.Module):
        def forward(self, pixel_values):
            return functional.normalize(model.get_image_features(pixel_values=pixel_values), dim=-1)

    class TextEncoder(torch.nn.Module):
        def forward(self, input_ids):
            return functional.normalize(model.get_text_features(input_ids=input_ids), dim=-1)

    torch.manual_seed(2048)
    image_input = torch.rand(1, 3, 224, 224, dtype=torch.float32) * 2.0 - 1.0
    text_input = torch.zeros(1, 64, dtype=torch.int64)
    text_input[0, :4] = torch.tensor([1495, 1707, 1804, 1], dtype=torch.int64)

    image_wrapper = ImageEncoder().eval()
    text_wrapper = TextEncoder().eval()
    with torch.no_grad():
        expected_image = image_wrapper(image_input).cpu().numpy()
        expected_text = text_wrapper(text_input).cpu().numpy()

    image_edge = litert_torch.convert(image_wrapper, (image_input,))
    text_edge = litert_torch.convert(text_wrapper, (text_input,))
    actual_image = np.asarray(image_edge(image_input))
    actual_text = np.asarray(text_edge(text_input))
    image_error = float(np.max(np.abs(expected_image - actual_image)))
    text_error = float(np.max(np.abs(expected_text - actual_text)))
    if image_error > 5e-3 or text_error > 5e-3:
        raise RuntimeError(f"LiteRT parity failed: image={image_error}, text={text_error}")

    image_path = args.output / "image_encoder.tflite"
    text_path = args.output / "text_encoder.tflite"
    image_edge.export(str(image_path))
    text_edge.export(str(text_path))

    tokenizer_model = checkpoint / "tokenizer.model"
    tokenizer = spm.SentencePieceProcessor(model_file=str(tokenizer_model))
    vocab_path = args.output / "tokenizer.vocab"
    with vocab_path.open("w", encoding="utf-8", newline="\n") as target:
        target.write("AGTOK1\n")
        for token_id in range(tokenizer.get_piece_size()):
            piece = tokenizer.id_to_piece(token_id).encode("utf-8")
            target.write(f"{token_id}\t{tokenizer.get_score(token_id):.9g}\t")
            target.write(base64.b64encode(piece).decode("ascii") + "\n")

    license_candidates = sorted(checkpoint.glob("LICENSE*"))
    if not license_candidates:
        raise RuntimeError("The pinned checkpoint has no license file; do not package it without review")
    license_path = args.output / "LICENSE.apache-2.0.txt"
    license_path.write_bytes(license_candidates[0].read_bytes())

    dimension = int(expected_image.shape[-1])
    report = {
        "schemaVersion": 1,
        "model": MODEL_ID,
        "revision": args.revision,
        "litertTorch": getattr(litert_torch, "__version__", "unknown"),
        "image": {"shape": [1, 3, 224, 224], "layout": "NCHW", "dtype": "FLOAT32"},
        "text": {"shape": [1, 64], "dtype": "INT64"},
        "output": {"shape": [1, dimension], "dtype": "FLOAT32", "normalized": True},
        "parity": {"imageMaxAbsError": image_error, "textMaxAbsError": text_error, "tolerance": 5e-3},
        "files": {path.name: sha256(path) for path in [image_path, text_path, vocab_path, license_path]},
    }
    (args.output / "conversion-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
