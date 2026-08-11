from __future__ import annotations

import json
import unittest
from pathlib import Path


FIXTURE_PATH = Path(__file__).with_name("expected_queries.yaml")
ALLOWED_EXACTNESS = {
    "EXACT",
    "COMPLETE_PREDICATE_SCAN",
    "ESTIMATED_FROM_RETRIEVAL",
    "PARTIAL_INDEX",
}
ESTIMATED_QUERY_IDS = {"Q01", "Q02", "Q06", "Q07", "Q08", "Q09", "Q11", "Q12", "Q13"}
EXACT_QUERY_IDS = {"Q03", "Q04", "Q05", "Q10", "Q14"}


class ExpectedQueriesContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.fixture = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
        cls.queries = cls.fixture["queries"]
        cls.queries_by_id = {query["id"]: query for query in cls.queries}

    def test_query_ids_are_unique(self) -> None:
        self.assertEqual(len(self.queries), len(self.queries_by_id))

    def test_exactness_uses_runtime_vocabulary(self) -> None:
        for query in self.queries:
            self.assertIn(query["exactness"], ALLOWED_EXACTNESS, query["id"])
            self.assertNotEqual("COMPLETE_MODEL_SCAN", query["exactness"], query["id"])

    def test_bounded_retrieval_queries_are_estimated(self) -> None:
        for query_id in ESTIMATED_QUERY_IDS:
            self.assertEqual(
                "ESTIMATED_FROM_RETRIEVAL",
                self.queries_by_id[query_id]["exactness"],
                query_id,
            )

    def test_deterministic_queries_are_exact(self) -> None:
        for query_id in EXACT_QUERY_IDS:
            self.assertEqual("EXACT", self.queries_by_id[query_id]["exactness"], query_id)

    def test_visual_and_semantic_evidence_is_never_exact(self) -> None:
        bounded_evidence = {"semantic", "people", "visual_verification", "video_keyframe", "event"}
        for query in self.queries:
            if bounded_evidence.intersection(query.get("evidence_types", [])):
                self.assertNotEqual("EXACT", query["exactness"], query["id"])


if __name__ == "__main__":
    unittest.main()
