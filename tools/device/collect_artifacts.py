from __future__ import annotations

import argparse
from pathlib import Path

from common import adb, resolve_serial


def package_logcat_args(pid: str) -> tuple[str, ...]:
    if not pid.isdigit():
        raise RuntimeError("Target package did not resolve to one numeric PID")
    return ("logcat", "-d", "-v", "threadtime", "--pid", pid, "-t", "2000")


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
    }
    for name, command_args in commands.items():
        result = adb(serial, *command_args, check=False)
        (args.output / name).write_bytes(result.stdout)
    pid_result = adb(serial, "shell", "pidof", args.package, check=False)
    pids = pid_result.stdout.decode(errors="replace").strip().split()
    if len(pids) == 1 and pids[0].isdigit():
        logcat = adb(serial, *package_logcat_args(pids[0]), check=False)
        (args.output / "logcat.txt").write_bytes(logcat.stdout)
    else:
        (args.output / "logcat.txt").write_text("Target process was not running; package-scoped logcat was not collected.\n", encoding="utf-8")
    print(f"Collected privacy-safe device diagnostics in {args.output}")


if __name__ == "__main__":
    main()
