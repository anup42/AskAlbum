from __future__ import annotations

import argparse
import json
import time
import uuid
from pathlib import Path

from common import adb, mask_serial, require_run_id, resolve_serial, run_as_read


def parse_cleanup_status(payload: bytes | None, run_id: str, operation_id: str) -> dict[str, object] | None:
    if not payload:
        return None
    try:
        result = json.loads(payload)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return None
    if result.get("runId") != run_id or result.get("operationId") != operation_id:
        return None
    if result.get("state") == "FAILED":
        raise RuntimeError(f"Device cleanup failed: {result.get('error')}")
    return result if result.get("state") == "COMPLETE" else None


def wait_for_cleanup(serial: str, package: str, run_id: str, operation_id: str, timeout_seconds: float) -> dict[str, object]:
    relative_path = f"files/test-seed/{run_id}/cleanup-status.json"
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        result = parse_cleanup_status(run_as_read(serial, package, relative_path), run_id, operation_id)
        if result is not None:
            return result
        time.sleep(0.25)
    raise RuntimeError("Timed out waiting for the matching run-scoped cleanup operation")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial")
    parser.add_argument("--package", default="com.askphotos.android")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--artifacts", type=Path, default=Path("artifacts/device-runs"))
    args = parser.parse_args()
    serial = resolve_serial(args.serial)
    run_id = require_run_id(args.run_id)
    operation_id = uuid.uuid4().hex
    adb(
        serial, "shell", "am", "start-foreground-service", "-n", f"{args.package}/.TestGallerySeederService",
        "-a", "com.askphotos.android.test.CLEANUP_GALLERY_FOREGROUND", "--es", "run_id", run_id,
        "--es", "operation_id", operation_id,
    )
    result = wait_for_cleanup(serial, args.package, run_id, operation_id, timeout_seconds=900)
    if result.get("remainingCount") != 0:
        raise RuntimeError(f"Cleanup left {result.get('remainingCount')} items in the run-specific album")
    host = args.artifacts / run_id
    host.mkdir(parents=True, exist_ok=True)
    safe_result = {**result, "serial": mask_serial(serial), "package": args.package}
    (host / "cleanup-result.json").write_text(json.dumps(safe_result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(safe_result, indent=2))


if __name__ == "__main__":
    main()
