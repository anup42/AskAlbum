from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageEnhance

from fixture_metadata import save_raster_with_metadata


def generate(core: Path, output: Path, count: int) -> None:
    manifest = json.loads((core / "gallery-manifest.json").read_text(encoding="utf-8"))
    sources = [entry for entry in manifest["items"] if entry["filename"].lower().endswith((".jpg", ".jpeg", ".png"))]
    if not sources:
        raise SystemExit("Core profile contains no raster images")
    media = output / "media"
    media.mkdir(parents=True, exist_ok=True)
    mapping: list[dict[str, object]] = []
    for index in range(count):
        source = sources[index % len(sources)]
        source_path = core / "media" / source["filename"]
        with Image.open(source_path) as opened:
            image = opened.convert("RGB")
            image.thumbnail((384, 384))
            factor = 0.88 + ((index * 17) % 25) / 100
            image = ImageEnhance.Brightness(image).enhance(factor)
            filename = f"stress_{index:05d}.jpg"
            save_raster_with_metadata(
                image,
                media / filename,
                "JPEG",
                source["captured_at"],
                source.get("gps"),
                quality=70 + index % 16,
                optimize=True,
            )
        mapping.append({
            "id": f"stress_{index:05d}", "filename": filename, "source_id": source["id"],
            "event": source.get("album"), "captured_at": source.get("captured_at"),
            "gps": source.get("gps"),
        })
    (output / "stress-mapping.json").write_text(json.dumps(mapping, indent=2), encoding="utf-8")
    digest = hashlib.sha256((output / "stress-mapping.json").read_bytes()).hexdigest()
    print(f"Generated {count} deterministic stress items; mapping SHA-256 {digest}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--core", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--count", type=int, choices=(5000, 20000), required=True)
    args = parser.parse_args()
    generate(args.core, args.output, args.count)


if __name__ == "__main__":
    main()
