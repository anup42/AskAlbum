from __future__ import annotations

import argparse
import json
import os
import subprocess
from pathlib import Path

from common import adb, require_run_id, resolve_serial


ROOT = Path(__file__).resolve().parents[2]
GRADLE_ROOT = ROOT / "android"
TEST_CLASS = "io.github.anup42.askalbum.StoredStressVectorRetrievalAcceptanceTest"
EXPECTED_COUNT = 5_000


def test_artifact(variant: str) -> tuple[str, Path]:
    variants = {
        "consumerDebug": (
            "ConsumerDebug",
            ROOT / "android/app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk",
        ),
        "offlineDemoDebug": (
            "OfflineDemoDebug",
            ROOT / "android/app/build/outputs/apk/androidTest/offlineDemo/debug/app-offlineDemo-debug-androidTest.apk",
        ),
    }
    try:
        return variants[variant]
    except KeyError as error:
        raise ValueError(f"Unsupported variant: {variant}") from error


def require_expected_count(value: int) -> int:
    if value != EXPECTED_COUNT:
        raise RuntimeError(f"Stored retrieval acceptance requires exactly {EXPECTED_COUNT} items")
    return value


def run_build(task_variant: str, output: Path, env: dict[str, str]) -> None:
    result = subprocess.run(
        [str(GRADLE_ROOT / "gradlew.bat"), f":app:assemble{task_variant}AndroidTest", "--console=plain"],
        cwd=GRADLE_ROOT,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        check=False,
    )
    output.write_text(result.stdout, encoding="utf-8")
    if result.returncode:
        raise RuntimeError(f"Android-test build failed; see {output}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Run retained, fully indexed 5k stored-vector acceptance")
    parser.add_argument("--serial")
    parser.add_argument("--package", default="io.github.anup42.askalbum")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--variant", choices=("consumerDebug", "offlineDemoDebug"), default="consumerDebug")
    parser.add_argument("--expected-count", type=int, default=EXPECTED_COUNT)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--timeout-seconds", type=int, default=600)
    parser.add_argument("--artifacts", type=Path, default=ROOT / "artifacts/device-runs")
    args = parser.parse_args()

    serial = resolve_serial(args.serial)
    run_id = require_run_id(args.run_id)
    expected_count = require_expected_count(args.expected_count)
    if not 60 <= args.timeout_seconds <= 1_800:
        raise RuntimeError("--timeout-seconds must be between 60 and 1800")
    artifacts = args.artifacts / run_id / "stored-5k-retrieval"
    artifacts.mkdir(parents=True, exist_ok=True)
    task_variant, test_apk = test_artifact(args.variant)

    if adb(serial, "shell", "pm", "path", args.package, check=False).returncode:
        raise RuntimeError(f"Target package is not installed: {args.package}")
    marker = f"files/test-install-preservation-{run_id}"
    adb(serial, "shell", "run-as", args.package, "touch", marker)
    try:
        if not args.skip_build:
            sdk_root = str(Path(subprocess.check_output(["where.exe", "adb"], text=True).splitlines()[0]).parent.parent)
            env = {**os.environ, "ANDROID_HOME": sdk_root, "ANDROID_SDK_ROOT": sdk_root, "ANDROID_SERIAL": serial}
            run_build(task_variant, artifacts / "gradle-build.txt", env)
        if not test_apk.is_file():
            raise RuntimeError(f"Android-test APK is missing: {test_apk}")
        install = adb(serial, "install", "-r", "-t", str(test_apk), timeout_seconds=300)
        (artifacts / "test-apk-install.txt").write_bytes(install.stdout + install.stderr)
        if adb(serial, "shell", "run-as", args.package, "ls", marker, check=False).returncode:
            raise RuntimeError("Android-test install erased target app-private data")

        instrumentation = adb(
            serial,
            "shell",
            "am",
            "instrument",
            "-w",
            "-r",
            "-e",
            "class",
            TEST_CLASS,
            "-e",
            "galleryRunId",
            run_id,
            "-e",
            "galleryExpectedCount",
            str(expected_count),
            f"{args.package}.test/androidx.test.runner.AndroidJUnitRunner",
            timeout_seconds=args.timeout_seconds,
            check=False,
        )
        output = instrumentation.stdout + instrumentation.stderr
        (artifacts / "instrumentation.txt").write_bytes(output)
        if instrumentation.returncode or b"OK (1 test)" not in instrumentation.stdout:
            raise RuntimeError(f"Stored 5k retrieval acceptance failed; see {artifacts / 'instrumentation.txt'}")
        summary = {
            "state": "PASS",
            "runId": run_id,
            "expectedCount": expected_count,
            "testClass": TEST_CLASS,
            "variant": args.variant,
        }
        (artifacts / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        print(f"Stored 5k retrieval acceptance passed; artifacts: {artifacts}")
    finally:
        adb(serial, "shell", "run-as", args.package, "rm", marker, check=False)


if __name__ == "__main__":
    main()
