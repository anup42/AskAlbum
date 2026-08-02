from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image

from fixture_metadata import EXIF_DATE_TIME_ORIGINAL, EXIF_GPS_INFO, EXIF_OFFSET_TIME_ORIGINAL, format_offset, gps_decimal, parse_captured_at


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
    for item in manifest["items"]:
        if item.get("kind") != "IMAGE":
            continue
        path = args.gallery / "media" / item["filename"]
        with Image.open(path) as image:
            exif = image.getexif()
            expected = parse_captured_at(item["captured_at"])
            if exif.get(EXIF_DATE_TIME_ORIGINAL) != expected.strftime("%Y:%m:%d %H:%M:%S"):
                errors.append(f"EXIF capture mismatch: {item['filename']}")
            if exif.get(EXIF_OFFSET_TIME_ORIGINAL) != format_offset(expected):
                errors.append(f"EXIF offset mismatch: {item['filename']}")
            expected_gps = item.get("gps")
            gps_ifd = dict(exif.get_ifd(EXIF_GPS_INFO))
            if expected_gps is not None:
                try:
                    actual_latitude = gps_decimal(gps_ifd, 2, 1)
                    actual_longitude = gps_decimal(gps_ifd, 4, 3)
                    if abs(actual_latitude - float(expected_gps[0])) > 1e-5 or abs(actual_longitude - float(expected_gps[1])) > 1e-5:
                        errors.append(f"EXIF GPS mismatch: {item['filename']}")
                except (KeyError, TypeError, ValueError):
                    errors.append(f"EXIF GPS missing: {item['filename']}")
    if errors:
        raise SystemExit("License/corpus verification failed:\n- " + "\n- ".join(errors))
    print(f"Verified {len(manifest['items'])} gallery items, {len(licenses)} license records, and all generated checksums.")


if __name__ == "__main__":
    main()
