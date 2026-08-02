from __future__ import annotations

import argparse
import json
from pathlib import Path

from common import adb, mask_serial, require_run_id, resolve_serial, wait_for_json


def broadcast(serial: str, package: str, action: str, run_id: str) -> None:
    adb(
        serial, "shell", "am", "broadcast", "-n", f"{package}/.TestGallerySeederReceiver",
        "-f", "0x20", "-a", action, "--es", "run_id", run_id,
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Verify persisted index recovery across a forced process stop")
    parser.add_argument("--serial")
    parser.add_argument("--package", default="io.github.anup42.askalbum")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--artifacts", type=Path, default=Path("artifacts/device-runs"))
    args = parser.parse_args()
    serial = resolve_serial(args.serial)
    run_id = require_run_id(args.run_id)
    root = f"files/test-seed/{run_id}"
    broadcast(serial, args.package, "io.github.anup42.askalbum.test.PREPARE_INDEX_INTERRUPTION", run_id)
    prepared = wait_for_json(serial, args.package, f"{root}/recovery-prepare-status.json", 180)
    adb(serial, "shell", "am", "force-stop", args.package)
    broadcast(serial, args.package, "io.github.anup42.askalbum.test.VERIFY_INDEX_RECOVERY", run_id)
    recovered = wait_for_json(serial, args.package, f"{root}/recovery-verify-status.json", 180)
    if recovered.get("runningStages") != 0 or recovered.get("indexingRows") != 0:
        raise RuntimeError(f"Incomplete recovery: {recovered}")
    host = args.artifacts / run_id
    host.mkdir(parents=True, exist_ok=True)
    result = {"state": "COMPLETE", "serial": mask_serial(serial), "package": args.package, "prepared": prepared, "recovered": recovered}
    (host / "index-recovery-result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
