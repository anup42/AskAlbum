from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from evaluate_gallery_dataset import complete_index_coverage, prepare_dataset, score_reports, validate_package


class EvaluateGalleryDatasetTest(unittest.TestCase):
    def test_prepare_keeps_scoring_oracle_out_of_device_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            context = [{
                "id": "img_1",
                "image_path": "images/example.jpg",
                "capture_time": "2025-01-02T03:04:05",
                "person_names": ["Example Person"],
                "relationship": ["friend"],
                "event": "example",
            }]
            queries = [{
                "query_id": "q_1",
                "query": "Find the example",
                "gallery_query": "oracle rewrite that must stay on the host",
                "answer": "Secret reference answer",
                "image_ids": ["img_1"],
                "query_asking_time": "2025-01-03T00:00:00",
            }]
            (source / "image_context.json").write_text(json.dumps(context), encoding="utf-8")
            (source / "queries_v2.json").write_text(json.dumps(queries), encoding="utf-8")
            with zipfile.ZipFile(source / "images.zip", "w") as archive:
                archive.writestr("images/example.jpg", b"not-a-real-image")
            before = hashlib.sha256((source / "queries_v2.json").read_bytes()).hexdigest()
            result = prepare_dataset(source, "unit_eval", root / "artifacts")
            manifest = json.loads((Path(result["gallery"]) / "gallery-manifest.json").read_text(encoding="utf-8"))
            oracle = json.loads(Path(result["oracle"]).read_text(encoding="utf-8"))
            device_query = manifest["evaluation"]["queries"][0]
            self.assertEqual({"id", "query"}, set(device_query))
            self.assertNotIn("answer", json.dumps(manifest))
            self.assertNotIn("oracle rewrite", json.dumps(manifest))
            self.assertEqual("Secret reference answer", oracle["queries"][0]["referenceAnswer"])
            self.assertEqual(before, hashlib.sha256((source / "queries_v2.json").read_bytes()).hexdigest())

    def test_metrics_report_search_rouge_and_missing_reference_separately(self) -> None:
        oracle = {"queries": [
            {"queryId": "q1", "referenceImageIds": ["a", "b"], "referenceAnswer": "red car"},
            {"queryId": "q2", "referenceImageIds": ["c"], "referenceAnswer": ""},
        ]}
        reports = [
            {"queryId": "q1", "state": "COMPLETE", "matchedImageIds": ["a", "x"], "answerText": "red car", "latencyMs": 100},
            {"queryId": "q2", "state": "COMPLETE", "matchedImageIds": ["c"], "answerText": "", "latencyMs": 300},
        ]
        summary, rows = score_reports(oracle, reports, top_k=10)
        self.assertAlmostEqual(0.75, summary["search"]["macroPrecision"])
        self.assertAlmostEqual(0.75, summary["search"]["macroRecall"])
        self.assertAlmostEqual(0.75, summary["search"]["macroF1"])
        self.assertEqual(1.0, summary["answer"]["rouge1"])
        self.assertEqual(1.0, summary["answer"]["rouge2"])
        self.assertEqual(1.0, summary["answer"]["rougeL"])
        self.assertEqual(1, summary["answer"]["notScoredNoReferenceCount"])
        self.assertEqual(200.0, summary["latency"]["averageQueryLatencyMs"])
        self.assertEqual("NOT_SCORED_NO_REFERENCE", rows[1]["answerScoreStatus"])

    def test_index_coverage_requires_every_media_row_ready(self) -> None:
        coverage = {
            "mediaCount": 2,
            "uniqueMediaIds": 2,
            "vectorCount": 2,
            "indexStates": {"READY": 1, "INDEXING": 1},
            "stages": {"EMBEDDING": {"COMPLETE": 2}},
        }
        self.assertFalse(complete_index_coverage(coverage, 2))
        coverage["indexStates"] = {"READY": 2}
        self.assertTrue(complete_index_coverage(coverage, 2))

    def test_package_name_rejects_shell_metacharacters(self) -> None:
        self.assertEqual("io.github.example.app", validate_package("io.github.example.app"))
        with self.assertRaises(RuntimeError):
            validate_package("io.github.example.app;id")


if __name__ == "__main__":
    unittest.main()
