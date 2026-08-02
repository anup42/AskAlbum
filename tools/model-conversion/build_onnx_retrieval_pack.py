#!/usr/bin/env python3
"""Validate and sign a pinned quantized SigLIP2 ONNX dual-encoder pack."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

import numpy as np
import onnxruntime as ort
import sentencepiece as spm

ROOT = Path(__file__).resolve().parent
SOURCE_MODEL = "google/siglip2-base-patch16-224"
ARTIFACT_REPOSITORY = "onnx-community/siglip2-base-patch16-224-ONNX"
ONNX_RUNTIME_VERSION = "1.23.2"


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def require_revision(value: str, label: str) -> str:
    if len(value) != 40 or any(char not in "0123456789abcdef" for char in value):
        raise RuntimeError(f"{label} must be an exact lowercase 40-character revision")
    return value


def inspect_encoder(path: Path, input_name: str, input_type: str) -> None:
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    inputs = session.get_inputs()
    outputs = {output.name: output for output in session.get_outputs()}
    if len(inputs) != 1 or inputs[0].name != input_name or inputs[0].type != input_type:
        raise RuntimeError(f"Unexpected {path.name} input contract")
    pooler = outputs.get("pooler_output")
    if pooler is None or pooler.type != "tensor(float)" or pooler.shape[-1] != 768:
        raise RuntimeError(f"Unexpected {path.name} pooler contract")
    if input_name == "pixel_values":
        sample = np.zeros((1, 3, 224, 224), dtype=np.float32)
    else:
        sample = np.zeros((1, 64), dtype=np.int64)
        sample[0, 0] = 1
    first = session.run(["pooler_output"], {input_name: sample})[0]
    second = session.run(["pooler_output"], {input_name: sample})[0]
    if first.shape != (1, 768) or not np.isfinite(first).all() or np.linalg.norm(first) <= 1e-9:
        raise RuntimeError(f"{path.name} produced an invalid embedding")
    if not np.array_equal(first, second):
        raise RuntimeError(f"{path.name} is not deterministic on the validation input")


def export_vocab(tokenizer_model: Path, output: Path) -> None:
    tokenizer = spm.SentencePieceProcessor(model_file=str(tokenizer_model))
    with output.open("w", encoding="utf-8", newline="\n") as target:
        target.write("AGTOK1\n")
        for token_id in range(tokenizer.get_piece_size()):
            piece = tokenizer.id_to_piece(token_id).encode("utf-8")
            target.write(f"{token_id}\t{tokenizer.get_score(token_id):.9g}\t")
            target.write(base64.b64encode(piece).decode("ascii") + "\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--image-model", type=Path, required=True)
    parser.add_argument("--text-model", type=Path, required=True)
    parser.add_argument("--tokenizer-model", type=Path, required=True)
    parser.add_argument("--license", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--artifact-revision", required=True)
    parser.add_argument("--pack-version", required=True)
    parser.add_argument("--minimum-similarity", type=float, required=True)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--keystore-type", default="JKS")
    parser.add_argument("--alias", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    require_revision(args.source_revision, "source revision")
    require_revision(args.artifact_revision, "artifact revision")
    if not -1.0 <= args.minimum_similarity <= 1.0:
        raise RuntimeError("minimum similarity must be within [-1, 1]")
    for path in (args.image_model, args.text_model, args.tokenizer_model, args.license):
        if not path.is_file():
            raise RuntimeError(f"Missing artifact: {path}")

    inspect_encoder(args.image_model, "pixel_values", "tensor(float)")
    inspect_encoder(args.text_model, "input_ids", "tensor(int64)")

    with tempfile.TemporaryDirectory(prefix="askalbum-onnx-pack-") as temp_name:
        temp = Path(temp_name)
        vocab = temp / "tokenizer.vocab"
        export_vocab(args.tokenizer_model, vocab)
        artifacts = {
            "image_encoder": args.image_model,
            "text_encoder": args.text_model,
            "tokenizer_vocab": vocab,
            "license": args.license,
        }
        info = subprocess.check_output(
            ["java", str(ROOT / "PackManifestSigner.java"), "info", str(args.keystore), args.keystore_type, args.alias],
            text=True,
            env=os.environ,
        ).strip().split("\t")
        if len(info) != 2:
            raise RuntimeError("Could not inspect pack signing key")
        algorithm, key_fingerprint = info
        manifest = {
            "schemaVersion": 1,
            "packId": "siglip2-base-p16-224-q8",
            "packVersion": args.pack_version,
            "source": {"model": SOURCE_MODEL, "revision": args.source_revision, "license": "apache-2.0"},
            "artifact": {"repository": ARTIFACT_REPOSITORY, "revision": args.artifact_revision},
            "runtime": {"name": "ONNX Runtime", "version": ONNX_RUNTIME_VERSION},
            "embedding": {"dimension": 768, "normalized": True, "minimumSimilarity": args.minimum_similarity},
            "image": {"size": 224, "layout": "NCHW", "resize": "BICUBIC", "mean": [0.5, 0.5, 0.5], "std": [0.5, 0.5, 0.5]},
            "text": {"length": 64, "lowercase": True, "padTokenId": 0, "eosTokenId": 1, "inputType": "INT64"},
            "signing": {"algorithm": algorithm, "keySha256": key_fingerprint},
            "files": [
                {"role": role, "name": path.name, "sizeBytes": path.stat().st_size, "sha256": digest(path)}
                for role, path in artifacts.items()
            ],
        }
        manifest_bytes = (json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
        manifest_path = temp / "manifest.json"
        signature_path = temp / "manifest.sig"
        manifest_path.write_bytes(manifest_bytes)
        subprocess.run(
            ["java", str(ROOT / "PackManifestSigner.java"), "sign", str(args.keystore), args.keystore_type, args.alias, str(manifest_path), str(signature_path)],
            check=True,
            env=os.environ,
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
            archive.write(manifest_path, "manifest.json")
            archive.write(signature_path, "manifest.sig")
            for path in artifacts.values():
                archive.write(path, path.name)

    print(json.dumps({"pack": str(args.output), "sha256": digest(args.output), "manifest": manifest}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
