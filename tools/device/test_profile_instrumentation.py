from __future__ import annotations

import unittest

from profile_instrumentation import parse_meminfo, parse_thermal_status


class ProfileInstrumentationTest(unittest.TestCase):
    def test_parses_current_android_meminfo_summary(self) -> None:
        text = "TOTAL PSS: 2,345   TOTAL RSS: 6,789   TOTAL SWAP PSS: 0"
        self.assertEqual((2345, 6789), parse_meminfo(text))

    def test_returns_none_when_target_process_is_absent(self) -> None:
        self.assertIsNone(parse_meminfo("No process found"))

    def test_parses_thermal_status(self) -> None:
        self.assertEqual(0, parse_thermal_status("Thermal Status: 0"))
        self.assertIsNone(parse_thermal_status("thermal service unavailable"))


if __name__ == "__main__":
    unittest.main()
