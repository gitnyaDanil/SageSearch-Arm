import importlib.util
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("evaluate.py")
SPEC = importlib.util.spec_from_file_location("sagesearch_evaluate", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
EVALUATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVALUATE)


class ParseOutputTest(unittest.TestCase):
    def test_strict_object_is_strict_and_recoverable(self):
        strict, recoverable, plan, error = EVALUATE.parse_model_output(
            '{"version":1,"contentKinds":["receipt"]}'
        )
        self.assertTrue(strict)
        self.assertTrue(recoverable)
        self.assertEqual(1, plan["version"])
        self.assertIsNone(error)

    def test_fenced_object_is_recoverable_but_not_strict(self):
        strict, recoverable, plan, error = EVALUATE.parse_model_output(
            '```json\n{"version":1}\n```'
        )
        self.assertFalse(strict)
        self.assertTrue(recoverable)
        self.assertEqual({"version": 1}, plan)
        self.assertIsNone(error)


class SchemaTest(unittest.TestCase):
    def test_unknown_field_is_rejected(self):
        errors = EVALUATE.validate_plan({"version": 1, "filePath": "/private/file"})
        self.assertTrue(any("unknown fields" in error for error in errors))

    def test_invalid_range_is_rejected(self):
        errors = EVALUATE.validate_plan(
            {"version": 1, "amountRangeMinor": {"min": 200, "max": 100}}
        )
        self.assertTrue(any("must not exceed" in error for error in errors))


class EvaluationTest(unittest.TestCase):
    def test_exact_plan_scores_one(self):
        plan = {
            "version": 1,
            "contentKinds": ["receipt"],
            "merchant": "Alfamart",
        }
        report = EVALUATE.evaluate_records(
            [{"id": "q1", "expected": plan}],
            [{"id": "q1", "output": plan, "latency_ms": 100}],
        )
        self.assertEqual(1.0, report["schema_valid_rate"])
        self.assertEqual(1.0, report["exact_plan_rate"])
        self.assertEqual(1.0, report["plan_slot_f1"])
        self.assertEqual(100.0, report["performance"]["latency_ms"]["median"])

    def test_extra_slot_reduces_precision(self):
        expected = {"version": 1, "contentKinds": ["receipt"]}
        predicted = {
            "version": 1,
            "contentKinds": ["receipt"],
            "merchant": "Invented Merchant",
        }
        report = EVALUATE.evaluate_records(
            [{"id": "q1", "expected": expected}],
            [{"id": "q1", "output": predicted}],
        )
        self.assertEqual(0.5, report["plan_slot_precision"])
        self.assertEqual(1.0, report["plan_slot_recall"])
        self.assertLess(report["plan_slot_f1"], 1.0)

    def test_missing_output_reduces_recall(self):
        report = EVALUATE.evaluate_records(
            [{"id": "q1", "expected": {"version": 1, "contentKinds": ["receipt"]}}],
            [],
        )
        self.assertEqual(["q1"], report["missing_ids"])
        self.assertEqual(0.0, report["plan_slot_recall"])


if __name__ == "__main__":
    unittest.main()
