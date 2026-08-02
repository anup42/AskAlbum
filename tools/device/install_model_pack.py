from __future__ import annotations

import argparse
import json
import time
import uuid
from pathlib import Path

from common import adb, mask_serial, resolve_serial, run_as_read

TERMINAL_STATES = {"INSTALLED", "FAILED", "CANCELLED"}
VALID_TIERS = {"E2B", "E4B"}
VALID_STATES = {"IDLE", "QUEUED", "DOWNLOADING", "VERIFYING", *TERMINAL_STATES}
DEVICE_ACTIONS = {"download": "DOWNLOAD", "status": "REPORT", "cancel": "CANCEL"}


def parse_status(payload: bytes | None, tier: str, operation_id: str) -> dict[str, object] | None:
    if not payload:
        return None
    try:
        result = json.loads(payload)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return None
    if result.get("tier") != tier or result.get("operationId") != operation_id:
        return None
    if result.get("state") == "FAILED":
        raise RuntimeError(f"Gemma device harness failed: {result.get('error')}")
    if result.get("state") != "COMPLETE":
        return None
    validate_status(result, tier)
    return result


def validate_status(result: dict[str, object], tier: str) -> None:
    if tier not in VALID_TIERS or result.get("tier") != tier:
        raise RuntimeError("Gemma status has an invalid tier")
    state = result.get("downloadState")
    if state not in VALID_STATES:
        raise RuntimeError("Gemma status has an invalid download state")
    downloaded = result.get("bytesDownloaded")
    total = result.get("totalBytes")
    if not isinstance(downloaded, int) or not isinstance(total, int) or total <= 0 or not 0 <= downloaded <= total:
        raise RuntimeError("Gemma status has invalid byte counts")
    if not isinstance(result.get("sha256"), str) or len(result["sha256"]) != 64:
        raise RuntimeError("Gemma status has no pinned SHA-256")
    if state == "INSTALLED" and (result.get("installed") is not True or downloaded != total):
        raise RuntimeError("Gemma reports an inconsistent installation")


def request_status(serial: str, package: str, tier: str, action: str, timeout_seconds: float = 15) -> dict[str, object]:
    operation_id = uuid.uuid4().hex
    if action == "DOWNLOAD":
        adb(
            serial, "shell", "am", "start", "-W", "-n", f"{package}/.TestGemmaDownloadActivity",
            "--es", "tier", tier, "--es", "operation_id", operation_id,
        )
    else:
        adb(
            serial, "shell", "am", "broadcast", "-n", f"{package}/.TestGemmaModelReceiver",
            "-a", f"io.github.anup42.askalbum.test.{action}_GEMMA", "--es", "tier", tier,
            "--es", "operation_id", operation_id,
        )
    relative = f"files/test-models/gemma-{tier.lower()}-status.json"
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        result = parse_status(run_as_read(serial, package, relative), tier, operation_id)
        if result is not None:
            return result
        time.sleep(0.2)
    raise RuntimeError("Timed out waiting for correlated Gemma status")


def wait_for_terminal(serial: str, package: str, tier: str, timeout_seconds: float, poll_seconds: float) -> dict[str, object]:
    deadline = time.monotonic() + timeout_seconds
    last_bucket = -1
    while time.monotonic() < deadline:
        result = request_status(serial, package, tier, "REPORT")
        state = str(result["downloadState"])
        total = int(result["totalBytes"])
        downloaded = int(result["bytesDownloaded"])
        bucket = int((downloaded * 20) / total) if total else 0
        if bucket != last_bucket or state in TERMINAL_STATES:
            print(f"{tier} {state}: {downloaded}/{total} bytes", flush=True)
            last_bucket = bucket
        if state in TERMINAL_STATES:
            if state != "INSTALLED":
                raise RuntimeError(f"Gemma download ended as {state}: {result.get('error')}")
            return result
        time.sleep(poll_seconds)
    raise RuntimeError("Timed out waiting for Gemma installation; the resumable WorkManager download was left active")


def main() -> None:
    parser = argparse.ArgumentParser(description="Drive the app's pinned, checksum-verified Gemma model downloader")
    parser.add_argument("--serial")
    parser.add_argument("--package", default="io.github.anup42.askalbum")
    parser.add_argument("--tier", choices=sorted(VALID_TIERS), default="E2B")
    parser.add_argument("--action", choices=("download", "status", "cancel"), default="download")
    parser.add_argument("--timeout-seconds", type=int, default=7200)
    parser.add_argument("--poll-seconds", type=float, default=5)
    parser.add_argument("--artifacts", type=Path, default=Path("artifacts/device-runs"))
    args = parser.parse_args()
    if not 30 <= args.timeout_seconds <= 14_400:
        raise RuntimeError("--timeout-seconds must be between 30 and 14400")
    if not 1 <= args.poll_seconds <= 60:
        raise RuntimeError("--poll-seconds must be between 1 and 60")
    serial = resolve_serial(args.serial)
    tier = args.tier.upper()
    action = DEVICE_ACTIONS[args.action]
    result = request_status(serial, args.package, tier, action)
    if args.action == "download" and result["downloadState"] != "INSTALLED":
        result = wait_for_terminal(serial, args.package, tier, args.timeout_seconds, args.poll_seconds)
    safe = {**result, "serial": mask_serial(serial), "package": args.package}
    destination = args.artifacts / "model-packs"
    destination.mkdir(parents=True, exist_ok=True)
    (destination / f"gemma-{tier.lower()}-{args.action}.json").write_text(
        json.dumps(safe, indent=2) + "\n", encoding="utf-8",
    )
    print(json.dumps(safe, indent=2))


if __name__ == "__main__":
    main()
