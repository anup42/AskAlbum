from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import uuid
from pathlib import Path

from common import adb, require_run_id, resolve_serial
from preflight import collect as collect_preflight


ROOT = Path(__file__).resolve().parents[2]


def run(args: list[str], log: Path, env: dict[str, str] | None = None) -> None:
    result = subprocess.run(args, cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=False)
    log.parent.mkdir(parents=True, exist_ok=True)
    log.write_text(result.stdout, encoding="utf-8")
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {' '.join(args)}; see {log}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the safe core connected-device acceptance flow")
    parser.add_argument("--serial")
    parser.add_argument("--package", default="com.askphotos.android")
    parser.add_argument("--run-id")
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()
    serial = resolve_serial(args.serial)
    run_id = require_run_id(args.run_id or f"accept_{uuid.uuid4().hex[:12]}")
    artifacts = ROOT / "artifacts" / "device-runs" / run_id
    artifacts.mkdir(parents=True, exist_ok=True)
    (artifacts / "preflight.json").write_text(json.dumps(collect_preflight(serial), indent=2) + "\n", encoding="utf-8")
    gallery = ROOT / "build" / "sample-gallery" / "core"
    run(
        [sys.executable, "tools/sample_gallery/build_sample_gallery.py", "--profile", "core", "--output", str(gallery)],
        artifacts / "corpus-build.txt",
    )
    run([sys.executable, "tools/sample_gallery/verify_licenses.py", "--gallery", str(gallery)], artifacts / "license-check.txt")
    sdk_root = str(Path(subprocess.check_output(["where.exe", "adb"], text=True).splitlines()[0]).parent.parent)
    env = {**os.environ, "ANDROID_HOME": sdk_root, "ANDROID_SDK_ROOT": sdk_root, "ANDROID_SERIAL": serial}
    if not args.skip_build:
        run(
            [str(ROOT / "android" / "gradlew.bat"), ":app:assembleDebug", ":app:assembleDebugAndroidTest", "--console=plain"],
            artifacts / "gradle-build.txt", env,
        )
        app_apk = ROOT / "android/app/build/outputs/apk/debug/app-debug.apk"
        test_apk = ROOT / "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
        adb(serial, "install", "-r", "-t", str(app_apk), timeout_seconds=300)
        adb(serial, "install", "-r", "-t", str(test_apk), timeout_seconds=300)
    seeded = False
    imported = False
    try:
        run(
            [sys.executable, "tools/device/seed_gallery.py", "--serial", serial, "--package", args.package,
             "--gallery", str(gallery), "--run-id", run_id, "--artifacts", str(artifacts.parent)],
            artifacts / "seed-command.txt",
        )
        seeded = True
        expected_count = len(json.loads((gallery / "gallery-manifest.json").read_text(encoding="utf-8"))["items"])
        run(
            [sys.executable, "tools/device/sync_seeded_gallery.py", "--serial", serial, "--package", args.package,
             "--run-id", run_id, "--action", "import", "--artifacts", str(artifacts.parent)],
            artifacts / "database-import-command.txt",
        )
        imported = True
        run(
            [sys.executable, "tools/device/test_index_recovery.py", "--serial", serial, "--package", args.package,
             "--run-id", run_id, "--artifacts", str(artifacts.parent)],
            artifacts / "index-recovery-command.txt",
        )
        for test_class, log_name in (
            ("com.askphotos.android.SeededGalleryTest", "seeded-gallery-instrumentation.txt"),
            ("com.askphotos.android.IndexRecoveryTest", "index-recovery-instrumentation.txt"),
            ("com.askphotos.android.SeededGalleryDisplayTest", "seeded-gallery-display-instrumentation.txt"),
        ):
            instrumentation = adb(
                serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", test_class,
                "-e", "galleryRunId", run_id, "-e", "galleryExpectedCount", str(expected_count),
                f"{args.package}.test/androidx.test.runner.AndroidJUnitRunner", timeout_seconds=180,
            )
            (artifacts / log_name).write_bytes(instrumentation.stdout + instrumentation.stderr)
            if b"OK (1 test)" not in instrumentation.stdout:
                raise RuntimeError(f"{test_class} did not report one passing test")
        run(
            [sys.executable, "tools/device/collect_artifacts.py", "--serial", serial, "--package", args.package,
             "--output", str(artifacts / "diagnostics")],
            artifacts / "collect-artifacts.txt",
        )
    finally:
        try:
            if imported:
                run(
                    [sys.executable, "tools/device/sync_seeded_gallery.py", "--serial", serial, "--package", args.package,
                     "--run-id", run_id, "--action", "remove", "--artifacts", str(artifacts.parent)],
                    artifacts / "database-remove-command.txt",
                )
        finally:
            if seeded:
                run(
                    [sys.executable, "tools/device/cleanup_gallery.py", "--serial", serial, "--package", args.package,
                     "--run-id", run_id, "--artifacts", str(artifacts.parent)],
                    artifacts / "cleanup-command.txt",
                )
    print(f"Connected core acceptance passed for {run_id}; artifacts: {artifacts}")


if __name__ == "__main__":
    main()
