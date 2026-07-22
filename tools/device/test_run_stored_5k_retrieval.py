from __future__ import annotations

import unittest

from run_stored_5k_retrieval import EXPECTED_COUNT, ROOT, require_expected_count, test_artifact


class Stored5kRetrievalHarnessTest(unittest.TestCase):
    def test_consumer_test_artifact_is_non_uninstalling_android_test_apk(self) -> None:
        task, artifact = test_artifact("consumerDebug")
        self.assertEqual("ConsumerDebug", task)
        self.assertEqual(
            ROOT / "android/app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk",
            artifact,
        )

    def test_acceptance_requires_exact_target_scale(self) -> None:
        self.assertEqual(EXPECTED_COUNT, require_expected_count(EXPECTED_COUNT))
        for value in (0, 4_999, 5_001, 20_000):
            with self.subTest(value=value), self.assertRaisesRegex(RuntimeError, "exactly 5000"):
                require_expected_count(value)

    def test_unknown_variant_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "Unsupported variant"):
            test_artifact("debug")


if __name__ == "__main__":
    unittest.main()
