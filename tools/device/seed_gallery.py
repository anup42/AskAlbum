from __future__ import annotations

import argparse
import base64
import concurrent.futures
import hashlib
import json
import math
import re
import time
import uuid
import zipfile
from pathlib import Path

from common import adb, mask_serial, require_run_id, resolve_serial, retry_transient, run_as_read


def parse_external_path(response: str, package: str, run_id: str) -> str:
    match = re.search(r"(?:^|[,\[{ ])path=([^,}\]]+)", response)
    if not match:
        raise RuntimeError(f"Provider did not return an external staging path: {response[-1000:]}")
    path = match.group(1).strip()
    expected_suffix = f"/Android/data/{package}/files/test-seed-transfer/{run_id}.zip"
    if not path.startswith("/") or ".." in path.split("/") or not path.endswith(expected_suffix):
        raise RuntimeError("Provider returned an unsafe external staging path")
    return path


def validate_transport_mode(transport: str, stage_only: bool) -> None:
    if stage_only and transport != "external-file":
        raise RuntimeError("--stage-only requires --transport external-file")


def start_seed_service(serial: str, package: str, run_id: str) -> None:
    result = adb(
        serial,
        "shell",
        "am",
        "start-foreground-service",
        "-n",
        f"{package}/.TestGallerySeederService",
        "-a",
        "com.askphotos.android.test.SEED_GALLERY_FOREGROUND",
        "--es",
        "run_id",
        run_id,
    )
    response = (result.stdout + result.stderr).decode(errors="replace")
    if "Error:" in response or "Exception" in response:
        raise RuntimeError(f"Could not start foreground test seeder: {response[-1000:]}")


def wait_for_seed_completion(serial: str, package: str, run_id: str, timeout_seconds: float) -> dict[str, object]:
    relative_path = f"files/test-seed/{run_id}/status.json"
    deadline = time.monotonic() + timeout_seconds
    last: dict[str, object] | None = None
    while time.monotonic() < deadline:
        payload = run_as_read(serial, package, relative_path)
        if payload:
            try:
                last = json.loads(payload)
                if last.get("state") == "FAILED":
                    raise RuntimeError(f"Device operation failed: {last.get('error')}")
                if last.get("state") == "COMPLETE" and last.get("stagingRemoved") is True:
                    validated = parse_complete_seed(payload, run_id)
                    if validated is None:
                        raise RuntimeError("Device returned an invalid or duplicate completed seed manifest")
                    return validated
            except json.JSONDecodeError:
                pass
        time.sleep(0.25)
    raise RuntimeError(f"Timed out waiting for finalized seed result; last status={last}")


def print_result_summary(result: dict[str, object]) -> None:
    summary = {key: value for key, value in result.items() if key != "createdUris"}
    uris = result.get("createdUris")
    if isinstance(uris, list):
        summary["createdUriCount"] = len(uris)
    print(json.dumps(summary, indent=2))


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
    if result.get("createdCount") != len(uris) or len(set(uris)) != len(uris):
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


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial")
    parser.add_argument("--package", default="com.askphotos.android")
    parser.add_argument("--gallery", type=Path, required=True)
    parser.add_argument("--run-id")
    parser.add_argument("--artifacts", type=Path, default=Path("artifacts/device-runs"))
    parser.add_argument("--reset-transfer", action="store_true")
    parser.add_argument("--transport", choices=("chunked", "external-file"), default="chunked")
    parser.add_argument("--stage-only", action="store_true")
    parser.add_argument("--timeout-seconds", type=int, default=900)
    args = parser.parse_args()
    if not 30 <= args.timeout_seconds <= 3600:
        raise RuntimeError("--timeout-seconds must be between 30 and 3600")
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
        start_seed_service(serial, args.package, run_id)
        resumed = wait_for_seed_completion(serial, args.package, run_id, args.timeout_seconds)
        safe_result = {**resumed, "resumedRunningSeed": True, "serial": mask_serial(serial), "package": args.package}
        (host / "seed-result.json").write_text(json.dumps(safe_result, indent=2) + "\n", encoding="utf-8")
        print_result_summary(safe_result)
        return
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
        print_result_summary(safe_result)
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
    archive_sha256 = sha256_file(archive)
    transport = args.transport
    validate_transport_mode(transport, args.stage_only)
    try:
        if transport == "external-file":
            if args.reset_transfer:
                adb(serial, "shell", "content", "call", "--uri", provider_root, "--method", "abort", "--arg", run_id, check=False)
            prepared_result = adb(
                serial, "shell", "content", "call", "--uri", provider_root, "--method", "prepare_external", "--arg", run_id,
            )
            prepared = (prepared_result.stdout + prepared_result.stderr).decode(errors="replace")
            if "Error while accessing provider" in prepared or "state=READY" not in prepared:
                raise RuntimeError(f"Provider did not prepare external staging: {prepared[-1000:]}")
            external_path = parse_external_path(prepared, args.package, run_id)
            adb(serial, "push", str(archive), external_path, timeout_seconds=900)
            adopted_result = adb(
                serial, "shell", "content", "call", "--uri", provider_root, "--method", "adopt_external", "--arg", run_id,
                "--extra", f"total_bytes:s:{total_bytes}", "--extra", f"sha256:s:{archive_sha256}", timeout_seconds=300,
            )
            adopted = (adopted_result.stdout + adopted_result.stderr).decode(errors="replace")
            if "Error while accessing provider" in adopted or "state=COMPLETE" not in adopted or archive_sha256 not in adopted:
                raise RuntimeError(f"Provider rejected external archive: {adopted[-1000:]}")
            if args.stage_only:
                safe_result = {
                    "state": "STAGED",
                    "runId": run_id,
                    "transport": "external_file",
                    "size": total_bytes,
                    "sha256": archive_sha256,
                    "serial": mask_serial(serial),
                    "package": args.package,
                }
                (host / "staging-result.json").write_text(json.dumps(safe_result, indent=2) + "\n", encoding="utf-8")
                print_result_summary(safe_result)
                return
            start_seed_service(serial, args.package, run_id)
            result = wait_for_seed_completion(serial, args.package, run_id, args.timeout_seconds)
            safe_result = {**result, "retriedCalls": 0, "transport": "external_file",
                           "serial": mask_serial(serial), "package": args.package}
            (host / "seed-result.json").write_text(json.dumps(safe_result, indent=2) + "\n", encoding="utf-8")
            print_result_summary(safe_result)
            return
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
        start_seed_service(serial, args.package, run_id)
        result = wait_for_seed_completion(serial, args.package, run_id, args.timeout_seconds)
        result["retriedCalls"] = retry_count
    except BaseException:
        failure = {"runId": run_id, "state": "INCOMPLETE", "message": "Transfer preserved for a validated resume"}
        (host / "transfer-failure.json").write_text(json.dumps(failure, indent=2) + "\n", encoding="utf-8")
        raise
    safe_result = {**result, "serial": mask_serial(serial), "package": args.package}
    (host / "seed-result.json").write_text(json.dumps(safe_result, indent=2) + "\n", encoding="utf-8")
    print_result_summary(safe_result)


if __name__ == "__main__":
    main()
