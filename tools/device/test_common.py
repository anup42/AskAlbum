from __future__ import annotations

import unittest

from common import retry_transient


class RetryTransientTest(unittest.TestCase):
    def test_returns_after_bounded_transient_failures(self) -> None:
        calls = 0
        delays: list[float] = []

        def operation() -> str:
            nonlocal calls
            calls += 1
            if calls < 3:
                raise RuntimeError("adb exit 255")
            return "ok"

        value, retries = retry_transient(operation, attempts=4, base_delay_seconds=0.1, sleep=delays.append)
        self.assertEqual("ok", value)
        self.assertEqual(2, retries)
        self.assertEqual([0.1, 0.2], delays)

    def test_raises_after_exact_attempt_limit(self) -> None:
        calls = 0

        def operation() -> None:
            nonlocal calls
            calls += 1
            raise RuntimeError("still offline")

        with self.assertRaisesRegex(RuntimeError, "after 3 attempts"):
            retry_transient(operation, attempts=3, base_delay_seconds=0, sleep=lambda _: None)
        self.assertEqual(3, calls)


if __name__ == "__main__":
    unittest.main()
