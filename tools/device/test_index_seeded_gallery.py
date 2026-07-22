from __future__ import annotations

import json
import unittest

from index_seeded_gallery import parse_import_status, validate_coverage


class SeededIndexHarnessTest(unittest.TestCase):
    def test_import_accepts_only_matching_complete_exact_counts(self) -> None:
        operation_id = "a" * 32
        payload = json.dumps({
            "state": "COMPLETE",
            "runId": "index_run",
            "operationId": operation_id,
            "requestedCount": 5000,
            "importedCount": 5000,
        }).encode()
        self.assertEqual(5000, parse_import_status(payload, "index_run", operation_id)["importedCount"])
        self.assertIsNone(parse_import_status(payload, "index_run", "b" * 32))

    def test_coverage_requires_every_stage_to_cover_the_same_scope(self) -> None:
        valid = {
            "state": "COMPLETE",
            "expectedCount": 5000,
            "mediaCount": 5000,
            "uniqueMediaIds": 5000,
            "stages": {
                "DISCOVERY": {"COMPLETE": 5000},
                "METADATA": {"COMPLETE": 5000},
                "THUMBNAIL": {"COMPLETE": 5000},
                "VIDEO_KEYFRAMES": {"SKIPPED": 5000},
                "EMBEDDING": {"PENDING": 4500, "COMPLETE": 500},
                "OCR": {"COMPLETE": 5000},
                "FACES": {"SKIPPED": 5000},
                "EVENTS": {"COMPLETE": 5000},
                "ENRICHMENT": {"COMPLETE": 5000},
            },
        }
        validate_coverage(valid)
        invalid = {**valid, "stages": {**valid["stages"], "EMBEDDING": {"PENDING": 4999}}}
        with self.assertRaisesRegex(RuntimeError, "EMBEDDING"):
            validate_coverage(invalid)
        missing = {**valid, "stages": {key: value for key, value in valid["stages"].items() if key != "OCR"}}
        with self.assertRaisesRegex(RuntimeError, "exact stage set"):
            validate_coverage(missing)


if __name__ == "__main__":
    unittest.main()
