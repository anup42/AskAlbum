from __future__ import annotations

import json
import os
import re
import subprocess
import time
from pathlib import Path
from typing import Callable, TypeVar


RUN_ID = re.compile(r"[A-Za-z0-9_-]{6,64}")
T = TypeVar("T")


def retry_transient(
    operation: Callable[[], T], *, attempts: int = 4, base_delay_seconds: float = 0.15,
    sleep: Callable[[float], None] = time.sleep,
) -> tuple[T, int]:
    if attempts < 1:
        raise ValueError("attempts must be positive")
    failures: list[str] = []
    for attempt in range(attempts):
        try:
            return operation(), attempt
        except RuntimeError as error:
            failures.append(str(error)[-500:])
            if attempt + 1 < attempts:
                sleep(base_delay_seconds * (2**attempt))
    raise RuntimeError(f"Operation failed after {attempts} attempts: {failures[-1]}")


def command(
    args: list[str], *, input_bytes: bytes | None = None, check: bool = True, timeout_seconds: float = 60,
) -> subprocess.CompletedProcess[bytes]:
    try:
        result = subprocess.run(
            args, input=input_bytes, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            check=False, timeout=timeout_seconds,
        )
    except subprocess.TimeoutExpired as error:
        raise RuntimeError(f"Command timed out after {timeout_seconds}s: {' '.join(args)}") from error
    if check and result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {' '.join(args)}\n{result.stderr.decode(errors='replace')[-2000:]}")
    return result


def authorized_devices() -> list[str]:
    output = command(["adb", "devices", "-l"]).stdout.decode()
    return [line.split()[0] for line in output.splitlines() if re.match(r"^\S+\s+device\b", line)]


def resolve_serial(requested: str | None) -> str:
    devices = authorized_devices()
    serial = requested or os.environ.get("ANDROID_SERIAL")
    if serial:
        if serial not in devices:
            raise RuntimeError(f"Requested device is not the sole authorized target: {serial}")
        return serial
    if len(devices) != 1:
        raise RuntimeError(f"Expected exactly one authorized device, found {len(devices)}; set ANDROID_SERIAL")
    return devices[0]


def adb(
    serial: str, *args: str, input_bytes: bytes | None = None, check: bool = True,
    timeout_seconds: float = 60,
) -> subprocess.CompletedProcess[bytes]:
    return command(
        ["adb", "-s", serial, *args], input_bytes=input_bytes,
        check=check, timeout_seconds=timeout_seconds,
    )


def require_run_id(run_id: str) -> str:
    if not RUN_ID.fullmatch(run_id):
        raise RuntimeError("Run ID must contain only 6-64 letters, digits, underscores, or hyphens")
    return run_id


def mask_serial(serial: str) -> str:
    return f"{serial[:3]}…{serial[-4:]}" if len(serial) > 7 else "masked"


def run_as_read(serial: str, package: str, relative_path: str) -> bytes | None:
    result = adb(serial, "shell", "run-as", package, "cat", relative_path, check=False)
    return result.stdout if result.returncode == 0 else None


def wait_for_json(serial: str, package: str, relative_path: str, timeout_seconds: float = 120) -> dict[str, object]:
    deadline = time.monotonic() + timeout_seconds
    last: dict[str, object] | None = None
    while time.monotonic() < deadline:
        payload = run_as_read(serial, package, relative_path)
        if payload:
            try:
                last = json.loads(payload)
                state = last.get("state")
                if state == "COMPLETE":
                    return last
                if state == "FAILED":
                    raise RuntimeError(f"Device operation failed: {last.get('error')}")
            except json.JSONDecodeError:
                pass
        time.sleep(0.25)
    raise RuntimeError(f"Timed out waiting for {relative_path}; last status={last}")
