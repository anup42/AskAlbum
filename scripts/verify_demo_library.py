from __future__ import annotations

import hashlib
import json
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
DEMO_ROOT = ROOT / "demo-assets"
MANIFEST = DEMO_ROOT / "manifest.json"
EXPECTED_LICENSE = "https://creativecommons.org/publicdomain/zero/1.0/"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    entries = json.loads(MANIFEST.read_text(encoding="utf-8"))
    errors: list[str] = []
    ids: set[str] = set()
    filenames: set[str] = set()

    for entry in entries:
        photo_id = entry.get("id")
        filename = entry.get("filename")
        if not photo_id or photo_id in ids:
            errors.append(f"duplicate or missing id: {photo_id!r}")
        if not filename or filename in filenames:
            errors.append(f"duplicate or missing filename: {filename!r}")
        ids.add(photo_id)
        filenames.add(filename)

        if entry.get("license") != "CC0 1.0" or entry.get("license_url") != EXPECTED_LICENSE:
            errors.append(f"{filename}: demo media must be CC0 1.0")
        if not str(entry.get("source_url", "")).startswith("https://"):
            errors.append(f"{filename}: missing HTTPS source page")
        if not str(entry.get("download_url", "")).startswith("https://"):
            errors.append(f"{filename}: missing HTTPS download URL")

        path = DEMO_ROOT / "images" / str(filename)
        if not path.is_file():
            errors.append(f"{filename}: file is missing")
            continue
        if sha256_file(path) != entry.get("sha256"):
            errors.append(f"{filename}: SHA-256 mismatch")
        with Image.open(path) as image:
            if [image.width, image.height] != [entry.get("width"), entry.get("height")]:
                errors.append(f"{filename}: image dimensions do not match manifest")

    undeclared = {path.name for path in (DEMO_ROOT / "images").iterdir() if path.is_file()} - filenames
    if undeclared:
        errors.append(f"undeclared files: {', '.join(sorted(undeclared))}")
    if errors:
        raise SystemExit("Demo library verification failed:\n- " + "\n- ".join(errors))
    print(f"Verified {len(entries)} CC0 demo photos, checksums and dimensions.")


if __name__ == "__main__":
    main()

