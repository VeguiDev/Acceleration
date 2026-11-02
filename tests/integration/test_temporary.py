"""Keep integration fixtures portable outside the local OpenCode environment."""
import unittest
from unittest.mock import patch

from apdp_network import temporary_root


class TemporaryRootTest(unittest.TestCase):
    def test_runner_directory_takes_priority(self):
        with patch.dict("os.environ", {"RUNNER_TEMP": "/runner/temp"}):
            self.assertEqual(temporary_root(), "/runner/temp")

    def test_approved_local_directory(self):
        with patch.dict("os.environ", {}, clear=True), patch("pathlib.Path.is_dir", return_value=True):
            self.assertEqual(temporary_root(), "/tmp/opencode")

    def test_missing_local_directory_uses_stdlib_default(self):
        with patch.dict("os.environ", {}, clear=True), patch("pathlib.Path.is_dir", return_value=False):
            self.assertIsNone(temporary_root())


if __name__ == "__main__":
    unittest.main()
