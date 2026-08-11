from __future__ import annotations

import unittest

from run_stored_5k_retrieval import (
    DEFAULT_EXPECTED_COUNT,
    ROOT,
    default_package,
    require_expected_count,
    test_artifact,
    validate_variant_package,
)


class Stored5kRetrievalHarnessTest(unittest.TestCase):
    def test_consumer_test_artifact_is_non_uninstalling_android_test_apk(self) -> None:
        task, artifact = test_artifact("consumerDebug")
        self.assertEqual("ConsumerDebug", task)
        self.assertEqual(
            ROOT / "android/app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk",
            artifact,
        )

    def test_acceptance_requires_exact_target_scale(self) -> None:
        self.assertEqual(DEFAULT_EXPECTED_COUNT, require_expected_count(DEFAULT_EXPECTED_COUNT))
        self.assertEqual(20_000, require_expected_count(20_000))
        for value in (0, 4_999, 5_001, 19_999, 20_001):
            with self.subTest(value=value), self.assertRaisesRegex(RuntimeError, "5000 or 20000"):
                require_expected_count(value)

    def test_fixture_variant_uses_isolated_package_and_test_apk(self) -> None:
        task, artifact = test_artifact("fixtureCiDebug")
        self.assertEqual("FixtureCiDebug", task)
        self.assertEqual(
            ROOT / "android/app/build/outputs/apk/androidTest/fixtureCi/debug/app-fixtureCi-debug-androidTest.apk",
            artifact,
        )
        self.assertEqual("io.github.anup42.askalbum.fixture", default_package("fixtureCiDebug"))

    def test_variant_package_mismatch_is_rejected(self) -> None:
        self.assertEqual(
            "io.github.anup42.askalbum.fixture",
            validate_variant_package("fixtureCiDebug", "io.github.anup42.askalbum.fixture"),
        )
        with self.assertRaisesRegex(RuntimeError, "Refusing to instrument a different app UID"):
            validate_variant_package("fixtureCiDebug", "io.github.anup42.askalbum")

    def test_unknown_variant_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "Unsupported variant"):
            test_artifact("debug")


if __name__ == "__main__":
    unittest.main()
