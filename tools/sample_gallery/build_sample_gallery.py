from __future__ import annotations

import argparse
import hashlib
import html
import json
import shutil
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

from PIL import Image, ImageEnhance, ImageOps

from generate_synthetic_documents import generate as generate_synthetic
from generate_stress_gallery import generate as generate_stress
from fixture_metadata import save_raster_with_metadata


ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = Path(__file__).with_name("manifest.yaml")
USER_AGENT = "AgenticGalleryFixtureBuilder/0.1 (local reproducible test corpus)"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_manifest() -> dict[str, object]:
    # The checked-in YAML is deliberately JSON-compatible, avoiding a runtime PyYAML dependency.
    return json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))


def api_metadata(title: str) -> dict[str, object]:
    query = urllib.parse.urlencode({
        "action": "query", "format": "json", "prop": "imageinfo",
        "iiprop": "url|size|mime|extmetadata", "iiurlwidth": "1600", "titles": title,
    })
    request = urllib.request.Request(f"https://commons.wikimedia.org/w/api.php?{query}", headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.load(response)
    page = next(iter(payload["query"]["pages"].values()))
    return page["imageinfo"][0]


def download_verified_source(entry: dict[str, object], cache: Path, whitelist: set[str]) -> Path:
    cache.mkdir(parents=True, exist_ok=True)
    destination = cache / str(entry["local_cache_name"])
    if not destination.is_file() or sha256_file(destination) != entry["sha256"]:
        metadata = api_metadata(str(entry["file_title"]))
        remote_license = metadata.get("extmetadata", {}).get("LicenseShortName", {}).get("value")
        if remote_license not in whitelist:
            raise RuntimeError(f"{entry['id']}: Commons API returned disallowed license {remote_license!r}")
        request = urllib.request.Request(str(entry["download_url"]), headers={"User-Agent": USER_AGENT})
        temporary = destination.with_suffix(destination.suffix + ".tmp")
        with urllib.request.urlopen(request, timeout=60) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
        temporary.replace(destination)
    actual = sha256_file(destination)
    if actual != entry["sha256"]:
        raise RuntimeError(f"{entry['id']}: SHA-256 mismatch: expected {entry['sha256']}, got {actual}")
    with Image.open(destination) as image:
        if [image.width, image.height] != [entry["width"], entry["height"]]:
            raise RuntimeError(f"{entry['id']}: pinned dimensions do not match")
    return destination


def transformed_copy(
    source: Path,
    destination: Path,
    variant: int,
    captured_at: str,
    gps: list[float] | None,
) -> None:
    with Image.open(source) as opened:
        image = ImageOps.exif_transpose(opened).convert("RGB")
        if variant:
            width, height = image.size
            inset = max(1, min(width, height) * variant // 100)
            image = image.crop((inset, inset, width - inset, height - inset))
            image = ImageEnhance.Brightness(image).enhance(0.94 + variant * 0.025)
        image.thumbnail((1280, 1280))
        save_raster_with_metadata(
            image,
            destination,
            "JPEG",
            captured_at,
            gps,
            quality=88 - variant,
            optimize=True,
        )


def build_core(output: Path, config: dict[str, object]) -> None:
    if output.exists():
        shutil.rmtree(output)
    media = output / "media"
    media.mkdir(parents=True)
    whitelist = set(config["license_whitelist"])
    source_cache = ROOT / "build" / "sample-gallery" / "source-downloads"
    legacy_manifest = json.loads((ROOT / str(config["legacy_manifest"])).read_text(encoding="utf-8"))
    source_records: list[dict[str, object]] = []
    for entry in config["sources"]:
        if entry["license"] not in whitelist:
            raise RuntimeError(f"Disallowed checked-in license for {entry['id']}")
        path = download_verified_source(entry, source_cache, whitelist)
        source_records.append({"entry": entry, "path": path})
    for legacy in legacy_manifest:
        if legacy["license"] not in whitelist:
            raise RuntimeError(f"Disallowed legacy license for {legacy['id']}")
        path = ROOT / "demo-assets" / "images" / legacy["filename"]
        if sha256_file(path) != legacy["sha256"]:
            raise RuntimeError(f"Legacy source checksum mismatch: {legacy['filename']}")
        source_records.append({"entry": {
            "id": f"legacy_{legacy['id']}", "license": legacy["license"], "license_url": legacy["license_url"],
            "author": legacy.get("creator"), "source_page": legacy["source_url"], "sha256": legacy["sha256"],
            "fixture": {"captured_at": "2024-06-15T12:00:00+00:00", "gps": [legacy.get("latitude"), legacy.get("longitude")],
                        "album": legacy.get("location_name") or "Open Gallery", "labels": legacy.get("tags", [])},
        }, "path": path})

    items: list[dict[str, object]] = []
    for source_index, source in enumerate(source_records):
        entry = source["entry"]
        fixture = entry["fixture"]
        base_time = datetime.fromisoformat(fixture["captured_at"])
        for variant in range(4):
            item_id = f"{entry['id']}_v{variant}"
            filename = f"{item_id}.jpg"
            captured_at = (base_time + timedelta(minutes=variant * 7)).isoformat()
            gps = fixture.get("gps")
            safe_gps = gps if gps and all(value is not None for value in gps) else None
            transformed_copy(source["path"], media / filename, variant, captured_at, safe_gps)
            items.append({
                "id": item_id, "filename": filename, "kind": "IMAGE",
                "captured_at": captured_at,
                "gps": safe_gps,
                "album": fixture["album"], "labels": fixture["labels"],
                "source_id": entry["id"], "license": entry["license"], "synthetic": False,
            })

    synthetic_dir = output / "synthetic-source"
    synthetic_records = generate_synthetic(synthetic_dir)
    for index, record in enumerate(synthetic_records):
        source = synthetic_dir / record["filename"]
        destination = media / record["filename"]
        captured = datetime(2026, 7, 18, 10, 0, tzinfo=timezone.utc) + timedelta(minutes=index)
        if record["id"] in {"synthetic_boarding_pass", "synthetic_hotel_confirmation", "synthetic_calendar"}:
            captured = datetime(2024, 3, 12, 8, 0, tzinfo=timezone.utc) + timedelta(minutes=index)
        if record["kind"] == "IMAGE":
            with Image.open(source) as opened:
                save_raster_with_metadata(opened.copy(), destination, require_not_none(opened.format), captured.isoformat(), None)
        else:
            shutil.copy2(source, destination)
        items.append({
            **record, "captured_at": captured.isoformat(), "gps": None,
            "album": "Synthetic Documents" if record["id"] != "synthetic_people_relation" else "Synthetic People",
            "license": config["synthetic_license"], "synthetic": True,
        })
    shutil.rmtree(synthetic_dir)

    if not 60 <= len(items) <= 100:
        raise RuntimeError(f"Core profile must contain 60-100 items, generated {len(items)}")
    gallery_manifest = {"profile": "core", "generated_at": "deterministic", "items": items}
    (output / "gallery-manifest.json").write_text(json.dumps(gallery_manifest, indent=2, ensure_ascii=False), encoding="utf-8")

    licenses = []
    attribution_lines = ["# Agentic Gallery sample attribution", "", "Synthetic fixtures are marked CC0 and contain fictitious data.", ""]
    for source in source_records:
        entry = source["entry"]
        licenses.append({key: entry.get(key) for key in ("id", "source_page", "license", "license_url", "author", "sha256")})
        attribution_lines.append(f"- **{entry['id']}** — {entry.get('author') or 'author not listed'}; {entry['license']}; {entry['source_page']}")
    licenses.append({"id": "synthetic_fixtures", "license": config["synthetic_license"], "author": "Agentic Gallery fixture generator"})
    (output.parent / "LICENSES.json").write_text(json.dumps(licenses, indent=2, ensure_ascii=False), encoding="utf-8")
    (output.parent / "ATTRIBUTION.md").write_text("\n".join(attribution_lines) + "\n", encoding="utf-8")
    checksum_lines = [f"{sha256_file(path)}  {path.relative_to(output.parent).as_posix()}" for path in sorted(media.iterdir())]
    (output.parent / "CHECKSUMS.sha256").write_text("\n".join(checksum_lines) + "\n", encoding="utf-8")
    count_2024 = sum(1 for item in items if str(item["captured_at"]).startswith("2024") and item["kind"] == "IMAGE")
    (output / "ground-truth-summary.json").write_text(json.dumps({"item_count": len(items), "photo_count_2024": count_2024}, indent=2), encoding="utf-8")
    print(f"Built core gallery with {len(items)} items ({count_2024} images dated 2024) at {output}")


def require_not_none(value: str | None) -> str:
    if value is None:
        raise RuntimeError("Synthetic raster has no image format")
    return value


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", choices=("core", "stress-5k", "stress-20k"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    config = read_manifest()
    if args.profile == "core":
        build_core(args.output, config)
    else:
        core = ROOT / "build" / "sample-gallery" / "core"
        if not (core / "gallery-manifest.json").is_file():
            build_core(core, config)
        generate_stress(core, args.output, 5000 if args.profile == "stress-5k" else 20000)


if __name__ == "__main__":
    main()
