from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from PIL import Image

from fixture_metadata import EXIF_DATE_TIME_ORIGINAL, EXIF_GPS_INFO, EXIF_OFFSET_TIME_ORIGINAL, gps_decimal, save_raster_with_metadata


class FixtureMetadataTest(unittest.TestCase):
    def test_jpeg_round_trip_preserves_capture_offset_and_gps(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.jpg"
            save_raster_with_metadata(
                Image.new("RGB", (16, 16), "blue"),
                path,
                "JPEG",
                "2024-03-13T10:42:00+08:00",
                [1.2834, 103.8607],
                quality=90,
            )

            with Image.open(path) as image:
                exif = image.getexif()
                self.assertEqual("2024:03:13 10:42:00", exif[EXIF_DATE_TIME_ORIGINAL])
                self.assertEqual("+08:00", exif[EXIF_OFFSET_TIME_ORIGINAL])
                gps = dict(exif.get_ifd(EXIF_GPS_INFO))
                self.assertAlmostEqual(1.2834, gps_decimal(gps, 2, 1), places=5)
                self.assertAlmostEqual(103.8607, gps_decimal(gps, 4, 3), places=5)

    def test_png_round_trip_preserves_capture_time(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.png"
            save_raster_with_metadata(Image.new("RGB", (16, 16), "white"), path, "PNG", "2026-07-18T10:00:00+00:00", None)

            with Image.open(path) as image:
                exif = image.getexif()
                self.assertEqual("2026:07:18 10:00:00", exif[EXIF_DATE_TIME_ORIGINAL])
                self.assertEqual("+00:00", exif[EXIF_OFFSET_TIME_ORIGINAL])


if __name__ == "__main__":
    unittest.main()
