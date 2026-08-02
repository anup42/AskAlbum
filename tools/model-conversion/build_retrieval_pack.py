#!/usr/bin/env python3
"""Validate converted SigLIP2 artifacts, sign the exact manifest, and build .agretrieval."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def inspect_model(path: Path):
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:
        try:
            from tensorflow.lite import Interpreter
        except ImportError as error:
            raise RuntimeError("Install ai-edge-litert or tensorflow to inspect TFLite tensors") from error
    model = Interpreter(model_path=str(path))
    model.allocate_tensors()
    inputs, outputs = model.get_input_details(), model.get_output_details()
    if len(inputs) != 1 or len(outputs) != 1:
        raise RuntimeError(f"{path.name} must have exactly one input and output")
    return list(map(int, inputs[0]["shape"])), inputs[0]["dtype"].__name__, list(map(int, outputs[0]["shape"])), outputs[0]["dtype"].__name__


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--converted", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--pack-id", default="siglip2-base-p16-224")
    parser.add_argument("--pack-version", required=True)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--keystore-type", default="JKS")
    parser.add_argument("--alias", required=True)
    parser.add_argument("--minimum-similarity", type=float, required=True, help="Calibrated no-match threshold from the labeled core corpus")
    args = parser.parse_args()

    report = json.loads((args.converted / "conversion-report.json").read_text(encoding="utf-8"))
    if report["model"] != "google/siglip2-base-patch16-224" or len(report["revision"]) != 40:
        raise RuntimeError("Conversion report is not a pinned supported SigLIP2 checkpoint")
    if max(report["parity"]["imageMaxAbsError"], report["parity"]["textMaxAbsError"]) > report["parity"]["tolerance"]:
        raise RuntimeError("Conversion report does not pass parity")

    artifacts = {
        "image_encoder": args.converted / "image_encoder.tflite",
        "text_encoder": args.converted / "text_encoder.tflite",
        "tokenizer_vocab": args.converted / "tokenizer.vocab",
        "license": args.converted / "LICENSE.apache-2.0.txt",
    }
    for path in artifacts.values():
        if not path.is_file() or report["files"].get(path.name) != digest(path):
            raise RuntimeError(f"Converted artifact changed after parity validation: {path.name}")

    image_shape, image_type, image_out, image_out_type = inspect_model(artifacts["image_encoder"])
    text_shape, text_type, text_out, text_out_type = inspect_model(artifacts["text_encoder"])
    if image_shape not in ([1, 3, 224, 224], [1, 224, 224, 3]) or image_type != "float32":
        raise RuntimeError(f"Unsupported image input contract: {image_shape} {image_type}")
    if text_shape != [1, 64] or text_type not in ("int32", "int64"):
        raise RuntimeError(f"Unsupported text input contract: {text_shape} {text_type}")
    if image_out != text_out or len(image_out) != 2 or image_out[0] != 1 or image_out_type != "float32" or text_out_type != "float32":
        raise RuntimeError("Image and text encoders must share one FLOAT32 embedding shape")

    info = subprocess.check_output(
        ["java", str(ROOT / "PackManifestSigner.java"), "info", str(args.keystore), args.keystore_type, args.alias],
        text=True,
        env=os.environ,
    ).strip().split("\t")
    if len(info) != 2:
        raise RuntimeError("Could not inspect pack signing key")
    algorithm, key_fingerprint = info

    files = [
        {"role": role, "name": path.name, "sizeBytes": path.stat().st_size, "sha256": digest(path)}
        for role, path in artifacts.items()
    ]
    layout = "NCHW" if image_shape == [1, 3, 224, 224] else "NHWC"
    manifest = {
        "schemaVersion": 1,
        "packId": args.pack_id,
        "packVersion": args.pack_version,
        "source": {"model": report["model"], "revision": report["revision"], "license": "apache-2.0"},
        "runtime": {"name": "LiteRT", "version": "2.1.0"},
        "embedding": {"dimension": image_out[1], "normalized": True, "minimumSimilarity": args.minimum_similarity},
        "image": {"size": 224, "layout": layout, "resize": "BICUBIC", "mean": [0.5, 0.5, 0.5], "std": [0.5, 0.5, 0.5]},
        "text": {"length": 64, "lowercase": True, "padTokenId": 0, "eosTokenId": 1, "inputType": text_type.upper()},
        "signing": {"algorithm": algorithm, "keySha256": key_fingerprint},
        "files": files,
    }
    manifest_bytes = (json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="askalbum-pack-") as temp_name:
        temp = Path(temp_name)
        manifest_path, signature_path = temp / "manifest.json", temp / "manifest.sig"
        manifest_path.write_bytes(manifest_bytes)
        subprocess.run(
            ["java", str(ROOT / "PackManifestSigner.java"), "sign", str(args.keystore), args.keystore_type, args.alias, str(manifest_path), str(signature_path)],
            check=True,
            env=os.environ,
        )
        with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            archive.write(manifest_path, "manifest.json")
            archive.write(signature_path, "manifest.sig")
            for path in artifacts.values():
                archive.write(path, path.name)
    print(json.dumps({"pack": str(args.output), "sha256": digest(args.output), "manifest": manifest}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
