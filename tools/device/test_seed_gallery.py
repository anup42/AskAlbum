from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from seed_gallery import parse_complete_seed, parse_external_path, sha256_file, validate_transport_mode


class ExistingSeedResultTest(unittest.TestCase):
    def test_accepts_only_complete_run_scoped_media_results(self) -> None:
        payload = json.dumps({
            "state": "COMPLETE",
            "runId": "sample_run",
            "createdUris": ["content://media/external_primary/images/media/7"],
        }).encode()
        self.assertEqual("COMPLETE", parse_complete_seed(payload, "sample_run")["state"])
        self.assertIsNone(parse_complete_seed(payload, "different_run"))

    def test_rejects_incomplete_or_non_media_results(self) -> None:
        self.assertIsNone(parse_complete_seed(b'{"state":"RUNNING"}', "sample_run"))
        self.assertIsNone(parse_complete_seed(json.dumps({
            "state": "COMPLETE",
            "runId": "sample_run",
            "createdUris": ["file:///sdcard/picture.jpg"],
        }).encode(), "sample_run"))

    def test_streaming_sha256_does_not_load_the_archive_contract_into_memory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "archive.zip"
            archive.write_bytes(b"agentic-gallery-stress")
            self.assertEqual("66552056b52576f7c98899c56a0366b3c5add5f1fc724844c80bd715b6bd872b", sha256_file(archive))

    def test_external_transport_accepts_only_the_canonical_provider_path(self) -> None:
        response = "Result: Bundle[{path=/storage/emulated/0/Android/data/com.askphotos.android/files/test-seed-transfer/stress_run.zip, state=READY}]"
        self.assertEqual(
            "/storage/emulated/0/Android/data/com.askphotos.android/files/test-seed-transfer/stress_run.zip",
            parse_external_path(response, "com.askphotos.android", "stress_run"),
        )

    def test_external_path_parser_rejects_other_packages_and_traversal(self) -> None:
        with self.assertRaises(RuntimeError):
            parse_external_path("path=/storage/emulated/0/Android/data/other/files/test-seed-transfer/stress_run.zip", "com.askphotos.android", "stress_run")
        with self.assertRaises(RuntimeError):
            parse_external_path("path=/storage/emulated/0/Android/data/com.askphotos.android/files/../test-seed-transfer/stress_run.zip", "com.askphotos.android", "stress_run")

    def test_external_file_route_is_staging_only_until_seeder_acceptance(self) -> None:
        validate_transport_mode("chunked", False)
        validate_transport_mode("external-file", True)
        with self.assertRaises(RuntimeError):
            validate_transport_mode("chunked", True)
        with self.assertRaises(RuntimeError):
            validate_transport_mode("external-file", False)


if __name__ == "__main__":
    unittest.main()
