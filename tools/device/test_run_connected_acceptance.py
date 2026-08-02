from __future__ import annotations

import unittest

from run_connected_acceptance import GRADLE_ROOT, ROOT, variant_artifacts


class VariantArtifactsTest(unittest.TestCase):
    def test_gradle_root_contains_the_android_settings_file(self) -> None:
        self.assertTrue((GRADLE_ROOT / "settings.gradle.kts").is_file())

    def test_consumer_debug_uses_flavoured_non_uninstalling_outputs(self) -> None:
        task, app, test = variant_artifacts("consumerDebug")
        self.assertEqual("ConsumerDebug", task)
        self.assertEqual(ROOT / "android/app/build/outputs/apk/consumer/debug/app-consumer-debug.apk", app)
        self.assertEqual(
            ROOT / "android/app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk",
            test,
        )

    def test_offline_demo_debug_uses_flavoured_outputs(self) -> None:
        task, app, test = variant_artifacts("offlineDemoDebug")
        self.assertEqual("OfflineDemoDebug", task)
        self.assertIn("offlineDemo/debug", app.as_posix())
        self.assertIn("androidTest/offlineDemo/debug", test.as_posix())

    def test_unknown_variant_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "Unsupported variant"):
            variant_artifacts("debug")


if __name__ == "__main__":
    unittest.main()
