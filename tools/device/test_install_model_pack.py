from __future__ import annotations

import json
import unittest

from install_model_pack import DEVICE_ACTIONS, parse_status, validate_status


class InstallModelPackHarnessTest(unittest.TestCase):
    def status(self, **overrides: object) -> dict[str, object]:
        result: dict[str, object] = {
            "state": "COMPLETE",
            "operationId": "a" * 32,
            "tier": "E2B",
            "downloadState": "DOWNLOADING",
            "bytesDownloaded": 50,
            "totalBytes": 100,
            "installed": False,
            "sha256": "b" * 64,
        }
        result.update(overrides)
        return result

    def test_accepts_only_correlated_valid_status(self) -> None:
        self.assertEqual("REPORT", DEVICE_ACTIONS["status"])
        payload = json.dumps(self.status()).encode()
        self.assertEqual("DOWNLOADING", parse_status(payload, "E2B", "a" * 32)["downloadState"])
        self.assertIsNone(parse_status(payload, "E2B", "c" * 32))

    def test_rejects_invalid_counts_and_false_installed_terminal(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "byte counts"):
            validate_status(self.status(bytesDownloaded=101), "E2B")
        with self.assertRaisesRegex(RuntimeError, "inconsistent installation"):
            validate_status(self.status(downloadState="INSTALLED", bytesDownloaded=100), "E2B")


if __name__ == "__main__":
    unittest.main()
