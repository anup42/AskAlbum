#!/usr/bin/env python3
"""Build a signed, checksummed .agemma wrapper around an external LiteRT-LM model."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parent
MIB = 1024 * 1024
GIB = 1024 * MIB


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(MIB), b""):
            value.update(block)
    return value.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True, help="External .litertlm file; never copied into the repository")
    parser.add_argument("--license", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--tier", choices=("E2B", "E4B"), default="E2B")
    parser.add_argument("--pack-id")
    parser.add_argument("--pack-version", required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--source-license", default="gemma-terms")
    parser.add_argument("--context-tokens", type=int, default=4096)
    parser.add_argument("--minimum-ram-gib", type=int)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--keystore-type", default="JKS")
    parser.add_argument("--alias", required=True)
    args = parser.parse_args()

    if args.model.suffix != ".litertlm" or not args.model.is_file() or args.model.stat().st_size < 50 * MIB:
        raise RuntimeError("--model must be a complete .litertlm artifact of at least 50 MiB")
    if not args.license.is_file() or args.license.stat().st_size not in range(1, 2 * MIB + 1):
        raise RuntimeError("--license must be a non-empty file no larger than 2 MiB")
    if not re.fullmatch(r"[0-9a-f]{40,64}", args.source_revision):
        raise RuntimeError("--source-revision must be a pinned lowercase hexadecimal revision")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,96}", args.pack_version):
        raise RuntimeError("Unsafe pack version")
    minimum_ram = (args.minimum_ram_gib or (8 if args.tier == "E4B" else 4)) * GIB
    if args.tier == "E4B" and minimum_ram < 8 * GIB:
        raise RuntimeError("E4B minimum RAM cannot be below 8 GiB")
    if args.context_tokens not in range(1024, 8193):
        raise RuntimeError("Context tokens must be within the Android runtime bound")

    info = subprocess.check_output(
        ["java", str(ROOT / "PackManifestSigner.java"), "info", str(args.keystore), args.keystore_type, args.alias],
        text=True,
        env=os.environ,
    ).strip().split("\t")
    if len(info) != 2:
        raise RuntimeError("Could not inspect APK-compatible pack signing key")
    algorithm, key_fingerprint = info
    pack_id = args.pack_id or f"gemma-4-{args.tier.lower()}"
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,96}", pack_id):
        raise RuntimeError("Unsafe pack ID")
    artifacts = {"model": args.model, "license": args.license}
    files = [
        {"role": role, "name": path.name, "sizeBytes": path.stat().st_size, "sha256": digest(path)}
        for role, path in artifacts.items()
    ]
    manifest = {
        "schemaVersion": 1,
        "packId": pack_id,
        "packVersion": args.pack_version,
        "model": {"family": "gemma-4", "tier": args.tier, "multimodal": True, "maxContextTokens": args.context_tokens},
        "source": {"revision": args.source_revision, "license": args.source_license},
        "runtime": {"name": "LiteRT-LM", "version": "0.14.0"},
        "device": {"minimumRamBytes": minimum_ram},
        "signing": {"algorithm": algorithm, "keySha256": key_fingerprint},
        "files": files,
    }
    manifest_bytes = (json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="agentic-gallery-gemma-") as temp_name:
        temp = Path(temp_name)
        manifest_path, signature_path = temp / "manifest.json", temp / "manifest.sig"
        manifest_path.write_bytes(manifest_bytes)
        subprocess.run(
            ["java", str(ROOT / "PackManifestSigner.java"), "sign", str(args.keystore), args.keystore_type, args.alias, str(manifest_path), str(signature_path)],
            check=True,
            env=os.environ,
        )
        with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
            archive.write(manifest_path, "manifest.json")
            archive.write(signature_path, "manifest.sig")
            for path in artifacts.values():
                archive.write(path, path.name)
    print(json.dumps({"pack": str(args.output), "sha256": digest(args.output), "manifest": manifest}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
