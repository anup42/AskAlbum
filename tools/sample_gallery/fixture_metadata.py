from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

from PIL import Image
from PIL.TiffImagePlugin import IFDRational


EXIF_DATE_TIME = 306
EXIF_DATE_TIME_ORIGINAL = 36867
EXIF_DATE_TIME_DIGITIZED = 36868
EXIF_OFFSET_TIME = 36880
EXIF_OFFSET_TIME_ORIGINAL = 36881
EXIF_OFFSET_TIME_DIGITIZED = 36882
EXIF_GPS_INFO = 34853


def parse_captured_at(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError("Fixture capture time must include an explicit UTC offset")
    return parsed


def build_fixture_exif(captured_at: str, gps: Sequence[float] | None) -> Image.Exif:
    captured = parse_captured_at(captured_at)
    timestamp = captured.strftime("%Y:%m:%d %H:%M:%S")
    offset = format_offset(captured)
    exif = Image.Exif()
    for tag in (EXIF_DATE_TIME, EXIF_DATE_TIME_ORIGINAL, EXIF_DATE_TIME_DIGITIZED):
        exif[tag] = timestamp
    for tag in (EXIF_OFFSET_TIME, EXIF_OFFSET_TIME_ORIGINAL, EXIF_OFFSET_TIME_DIGITIZED):
        exif[tag] = offset
    if gps is not None:
        if len(gps) != 2:
            raise ValueError("GPS fixture must contain latitude and longitude")
        latitude, longitude = float(gps[0]), float(gps[1])
        if not -90 <= latitude <= 90 or not -180 <= longitude <= 180:
            raise ValueError("GPS fixture is outside valid bounds")
        utc = captured.astimezone(timezone.utc)
        exif[EXIF_GPS_INFO] = {
            0: b"\x02\x03\x00\x00",
            1: "N" if latitude >= 0 else "S",
            2: decimal_degrees(abs(latitude)),
            3: "E" if longitude >= 0 else "W",
            4: decimal_degrees(abs(longitude)),
            7: tuple(IFDRational(value, 1) for value in (utc.hour, utc.minute, utc.second)),
            29: utc.strftime("%Y:%m:%d"),
        }
    return exif


def save_raster_with_metadata(
    image: Image.Image,
    destination: Path,
    format_name: str,
    captured_at: str,
    gps: Sequence[float] | None,
    **save_options: object,
) -> None:
    image.save(destination, format_name, exif=build_fixture_exif(captured_at, gps), **save_options)


def format_offset(captured: datetime) -> str:
    offset = captured.utcoffset()
    if offset is None:
        raise ValueError("Fixture capture time has no UTC offset")
    total_minutes = int(offset.total_seconds() // 60)
    sign = "+" if total_minutes >= 0 else "-"
    hours, minutes = divmod(abs(total_minutes), 60)
    return f"{sign}{hours:02d}:{minutes:02d}"


def decimal_degrees(value: float) -> tuple[IFDRational, IFDRational, IFDRational]:
    degrees = int(value)
    minute_value = (value - degrees) * 60
    minutes = int(minute_value)
    seconds = (minute_value - minutes) * 60
    return IFDRational(degrees, 1), IFDRational(minutes, 1), IFDRational(round(seconds * 1_000_000), 1_000_000)


def gps_decimal(gps_ifd: dict[int, object], value_tag: int, reference_tag: int) -> float:
    values = gps_ifd[value_tag]
    if not isinstance(values, tuple) or len(values) != 3:
        raise ValueError("Invalid GPS DMS tuple")
    decimal = float(values[0]) + float(values[1]) / 60 + float(values[2]) / 3600
    return -decimal if str(gps_ifd[reference_tag]).upper() in {"S", "W"} else decimal
