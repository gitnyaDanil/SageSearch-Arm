import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("build_task11_report.py")
SPEC = importlib.util.spec_from_file_location("sagesearch_task11", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
TASK11 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TASK11)


class Task11ReportTest(unittest.TestCase):
    def test_selects_fastest_passing_configuration_and_writes_three_formats(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            cases = root / "cases.jsonl"
            cases.write_text(
                json.dumps(
                    {"id": "q1", "expected": {"version": 1, "contentKinds": ["receipt"]}}
                ) + "\n",
                encoding="utf-8",
            )
            result_root = root / "matrix"
            latencies = {
                "gpu-baseline-unconstrained": 120.0,
                "cpu-baseline-unconstrained": 90.0,
                "cpu-optimized-unconstrained": 80.0,
                "cpu-optimized-constrained-hybrid": 70.0,
            }
            for slug, latency in latencies.items():
                directory = result_root / slug
                directory.mkdir(parents=True)
                (directory / "outputs.jsonl").write_text(
                    json.dumps(
                        {
                            "id": "q1",
                            "output": {"version": 1, "contentKinds": ["receipt"]},
                            "latency_ms": latency,
                        }
                    ) + "\n",
                    encoding="utf-8",
                )
                (directory / "metadata.json").write_text(
                    json.dumps({"backend": "GPU" if slug.startswith("gpu") else "CPU"}),
                    encoding="utf-8",
                )
            retrieval = root / "retrieval.json"
            retrieval.write_text(
                json.dumps(
                    {
                        "document_count": 10000,
                        "latency_ms_p50": 10.0,
                        "latency_ms_p95": 15.0,
                        "retrieval_top_1_rate": 1.0,
                        "retrieval_top_3_rate": 1.0,
                    }
                ),
                encoding="utf-8",
            )

            report = TASK11.build_report(cases, result_root, retrieval)
            output = root / "output"
            TASK11.write_outputs(report, output)

            self.assertEqual("cpu-optimized-constrained-hybrid", report["winning_configuration"])
            self.assertAlmostEqual(41.6666667, report["median_latency_improvement_vs_gpu_percent"])
            self.assertTrue(report["latency_target_pass"])
            self.assertTrue((output / "comparison.json").is_file())
            self.assertTrue((output / "comparison.csv").is_file())
            self.assertTrue((output / "comparison.md").is_file())

    def test_all_generation_errors_are_not_reported_as_successful_latency(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            cases = root / "cases.jsonl"
            cases.write_text(
                json.dumps({"id": "q1", "expected": {"version": 1}}) + "\n",
                encoding="utf-8",
            )
            directory = root / "matrix" / "gpu-baseline-unconstrained"
            directory.mkdir(parents=True)
            (directory / "outputs.jsonl").write_text(
                json.dumps(
                    {
                        "id": "q1",
                        "latency_ms": 123.0,
                        "error_type": "LiteRtLmJniException",
                    }
                ) + "\n",
                encoding="utf-8",
            )
            (directory / "metadata.json").write_text(
                json.dumps({"backend": "GPU"}), encoding="utf-8"
            )

            summary = TASK11.configuration_summary(
                TASK11.EVALUATE.read_jsonl(cases),
                directory,
                "gpu-baseline-unconstrained",
            )

            self.assertIsNone(summary["latency_ms_median"])
            self.assertEqual(123.0, summary["failure_return_latency_ms_median"])
            self.assertEqual(1, summary["error_count"])
            self.assertEqual(0, summary["usable_output_count"])
            self.assertEqual("LiteRtLmJniException (1/1)", summary["failure_type"])


if __name__ == "__main__":
    unittest.main()
