from __future__ import annotations

import argparse
import json
import time
import uuid
from pathlib import Path

from common import adb, mask_serial, require_run_id, resolve_serial, run_as_read

EXPECTED_INDEX_STAGES = {
    "DISCOVERY", "METADATA", "THUMBNAIL", "VIDEO_KEYFRAMES", "EMBEDDING",
    "OCR", "FACES", "EVENTS", "ENRICHMENT",
}


def parse_import_status(payload: bytes | None, run_id: str, operation_id: str) -> dict[str, object] | None:
    if not payload:
        return None
    try:
        result = json.loads(payload)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return None
    if result.get("runId") != run_id or result.get("operationId") != operation_id:
        return None
    if result.get("state") == "FAILED":
        raise RuntimeError(f"Device import failed: {result.get('error')}")
    if result.get("state") != "COMPLETE":
        return None
    if result.get("requestedCount") != result.get("importedCount"):
        raise RuntimeError("Completed import does not cover every requested URI")
    return result


def parse_operation_status(payload: bytes | None, run_id: str, operation_id: str) -> dict[str, object] | None:
    if not payload:
        return None
    try:
        result = json.loads(payload)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return None
    if result.get("runId") != run_id or result.get("operationId") != operation_id:
        return None
    if result.get("state") == "FAILED":
        raise RuntimeError(f"Device operation failed: {result.get('error')}")
    return result if result.get("state") == "COMPLETE" else None


def validate_coverage(result: dict[str, object]) -> None:
    if result.get("state") != "COMPLETE":
        raise RuntimeError("Index coverage report is incomplete")
    expected = result.get("expectedCount")
    media = result.get("mediaCount")
    unique = result.get("uniqueMediaIds")
    if not isinstance(expected, int) or expected < 1 or media != expected or media != unique or not isinstance(media, int):
        raise RuntimeError("Index coverage report contains inconsistent scoped media counts")
    stages = result.get("stages")
    if not isinstance(stages, dict):
        raise RuntimeError("Index coverage report has no stage matrix")
    if set(stages) != EXPECTED_INDEX_STAGES:
        raise RuntimeError("Index coverage report does not contain the exact stage set")
    for stage, counts in stages.items():
        if not isinstance(stage, str) or not isinstance(counts, dict) or sum(counts.values()) != media:
            raise RuntimeError(f"Stage coverage mismatch for {stage}")


def wait_for_import(serial: str, package: str, run_id: str, operation_id: str, timeout_seconds: float) -> dict[str, object]:
    relative_path = f"files/test-seed/{run_id}/import-status.json"
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        result = parse_import_status(run_as_read(serial, package, relative_path), run_id, operation_id)
        if result is not None:
            return result
        time.sleep(0.25)
    raise RuntimeError("Timed out waiting for matching run-scoped import")


def wait_for_operation(
    serial: str,
    package: str,
    run_id: str,
    operation_id: str,
    filename: str,
    timeout_seconds: float,
) -> dict[str, object]:
    relative_path = f"files/test-seed/{run_id}/{filename}"
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        result = parse_operation_status(run_as_read(serial, package, relative_path), run_id, operation_id)
        if result is not None:
            return result
        time.sleep(0.25)
    raise RuntimeError(f"Timed out waiting for matching run-scoped {filename}")


def read_complete_status(serial: str, package: str, run_id: str, filename: str) -> dict[str, object]:
    payload = run_as_read(serial, package, f"files/test-seed/{run_id}/{filename}")
    if not payload:
        raise RuntimeError(f"Device did not write {filename}")
    result = json.loads(payload)
    if result.get("state") != "COMPLETE" or result.get("runId") != run_id:
        raise RuntimeError(f"Device returned invalid {filename}")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Import, resume, or report one run-scoped seeded gallery index")
    parser.add_argument("--serial")
    parser.add_argument("--package", default="com.askphotos.android")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--action", choices=("import", "resume", "status"), required=True)
    parser.add_argument("--timeout-seconds", type=int, default=900)
    parser.add_argument("--artifacts", type=Path, default=Path("artifacts/device-runs"))
    args = parser.parse_args()
    if not 30 <= args.timeout_seconds <= 3600:
        raise RuntimeError("--timeout-seconds must be between 30 and 3600")
    serial = resolve_serial(args.serial)
    run_id = require_run_id(args.run_id)
    if args.action == "import":
        operation_id = uuid.uuid4().hex
        adb(
            serial, "shell", "am", "start-foreground-service", "-n", f"{args.package}/.TestGallerySeederService",
            "-a", "com.askphotos.android.test.IMPORT_SEEDED_FOREGROUND", "--es", "run_id", run_id,
            "--es", "operation_id", operation_id,
        )
        result = wait_for_import(serial, args.package, run_id, operation_id, args.timeout_seconds)
        filename = "index-import-result.json"
    elif args.action == "resume":
        operation_id = uuid.uuid4().hex
        adb(
            serial, "shell", "am", "broadcast", "-n", f"{args.package}/.TestGallerySeederReceiver",
            "-a", "com.askphotos.android.test.RESUME_INDEXING", "--es", "run_id", run_id,
            "--es", "operation_id", operation_id,
        )
        result = wait_for_operation(
            serial, args.package, run_id, operation_id, "index-resume-status.json", args.timeout_seconds,
        )
        filename = "index-resume-result.json"
    else:
        action = (
            "com.askphotos.android.test.REPORT_INDEX_COVERAGE"
        )
        adb(
            serial, "shell", "am", "broadcast", "-n", f"{args.package}/.TestGallerySeederReceiver",
            "-a", action, "--es", "run_id", run_id,
        )
        status_name = "index-coverage-status.json"
        result = read_complete_status(serial, args.package, run_id, status_name)
        filename = "index-coverage-result.json"
        validate_coverage(result)
    safe_result = {**result, "serial": mask_serial(serial), "package": args.package}
    host = args.artifacts / run_id
    host.mkdir(parents=True, exist_ok=True)
    (host / filename).write_text(json.dumps(safe_result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(safe_result, indent=2))


if __name__ == "__main__":
    main()
