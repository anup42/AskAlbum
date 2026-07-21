from __future__ import annotations

import argparse
import json
from pathlib import Path

from common import adb, mask_serial, resolve_serial


PROPERTIES = {
    "manufacturer": "ro.product.manufacturer", "model": "ro.product.model",
    "android_release": "ro.build.version.release", "api": "ro.build.version.sdk",
    "abi": "ro.product.cpu.abi", "soc": "ro.soc.model",
}


def collect(serial: str) -> dict[str, object]:
    report: dict[str, object] = {"serial": mask_serial(serial)}
    for name, prop in PROPERTIES.items():
        report[name] = adb(serial, "shell", "getprop", prop).stdout.decode().strip()
    meminfo = adb(serial, "shell", "cat", "/proc/meminfo").stdout.decode().splitlines()
    report["memory"] = [line for line in meminfo if line.startswith(("MemTotal:", "MemAvailable:"))]
    report["data_filesystem"] = adb(serial, "shell", "df", "-h", "/data").stdout.decode().splitlines()[-1]
    return report


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    serial = resolve_serial(args.serial)
    report = collect(serial)
    rendered = json.dumps(report, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)


if __name__ == "__main__":
    main()
