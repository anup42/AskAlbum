from __future__ import annotations

import json
import unittest

from cleanup_gallery import parse_cleanup_status


class CleanupOperationStatusTest(unittest.TestCase):
    def test_accepts_only_the_matching_terminal_operation(self) -> None:
        operation_id = "a" * 32
        complete = json.dumps({
            "state": "COMPLETE",
            "runId": "cleanup_run",
            "operationId": operation_id,
            "remainingCount": 0,
        }).encode()
        self.assertEqual("COMPLETE", parse_cleanup_status(complete, "cleanup_run", operation_id)["state"])
        self.assertIsNone(parse_cleanup_status(complete, "cleanup_run", "b" * 32))
        self.assertIsNone(parse_cleanup_status(complete, "another_run", operation_id))

    def test_matching_failure_is_not_silently_retried(self) -> None:
        failure = json.dumps({
            "state": "FAILED",
            "runId": "cleanup_run",
            "operationId": "a" * 32,
            "error": "bounded failure",
        }).encode()
        with self.assertRaisesRegex(RuntimeError, "bounded failure"):
            parse_cleanup_status(failure, "cleanup_run", "a" * 32)


if __name__ == "__main__":
    unittest.main()
