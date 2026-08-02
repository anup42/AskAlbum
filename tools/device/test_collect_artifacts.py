from __future__ import annotations

import unittest

from collect_artifacts import package_logcat_args


class PrivacySafeArtifactCollectionTest(unittest.TestCase):
    def test_logcat_is_always_restricted_to_the_numeric_target_pid(self) -> None:
        args = package_logcat_args("10833")
        self.assertEqual(("--pid", "10833"), args[4:6])
        with self.assertRaises(RuntimeError):
            package_logcat_args("10833 10834")
        with self.assertRaises(RuntimeError):
            package_logcat_args("io.github.anup42.askalbum")


if __name__ == "__main__":
    unittest.main()
