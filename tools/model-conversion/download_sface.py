#!/usr/bin/env python3
"""Download the exact Apache-2.0 SFace ONNX asset embedded by the Android build."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import urllib.request

REVISION = "c140188d35b7d0050f2dcfdfb8fe3e98d516744f"
FILE_NAME = "face_recognition_sface_2021dec.onnx"
URL = f"https://huggingface.co/opencv/face_recognition_sface/resolve/{REVISION}/{FILE_NAME}?download=true"
SIZE = 38_696_353
SHA256 = "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def main() -> None:
    repository = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=Path,
        default=repository / "build" / "models" / "face" / FILE_NAME,
    )
    args = parser.parse_args()
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    if output.is_file() and output.stat().st_size == SIZE and digest(output) == SHA256:
        print(f"Verified existing SFace model: {output}")
        return

    partial = output.with_suffix(output.suffix + ".part")
    request = urllib.request.Request(URL, headers={"User-Agent": "AgenticGallery-model-fetch/0.0.6"})
    try:
        with urllib.request.urlopen(request) as response, partial.open("wb") as target:
            while block := response.read(1024 * 1024):
                target.write(block)
    except BaseException:
        partial.unlink(missing_ok=True)
        raise

    actual_size = partial.stat().st_size
    actual_hash = digest(partial)
    if actual_size != SIZE or actual_hash != SHA256:
        partial.unlink(missing_ok=True)
        raise SystemExit(f"SFace verification failed: size={actual_size}, sha256={actual_hash}")
    os.replace(partial, output)
    print(f"Downloaded and verified SFace model: {output}")


if __name__ == "__main__":
    main()
