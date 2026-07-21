from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

from common import adb, mask_serial, resolve_serial


MIN_MODEL_BYTES = 50 * 1024 * 1024


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description="Install a verified local model into a debuggable app without committing it")
    parser.add_argument("--serial")
    parser.add_argument("--package", default="com.askphotos.android")
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--sha256", required=True)
    args = parser.parse_args()
    serial = resolve_serial(args.serial)
    model = args.model.resolve()
    if not model.is_file() or model.stat().st_size < MIN_MODEL_BYTES:
        raise RuntimeError("Model pack is missing or too small to be a LiteRT-LM pack")
    expected = args.sha256.lower()
    actual = sha256(model)
    if actual != expected:
        raise RuntimeError(f"Host model SHA-256 mismatch: expected {expected}, got {actual}")
    temporary = f"/data/local/tmp/agentic-gallery-model-{actual[:16]}.litertlm"
    try:
        adb(serial, "push", str(model), temporary, timeout_seconds=1800)
        adb(serial, "shell", "chmod", "0644", temporary)
        adb(serial, "shell", "run-as", args.package, "mkdir", "-p", "files/models")
        adb(serial, "shell", "run-as", args.package, "cp", temporary, "files/models/gemma.importing", timeout_seconds=1800)
        device_hash = adb(
            serial, "shell", "run-as", args.package, "sha256sum", "files/models/gemma.importing",
            timeout_seconds=1800,
        ).stdout.decode().split()[0].lower()
        if device_hash != expected:
            adb(serial, "shell", "run-as", args.package, "rm", "-f", "files/models/gemma.importing", check=False)
            raise RuntimeError(f"Device model SHA-256 mismatch: expected {expected}, got {device_hash}")
        adb(serial, "shell", "run-as", args.package, "mv", "files/models/gemma.importing", "files/models/gemma.litertlm")
        print(f"Installed verified model pack ({model.stat().st_size} bytes, {actual}) on {mask_serial(serial)}")
    finally:
        adb(serial, "shell", "rm", "-f", temporary, check=False)


if __name__ == "__main__":
    main()
