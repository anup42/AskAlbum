from __future__ import annotations

import argparse
import json
import re
import subprocess
import time
from pathlib import Path

from common import adb, mask_serial, resolve_serial


TOTAL_PSS = re.compile(r"TOTAL PSS:\s*([\d,]+)")
TOTAL_RSS = re.compile(r"TOTAL RSS:\s*([\d,]+)")
LEGACY_TOTAL = re.compile(r"^\s*TOTAL\s+([\d,]+)(?:\s+[\d,]+){6}\s+([\d,]+)\s*$", re.MULTILINE)
THERMAL_STATUS = re.compile(r"Thermal Status:\s*(\d+)")


def parse_meminfo(text: str) -> tuple[int, int] | None:
    pss = TOTAL_PSS.search(text)
    rss = TOTAL_RSS.search(text)
    if pss and rss:
        return _number(pss.group(1)), _number(rss.group(1))
    legacy = LEGACY_TOTAL.search(text)
    if legacy:
        return _number(legacy.group(1)), _number(legacy.group(2))
    return None


def parse_thermal_status(text: str) -> int | None:
    match = THERMAL_STATUS.search(text)
    return int(match.group(1)) if match else None


def _number(value: str) -> int:
    return int(value.replace(",", ""))


def main() -> None:
    parser = argparse.ArgumentParser(description="Profile one installed Android instrumentation class")
    parser.add_argument("--serial")
    parser.add_argument("--package", default="com.askphotos.android")
    parser.add_argument("--test-class", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--interval-ms", type=int, default=500)
    parser.add_argument("--timeout-seconds", type=int, default=600)
    args = parser.parse_args()
    if args.interval_ms not in range(100, 5001):
        raise RuntimeError("interval-ms must be between 100 and 5000")
    if args.timeout_seconds not in range(1, 3601):
        raise RuntimeError("timeout-seconds must be between 1 and 3600")

    serial = resolve_serial(args.serial)
    args.output.mkdir(parents=True, exist_ok=True)
    before_thermal = adb(serial, "shell", "dumpsys", "thermalservice", check=False).stdout.decode(errors="replace")
    command = [
        "adb", "-s", serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", args.test_class,
        f"{args.package}.test/androidx.test.runner.AndroidJUnitRunner",
    ]
    started = time.monotonic()
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    samples: list[dict[str, int]] = []
    try:
        while process.poll() is None:
            if time.monotonic() - started > args.timeout_seconds:
                process.terminate()
                adb(serial, "shell", "am", "force-stop", args.package, check=False)
                raise RuntimeError(f"Instrumentation exceeded {args.timeout_seconds} seconds")
            result = adb(serial, "shell", "dumpsys", "meminfo", args.package, check=False, timeout_seconds=15)
            parsed = parse_meminfo(result.stdout.decode(errors="replace"))
            if parsed:
                pss_kb, rss_kb = parsed
                samples.append(
                    {
                        "elapsedMs": round((time.monotonic() - started) * 1000),
                        "pssKb": pss_kb,
                        "rssKb": rss_kb,
                    },
                )
            time.sleep(args.interval_ms / 1000)
        output = process.communicate(timeout=10)[0].decode(errors="replace") if process.stdout else ""
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)

    post_run_meminfo = adb(
        serial, "shell", "dumpsys", "meminfo", args.package, check=False, timeout_seconds=15,
    ).stdout.decode(errors="replace")
    post_run_memory = parse_meminfo(post_run_meminfo)
    after_thermal = adb(serial, "shell", "dumpsys", "thermalservice", check=False).stdout.decode(errors="replace")
    (args.output / "instrumentation.txt").write_text(output, encoding="utf-8")
    (args.output / "thermal-before.txt").write_text(before_thermal, encoding="utf-8")
    (args.output / "thermal-after.txt").write_text(after_thermal, encoding="utf-8")
    (args.output / "memory-samples.json").write_text(json.dumps(samples, indent=2) + "\n", encoding="utf-8")
    summary = {
        "serial": mask_serial(serial),
        "package": args.package,
        "testClass": args.test_class,
        "exitCode": process.returncode,
        "passed": process.returncode == 0 and "OK (1 test)" in output,
        "durationMs": round((time.monotonic() - started) * 1000),
        "sampleCount": len(samples),
        "firstPssKb": samples[0]["pssKb"] if samples else None,
        "lastActivePssKb": samples[-1]["pssKb"] if samples else None,
        "postRunPssKb": post_run_memory[0] if post_run_memory else None,
        "postRunRssKb": post_run_memory[1] if post_run_memory else None,
        "peakPssKb": max((sample["pssKb"] for sample in samples), default=None),
        "peakRssKb": max((sample["rssKb"] for sample in samples), default=None),
        "thermalStatusBefore": parse_thermal_status(before_thermal),
        "thermalStatusAfter": parse_thermal_status(after_thermal),
    }
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))
    if not summary["passed"]:
        raise RuntimeError(f"Instrumentation failed; see {args.output / 'instrumentation.txt'}")


if __name__ == "__main__":
    main()
