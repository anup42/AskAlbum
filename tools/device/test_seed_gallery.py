from __future__ import annotations

import json
import unittest

from seed_gallery import parse_complete_seed


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


if __name__ == "__main__":
    unittest.main()
