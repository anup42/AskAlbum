from __future__ import annotations

import argparse
import base64
import concurrent.futures
import hashlib
import json
import math
import re
import uuid
import zipfile
from pathlib import Path

from common import adb, mask_serial, require_run_id, resolve_serial, retry_transient, run_as_read, wait_for_json


def parse_complete_seed(payload: bytes | None, run_id: str) -> dict[str, object] | None:
    if not payload:
        return None
    try:
        result = json.loads(payload)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return None
    if result.get("state") != "COMPLETE" or result.get("runId") != run_id:
        return None
    uris = result.get("createdUris")
    if not isinstance(uris, list) or not uris or not all(
        isinstance(uri, str) and uri.startswith("content://media/") for uri in uris
    ):
        return None
    return result


def device_mtime(serial: str, package: str, relative_path: str) -> int | None:
    result = adb(serial, "shell", "run-as", package, "stat", "-c", "%Y", relative_path, check=False)
    if result.returncode:
        return None
    try:
        return int(result.stdout.decode().strip())
    except ValueError:
        return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial")
    parser.add_argument("--package", default="com.askphotos.android")
    parser.add_argument("--gallery", type=Path, required=True)
    parser.add_argument("--run-id")
    parser.add_argument("--artifacts", type=Path, default=Path("artifacts/device-runs"))
    parser.add_argument("--reset-transfer", action="store_true")
    args = parser.parse_args()
    serial = resolve_serial(args.serial)
    run_id = require_run_id(args.run_id or f"run_{uuid.uuid4().hex[:12]}")
    manifest_path = args.gallery / "gallery-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    media_root = args.gallery / "media"
    items = manifest.get("items", [])
    if not items:
        raise RuntimeError("Gallery manifest has no items")
    base = f"files/test-seed/{run_id}"
    for item in items:
        filename = item["filename"]
        source = media_root / filename
        if not source.is_file() or source.parent.resolve() != media_root.resolve():
            raise RuntimeError(f"Unsafe or missing gallery item: {filename}")
    host = args.artifacts / run_id
    host.mkdir(parents=True, exist_ok=True)
    cleanup = run_as_read(serial, args.package, f"{base}/cleanup-status.json")
    try:
        cleanup_complete = bool(cleanup and json.loads(cleanup).get("state") == "COMPLETE")
    except (json.JSONDecodeError, UnicodeDecodeError):
        cleanup_complete = False
    status_payload = run_as_read(serial, args.package, f"{base}/status.json")
    try:
        status = json.loads(status_payload) if status_payload else None
    except (json.JSONDecodeError, UnicodeDecodeError):
        status = None
    if not cleanup_complete and isinstance(status, dict) and status.get("state") == "RUNNING":
        status = wait_for_json(serial, args.package, f"{base}/status.json", timeout_seconds=180)
    status_mtime = device_mtime(serial, args.package, f"{base}/status.json")
    cleanup_mtime = device_mtime(serial, args.package, f"{base}/cleanup-status.json")
    cleanup_supersedes_seed = cleanup_complete and (
        status_mtime is None or cleanup_mtime is None or cleanup_mtime >= status_mtime
    )
    existing = None if cleanup_supersedes_seed else parse_complete_seed(
        json.dumps(status).encode("utf-8") if isinstance(status, dict) else status_payload,
        run_id,
    )
    if existing is not None:
        safe_result = {**existing, "retriedCalls": 0, "resumedExistingSeed": True,
                       "serial": mask_serial(serial), "package": args.package}
        (host / "seed-result.json").write_text(json.dumps(safe_result, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(safe_result, indent=2))
        return
    if cleanup_supersedes_seed and not args.reset_transfer:
        raise RuntimeError("This run ID was already cleaned; use a new run ID or --reset-transfer")
    archive = host / "gallery-seed.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_STORED) as bundle:
        bundle.write(manifest_path, "gallery-manifest.json")
        for item in items:
            filename = item["filename"]
            bundle.write(media_root / filename, f"media/{filename}")
    provider_root = f"content://{args.package}.testseed"
    total_bytes = archive.stat().st_size
    chunk_size = 12 * 1024
    chunk_count = math.ceil(total_bytes / chunk_size)
    archive_sha256 = hashlib.sha256(archive.read_bytes()).hexdigest()
    try:
        if args.reset_transfer:
            adb(
                serial, "shell", "content", "call", "--uri", provider_root,
                "--method", "abort", "--arg", run_id, check=False,
            )
        initialized_result = adb(
            serial, "shell", "content", "call", "--uri", provider_root, "--method", "init", "--arg", run_id,
            "--extra", f"total_bytes:s:{total_bytes}", "--extra", f"chunk_size:s:{chunk_size}",
            "--extra", f"chunk_count:s:{chunk_count}", "--extra", f"sha256:s:{archive_sha256}",
        )
        initialized = (initialized_result.stdout + initialized_result.stderr).decode(errors="replace")
        if "Error while accessing provider" in initialized:
            raise RuntimeError(f"Provider initialization failed: {initialized[-1000:]}")
        bitmap_match = re.search(r"present_bitmap=([01]+)", initialized)
        if not bitmap_match or len(bitmap_match.group(1)) != chunk_count:
            raise RuntimeError(f"Provider returned invalid transfer bitmap: {initialized[-1000:]}")
        bitmap = bitmap_match.group(1)
        archive_bytes = archive.read_bytes()
        missing = [index for index, present in enumerate(bitmap) if present == "0"]

        def upload(index: int) -> tuple[int, int]:
            start = index * chunk_size
            payload = archive_bytes[start:start + chunk_size]
            encoded = base64.urlsafe_b64encode(payload).decode("ascii")
            chunk_sha256 = hashlib.sha256(payload).hexdigest()
            def write_once() -> None:
                result = adb(
                    serial, "shell", "content", "call", "--uri", provider_root,
                    "--method", "write_chunk", "--arg", run_id,
                    "--extra", f"index:s:{index}",
                    "--extra", f"expected_length:s:{len(payload)}",
                    "--extra", f"sha256:s:{chunk_sha256}",
                    "--extra", f"data:s:{encoded}",
                    timeout_seconds=30,
                )
                response = (result.stdout + result.stderr).decode(errors="replace")
                if "Error while accessing provider" in response or "state=WRITTEN" not in response or chunk_sha256 not in response:
                    raise RuntimeError(f"Provider rejected chunk {index}: {response[-1000:]}")

            _, retries = retry_transient(write_once, attempts=4)
            return index, retries

        completed = chunk_count - len(missing)
        retry_count = 0
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
            futures = [executor.submit(upload, index) for index in missing]
            for future in concurrent.futures.as_completed(futures):
                _, retries = future.result()
                retry_count += retries
                completed += 1
                if completed % 50 == 0 or completed == chunk_count:
                    progress = {
                        "runId": run_id,
                        "completedChunks": completed,
                        "chunkCount": chunk_count,
                        "retriedCalls": retry_count,
                    }
                    (host / "transfer-progress.json").write_text(json.dumps(progress, indent=2) + "\n", encoding="utf-8")
                    print(f"Transferred {completed}/{chunk_count} exact-size chunks", flush=True)
        finalized_result = adb(
            serial, "shell", "content", "call", "--uri", provider_root,
            "--method", "finalize", "--arg", run_id,
        )
        finalized = (finalized_result.stdout + finalized_result.stderr).decode(errors="replace")
        if "Error while accessing provider" in finalized or "state=COMPLETE" not in finalized or archive_sha256 not in finalized or str(total_bytes) not in finalized:
            raise RuntimeError(f"Provider did not confirm complete transfer: {finalized[-1000:]}")
        adb(
            serial, "shell", "am", "broadcast", "-n", f"{args.package}/.TestGallerySeederReceiver",
            "-a", "com.askphotos.android.test.SEED_GALLERY", "--es", "run_id", run_id,
        )
        result = wait_for_json(serial, args.package, f"{base}/status.json", timeout_seconds=180)
        result["retriedCalls"] = retry_count
    except BaseException:
        failure = {"runId": run_id, "state": "INCOMPLETE", "message": "Transfer preserved for a validated resume"}
        (host / "transfer-failure.json").write_text(json.dumps(failure, indent=2) + "\n", encoding="utf-8")
        raise
    safe_result = {**result, "serial": mask_serial(serial), "package": args.package}
    (host / "seed-result.json").write_text(json.dumps(safe_result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(safe_result, indent=2))


if __name__ == "__main__":
    main()
