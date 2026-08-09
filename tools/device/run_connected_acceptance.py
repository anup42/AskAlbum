from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import uuid
from pathlib import Path

from common import adb, require_run_id, resolve_serial
from preflight import collect as collect_preflight


ROOT = Path(__file__).resolve().parents[2]
GRADLE_ROOT = ROOT / "android"
DEFAULT_TEST_CLASSES = (
    "io.github.anup42.askalbum.SeededGalleryTest",
    "io.github.anup42.askalbum.IndexRecoveryTest",
    "io.github.anup42.askalbum.SeededGalleryDisplayTest",
)

SUCCESSFUL_INSTRUMENTATION = re.compile(rb"(?:^|\r?\n)OK \(([1-9][0-9]*) tests?\)(?:\r?\n|$)")


def instrumentation_passed(output: bytes) -> bool:
    """Accept one or more completed tests, while rejecting empty or failed runs."""
    return b"INSTRUMENTATION_CODE: -1" in output and SUCCESSFUL_INSTRUMENTATION.search(output) is not None


def variant_artifacts(variant: str) -> tuple[str, Path, Path]:
    variants = {
        "consumerDebug": (
            "ConsumerDebug",
            ROOT / "android/app/build/outputs/apk/consumer/debug/app-consumer-debug.apk",
            ROOT / "android/app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk",
        ),
        "offlineDemoDebug": (
            "OfflineDemoDebug",
            ROOT / "android/app/build/outputs/apk/offlineDemo/debug/app-offlineDemo-debug.apk",
            ROOT / "android/app/build/outputs/apk/androidTest/offlineDemo/debug/app-offlineDemo-debug-androidTest.apk",
        ),
        "fixtureCiDebug": (
            "FixtureCiDebug",
            ROOT / "android/app/build/outputs/apk/fixtureCi/debug/app-fixtureCi-debug.apk",
            ROOT / "android/app/build/outputs/apk/androidTest/fixtureCi/debug/app-fixtureCi-debug-androidTest.apk",
        ),
    }
    try:
        return variants[variant]
    except KeyError as error:
        raise ValueError(f"Unsupported variant: {variant}") from error


def variant_package(variant: str) -> str:
    packages = {
        "consumerDebug": "io.github.anup42.askalbum",
        "offlineDemoDebug": "io.github.anup42.askalbum",
        "fixtureCiDebug": "io.github.anup42.askalbum.fixture",
    }
    try:
        return packages[variant]
    except KeyError as error:
        raise ValueError(f"Unsupported variant: {variant}") from error


def component_name(application_package: str, component_package: str, class_name: str) -> str:
    return f"{application_package}/{component_package}.{class_name}"


def run(
    args: list[str], log: Path, env: dict[str, str] | None = None, cwd: Path = ROOT,
) -> None:
    result = subprocess.run(args, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=False)
    log.parent.mkdir(parents=True, exist_ok=True)
    log.write_text(result.stdout, encoding="utf-8")
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {' '.join(args)}; see {log}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the safe core connected-device acceptance flow")
    parser.add_argument("--serial")
    parser.add_argument("--package")
    parser.add_argument("--component-package", default="io.github.anup42.askalbum")
    parser.add_argument("--run-id")
    parser.add_argument("--variant", choices=("consumerDebug", "offlineDemoDebug", "fixtureCiDebug"), default="consumerDebug")
    parser.add_argument("--test-class", action="append", dest="test_classes")
    parser.add_argument("--skip-index-recovery", action="store_true")
    parser.add_argument("--instrument-timeout-seconds", type=int, default=600)
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()
    serial = resolve_serial(args.serial)
    run_id = require_run_id(args.run_id or f"accept_{uuid.uuid4().hex[:12]}")
    package = args.package or variant_package(args.variant)
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
    task_variant, app_apk, test_apk = variant_artifacts(args.variant)
    marker = f"files/test-install-preservation-{run_id}"
    installed_before = b"package:" in adb(serial, "shell", "pm", "path", package, check=False).stdout
    if installed_before:
        adb(serial, "shell", "run-as", package, "touch", marker)
    try:
        if not args.skip_build:
            run(
                [str(GRADLE_ROOT / "gradlew.bat"), f":app:assemble{task_variant}",
                 f":app:assemble{task_variant}AndroidTest", "--console=plain"],
                artifacts / "gradle-build.txt", env, GRADLE_ROOT,
            )
            if not app_apk.is_file() or not test_apk.is_file():
                raise RuntimeError(f"Expected APK outputs are missing for {args.variant}")
            adb(serial, "install", "-r", "-t", str(app_apk), timeout_seconds=300)
            adb(serial, "install", "-r", "-t", str(test_apk), timeout_seconds=300)
        if installed_before:
            preserved = adb(serial, "shell", "run-as", package, "ls", marker, check=False)
            if preserved.returncode:
                raise RuntimeError("Target install erased app-private data; refusing to seed or run acceptance")
    except BaseException:
        if installed_before:
            adb(serial, "shell", "run-as", package, "rm", marker, check=False)
        raise
    seeded = False
    imported = False
    try:
        run(
            [sys.executable, "tools/device/seed_gallery.py", "--serial", serial, "--package", package,
             "--gallery", str(gallery), "--run-id", run_id, "--artifacts", str(artifacts.parent),
             "--transport", "instrumentation"],
            artifacts / "seed-command.txt",
        )
        seeded = True
        expected_count = len(json.loads((gallery / "gallery-manifest.json").read_text(encoding="utf-8"))["items"])
        # Android 15+ may reject a foreground-service start while the target app is backgrounded.
        # Bring the debug target to the foreground before asking its run-scoped seeder service to
        # import the URI manifest. This does not grant permissions or touch unrelated media.
        adb(
            serial,
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            component_name(package, args.component_package, "MainActivity"),
        )
        run(
            [sys.executable, "tools/device/sync_seeded_gallery.py", "--serial", serial, "--package", package,
             "--run-id", run_id, "--action", "import", "--artifacts", str(artifacts.parent)],
            artifacts / "database-import-command.txt",
        )
        imported = True
        if not args.skip_index_recovery:
            run(
                [sys.executable, "tools/device/test_index_recovery.py", "--serial", serial, "--package", package,
                 "--run-id", run_id, "--artifacts", str(artifacts.parent)],
                artifacts / "index-recovery-command.txt",
            )
        test_classes = tuple(args.test_classes or DEFAULT_TEST_CLASSES)
        for index, test_class in enumerate(test_classes, start=1):
            log_name = f"instrumentation-{index:02d}-{test_class.rsplit('.', 1)[-1]}.txt"
            instrumentation = adb(
                serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", test_class,
                "-e", "galleryRunId", run_id, "-e", "galleryExpectedCount", str(expected_count),
                f"{package}.test/androidx.test.runner.AndroidJUnitRunner",
                timeout_seconds=args.instrument_timeout_seconds,
            )
            (artifacts / log_name).write_bytes(instrumentation.stdout + instrumentation.stderr)
            if not instrumentation_passed(instrumentation.stdout):
                raise RuntimeError(f"{test_class} did not report a successful non-empty test run")
        run(
            [sys.executable, "tools/device/collect_artifacts.py", "--serial", serial, "--package", package,
             "--output", str(artifacts / "diagnostics")],
            artifacts / "collect-artifacts.txt",
        )
    finally:
        try:
            if imported:
                run(
                    [sys.executable, "tools/device/sync_seeded_gallery.py", "--serial", serial, "--package", package,
                     "--run-id", run_id, "--action", "remove", "--artifacts", str(artifacts.parent)],
                    artifacts / "database-remove-command.txt",
                )
        finally:
            if seeded:
                run(
                    [sys.executable, "tools/device/cleanup_gallery.py", "--serial", serial, "--package", package,
                     "--run-id", run_id, "--artifacts", str(artifacts.parent)],
                    artifacts / "cleanup-command.txt",
                )
            if installed_before:
                adb(serial, "shell", "run-as", package, "rm", marker, check=False)
    print(f"Connected core acceptance passed for {run_id}; artifacts: {artifacts}")


if __name__ == "__main__":
    main()
