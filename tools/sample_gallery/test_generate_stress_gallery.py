from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from PIL import Image

from fixture_metadata import save_raster_with_metadata
from generate_stress_gallery import generate


class StressGalleryGeneratorTest(unittest.TestCase):
    def test_profile_is_seedable_and_preserves_source_lineage(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            core = root / "core"
            media = core / "media"
            media.mkdir(parents=True)
            source = media / "source.jpg"
            save_raster_with_metadata(
                Image.new("RGB", (32, 24), "navy"),
                source,
                "JPEG",
                "2024-03-13T10:42:00+08:00",
                [1.2834, 103.8607],
            )
            (core / "gallery-manifest.json").write_text(json.dumps({
                "profile": "core",
                "items": [{
                    "id": "core_variant",
                    "filename": source.name,
                    "kind": "IMAGE",
                    "captured_at": "2024-03-13T10:42:00+08:00",
                    "gps": [1.2834, 103.8607],
                    "album": "Singapore 2024",
                    "labels": ["singapore", "marina_bay"],
                    "source_id": "licensed_source",
                    "license": "CC0",
                    "synthetic": False,
                }],
            }), encoding="utf-8")

            output = root / "stress"
            generate(core, output, 3)

            manifest = json.loads((output / "gallery-manifest.json").read_text(encoding="utf-8"))
            mapping = json.loads((output / "stress-mapping.json").read_text(encoding="utf-8"))
            self.assertEqual("stress-3", manifest["profile"])
            self.assertEqual(3, len(manifest["items"]))
            self.assertEqual({"stress_00000.jpg", "stress_00001.jpg", "stress_00002.jpg"}, {
                item["filename"] for item in manifest["items"]
            })
            self.assertTrue(all(item["kind"] == "IMAGE" for item in manifest["items"]))
            self.assertTrue(all(item["source_id"] == "licensed_source" for item in manifest["items"]))
            self.assertTrue(all(item["derivative_of"] == "core_variant" for item in manifest["items"]))
            self.assertEqual("core_variant", mapping[0]["core_item_id"])
            checksum_lines = (root / "CHECKSUMS.sha256").read_text(encoding="utf-8").splitlines()
            self.assertEqual(3, len(checksum_lines))
            self.assertTrue(all("  stress/media/stress_" in line for line in checksum_lines))


if __name__ == "__main__":
    unittest.main()
