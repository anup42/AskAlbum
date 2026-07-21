from __future__ import annotations

import argparse
import json
from pathlib import Path

from common import adb, mask_serial, require_run_id, resolve_serial, wait_for_json


ACTIONS = {
    "import": ("com.askphotos.android.test.IMPORT_SEEDED", "import-status.json"),
    "remove": ("com.askphotos.android.test.REMOVE_IMPORTED", "db-cleanup-status.json"),
}


def main() -> None:
    parser = argparse.ArgumentParser(description="Import or remove one run-scoped seeded gallery in the app database")
    parser.add_argument("--serial")
    parser.add_argument("--package", default="com.askphotos.android")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--action", choices=sorted(ACTIONS), required=True)
    parser.add_argument("--artifacts", type=Path, default=Path("artifacts/device-runs"))
    args = parser.parse_args()
    serial = resolve_serial(args.serial)
    run_id = require_run_id(args.run_id)
    action, status_name = ACTIONS[args.action]
    adb(
        serial, "shell", "am", "broadcast", "-n", f"{args.package}/.TestGallerySeederReceiver",
        "-a", action, "--es", "run_id", run_id,
    )
    result = wait_for_json(
        serial, args.package, f"files/test-seed/{run_id}/{status_name}", timeout_seconds=180,
    )
    if args.action == "import" and result.get("importedCount") != result.get("requestedCount"):
        raise RuntimeError(f"Import count mismatch: {result}")
    if args.action == "remove" and result.get("remainingCount") != 0:
        raise RuntimeError(f"Database cleanup left seeded rows: {result}")
    host = args.artifacts / run_id
    host.mkdir(parents=True, exist_ok=True)
    safe = {**result, "serial": mask_serial(serial), "package": args.package}
    (host / f"database-{args.action}-result.json").write_text(json.dumps(safe, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(safe, indent=2))


if __name__ == "__main__":
    main()
