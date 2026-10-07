import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check


class PreflightCheck(unittest.TestCase):
    def test_supported_and_rejected_trees_never_mutate_inputs(self):
        check.main()


if __name__ == "__main__":
    unittest.main()
