from __future__ import annotations

import argparse
import json
from pathlib import Path

from common import adb, mask_serial, require_run_id, resolve_serial, wait_for_json


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial")
    parser.add_argument("--package", default="com.askphotos.android")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--artifacts", type=Path, default=Path("artifacts/device-runs"))
    args = parser.parse_args()
    serial = resolve_serial(args.serial)
    run_id = require_run_id(args.run_id)
    base = f"files/test-seed/{run_id}"
    adb(
        serial, "shell", "am", "broadcast", "-n", f"{args.package}/.TestGallerySeederReceiver",
        "-a", "com.askphotos.android.test.CLEANUP_GALLERY", "--es", "run_id", run_id,
    )
    result = wait_for_json(serial, args.package, f"{base}/cleanup-status.json", timeout_seconds=120)
    if result.get("remainingCount") != 0:
        raise RuntimeError(f"Cleanup left {result.get('remainingCount')} items in the run-specific album")
    host = args.artifacts / run_id
    host.mkdir(parents=True, exist_ok=True)
    safe_result = {**result, "serial": mask_serial(serial), "package": args.package}
    (host / "cleanup-result.json").write_text(json.dumps(safe_result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(safe_result, indent=2))


if __name__ == "__main__":
    main()
