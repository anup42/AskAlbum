from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gallery", type=Path, required=True)
    args = parser.parse_args()
    root = args.gallery.parent
    manifest = json.loads((args.gallery / "gallery-manifest.json").read_text(encoding="utf-8"))
    licenses = json.loads((root / "LICENSES.json").read_text(encoding="utf-8"))
    allowed = {"Public domain", "CC0", "CC0 1.0", "CC BY 4.0", "CC BY-SA 4.0"}
    errors: list[str] = []
    if not licenses or any(record.get("license") not in allowed for record in licenses):
        errors.append("license report contains a missing or disallowed license")
    item_names = {entry["filename"] for entry in manifest["items"]}
    actual_names = {path.name for path in (args.gallery / "media").iterdir() if path.is_file()}
    if item_names != actual_names:
        errors.append("gallery manifest and media directory differ")
    declared_checksums = {}
    for line in (root / "CHECKSUMS.sha256").read_text(encoding="utf-8").splitlines():
        digest, relative = line.split("  ", 1)
        declared_checksums[relative] = digest
    for path in (args.gallery / "media").iterdir():
        relative = path.relative_to(root).as_posix()
        if declared_checksums.get(relative) != sha256(path):
            errors.append(f"checksum mismatch: {relative}")
    if errors:
        raise SystemExit("License/corpus verification failed:\n- " + "\n- ".join(errors))
    print(f"Verified {len(manifest['items'])} gallery items, {len(licenses)} license records, and all generated checksums.")


if __name__ == "__main__":
    main()
