from __future__ import annotations

import argparse
from pathlib import Path

from common import adb, resolve_serial


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial")
    parser.add_argument("--package", default="com.askphotos.android")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    serial = resolve_serial(args.serial)
    args.output.mkdir(parents=True, exist_ok=True)
    commands = {
        "meminfo.txt": ("shell", "dumpsys", "meminfo", args.package),
        "thermal.txt": ("shell", "dumpsys", "thermalservice"),
        "package.txt": ("shell", "dumpsys", "package", args.package),
        "logcat.txt": ("logcat", "-d", "-v", "threadtime", "-t", "2000"),
    }
    for name, command_args in commands.items():
        result = adb(serial, *command_args, check=False)
        (args.output / name).write_bytes(result.stdout)
    print(f"Collected privacy-safe device diagnostics in {args.output}")


if __name__ == "__main__":
    main()
