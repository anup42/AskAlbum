from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageEnhance

from fixture_metadata import save_raster_with_metadata


def generate(core: Path, output: Path, count: int) -> None:
    if count <= 0:
        raise ValueError("count must be positive")
    manifest = json.loads((core / "gallery-manifest.json").read_text(encoding="utf-8"))
    sources = [entry for entry in manifest["items"] if entry["filename"].lower().endswith((".jpg", ".jpeg", ".png"))]
    if not sources:
        raise SystemExit("Core profile contains no raster images")
    media = output / "media"
    if media.exists() and any(media.iterdir()):
        raise SystemExit(f"Refusing to mix a stress profile with existing media: {media}")
    media.mkdir(parents=True, exist_ok=True)
    mapping: list[dict[str, object]] = []
    stress_items: list[dict[str, object]] = []
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
        item_id = f"stress_{index:05d}"
        source_id = source.get("source_id", source["id"])
        stress_items.append({
            "id": item_id,
            "filename": filename,
            "kind": "IMAGE",
            "captured_at": source.get("captured_at"),
            "gps": source.get("gps"),
            "album": source.get("album"),
            "labels": source.get("labels", []),
            "source_id": source_id,
            "derivative_of": source["id"],
            "license": source.get("license"),
            "synthetic": bool(source.get("synthetic", False)),
        })
        mapping.append({
            "id": item_id,
            "filename": filename,
            "source_id": source_id,
            "core_item_id": source["id"],
            "event": source.get("album"),
            "captured_at": source.get("captured_at"),
            "gps": source.get("gps"),
        })
    profile = {5_000: "stress-5k", 20_000: "stress-20k"}.get(count, f"stress-{count}")
    (output / "gallery-manifest.json").write_text(json.dumps({
        "profile": profile,
        "generated_at": "deterministic",
        "source_profile": manifest.get("profile", "core"),
        "items": stress_items,
    }, indent=2, ensure_ascii=False), encoding="utf-8")
    (output / "stress-mapping.json").write_text(json.dumps(mapping, indent=2), encoding="utf-8")
    update_checksums(output)
    digest = hashlib.sha256((output / "stress-mapping.json").read_bytes()).hexdigest()
    print(f"Generated {count} deterministic stress items; mapping SHA-256 {digest}")


def update_checksums(output: Path) -> None:
    root = output.parent
    checksum_file = root / "CHECKSUMS.sha256"
    prefix = f"{output.relative_to(root).as_posix()}/media/"
    retained = []
    if checksum_file.is_file():
        retained = [line for line in checksum_file.read_text(encoding="utf-8").splitlines() if f"  {prefix}" not in line]
    generated = []
    for path in sorted((output / "media").iterdir()):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        generated.append(f"{digest}  {path.relative_to(root).as_posix()}")
    checksum_file.write_text("\n".join(retained + generated) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--core", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--count", type=int, choices=(5000, 20000), required=True)
    args = parser.parse_args()
    generate(args.core, args.output, args.count)


if __name__ == "__main__":
    main()
