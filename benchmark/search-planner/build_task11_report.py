#!/usr/bin/env python3
"""Build the privacy-safe SageSearch Task 11 JSON/CSV/Markdown comparison."""

from __future__ import annotations

import argparse
from collections import Counter
import csv
import importlib.util
import json
from pathlib import Path
from typing import Any

EVALUATE_PATH = Path(__file__).with_name("evaluate.py")
EVALUATE_SPEC = importlib.util.spec_from_file_location("sagesearch_evaluate", EVALUATE_PATH)
assert EVALUATE_SPEC is not None and EVALUATE_SPEC.loader is not None
EVALUATE = importlib.util.module_from_spec(EVALUATE_SPEC)
EVALUATE_SPEC.loader.exec_module(EVALUATE)

CONFIGURATIONS = (
    "gpu-baseline-unconstrained",
    "cpu-baseline-unconstrained",
    "cpu-optimized-unconstrained",
    "cpu-optimized-constrained-hybrid",
)


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def configuration_summary(
    cases: list[dict[str, Any]], configuration_dir: Path, slug: str
) -> dict[str, Any]:
    outputs_path = configuration_dir / "outputs.jsonl"
    metadata_path = configuration_dir / "metadata.json"
    metadata = load_json(metadata_path) if metadata_path.exists() else {}
    records = EVALUATE.read_jsonl(outputs_path) if outputs_path.exists() else []
    report = EVALUATE.evaluate_records(cases, records)
    (configuration_dir / "report.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    latency = report.get("performance", {}).get("latency_ms", {})
    pss = report.get("performance", {}).get("peak_pss_mb", {})
    error_types = Counter(
        str(record["error_type"])
        for record in records
        if record.get("error_type")
    )
    error_count = sum(error_types.values())
    usable_output_count = sum(
        1 for record in records if record.get("output") is not None
    )
    failure_type = metadata.get("failure_type")
    if not failure_type and records and error_count == len(records):
        failure_type = ", ".join(
            f"{name} ({count}/{len(records)})"
            for name, count in sorted(error_types.items())
        )
    quality_pass = (
        not failure_type
        and error_count == 0
        and report["output_count"] == report["case_count"]
        and report["schema_valid_rate"] == 1.0
        and report["plan_slot_f1"] >= 0.95
    )
    return {
        "configuration": slug,
        "backend": metadata.get("backend"),
        "prompt": metadata.get("prompt"),
        "response_constraint": metadata.get("response_constraint"),
        "deterministic_reconciliation": metadata.get("deterministic_reconciliation"),
        "failure_type": failure_type,
        "error_count": error_count,
        "usable_output_count": usable_output_count,
        "engine_initialization_ms": metadata.get("engine_initialization_ms"),
        "strict_json_rate": report["strict_json_rate"],
        "schema_valid_rate": report["schema_valid_rate"],
        "exact_plan_rate": report["exact_plan_rate"],
        "plan_slot_f1": report["plan_slot_f1"],
        "latency_ms_median": latency.get("median") if usable_output_count else None,
        "latency_ms_p95": latency.get("p95") if usable_output_count else None,
        "failure_return_latency_ms_median": (
            latency.get("median") if error_count == len(records) and records else None
        ),
        "peak_pss_mb_median": pss.get("median"),
        "quality_pass": quality_pass,
    }


def build_report(cases_path: Path, result_root: Path, retrieval_path: Path) -> dict[str, Any]:
    cases = EVALUATE.read_jsonl(cases_path)
    configurations = [
        configuration_summary(cases, result_root / slug, slug) for slug in CONFIGURATIONS
    ]
    passing = [
        item for item in configurations
        if item["quality_pass"] and item["latency_ms_median"] is not None
    ]
    winner = min(passing, key=lambda item: item["latency_ms_median"]) if passing else None
    gpu = next(item for item in configurations if item["configuration"].startswith("gpu-"))
    improvement = None
    if winner and gpu["quality_pass"] and gpu["latency_ms_median"]:
        improvement = (
            (gpu["latency_ms_median"] - winner["latency_ms_median"])
            / gpu["latency_ms_median"]
            * 100.0
        )
    retrieval = load_json(retrieval_path)
    return {
        "evidence_kind": "SageSearch Arm Task 11 benchmark matrix",
        "case_count": len(cases),
        "configurations": configurations,
        "winning_configuration": winner["configuration"] if winner else None,
        "median_latency_improvement_vs_gpu_percent": improvement,
        "latency_target_pass": improvement is not None and improvement >= 30.0,
        "planner_quality_gate": {
            "schema_valid_rate": 1.0,
            "minimum_plan_slot_f1": 0.95,
        },
        "preliminary_retrieval": retrieval,
        "limitations": [
            "Planner cases, documents, and retrieval queries are synthetic smoke fixtures.",
            "The synchronous LiteRT-LM API did not expose TTFT, prefill, decode, or energy metrics.",
            "No NPU, SME2, i8mm, KleidiAI, battery-life, or energy-efficiency claim is made.",
        ],
    }


def write_outputs(report: dict[str, Any], output_dir: Path) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "comparison.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    columns = [
        "configuration", "backend", "prompt", "response_constraint",
        "deterministic_reconciliation", "failure_type", "error_count",
        "usable_output_count", "engine_initialization_ms",
        "strict_json_rate", "schema_valid_rate", "exact_plan_rate", "plan_slot_f1",
        "latency_ms_median", "latency_ms_p95", "failure_return_latency_ms_median",
        "peak_pss_mb_median", "quality_pass",
    ]
    with (output_dir / "comparison.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns)
        writer.writeheader()
        writer.writerows({key: row.get(key) for key in columns} for row in report["configurations"])

    rows = []
    for item in report["configurations"]:
        latency = item["latency_ms_median"]
        p95 = item["latency_ms_p95"]
        rows.append(
            "| {configuration} | {backend} | {schema:.1%} | {f1:.3f} | {latency} | {p95} | {passed} |".format(
                configuration=item["configuration"],
                backend=item["backend"] or "unavailable",
                schema=item["schema_valid_rate"],
                f1=item["plan_slot_f1"],
                latency=f"{latency:.1f}" if latency is not None else "unavailable",
                p95=f"{p95:.1f}" if p95 is not None else "unavailable",
                passed="yes" if item["quality_pass"] else "no",
            )
        )
    retrieval = report["preliminary_retrieval"]
    improvement = report["median_latency_improvement_vs_gpu_percent"]
    markdown = "\n".join(
        [
            "# SageSearch Arm benchmark",
            "",
            "| Configuration | Backend | Schema valid | Plan F1 | Median ms | p95 ms | Quality gate |",
            "|---|---:|---:|---:|---:|---:|---:|",
            *rows,
            "",
            f"Winning passing configuration: **{report['winning_configuration'] or 'none'}**.",
            (
                f"Median planner latency improvement versus GPU baseline: **{improvement:.1f}%**."
                if improvement is not None
                else "Median planner latency improvement versus GPU baseline: **not measurable**."
            ),
            "The GPU row has no generation latency because all calls failed; its recorded error-return latency is preserved in JSON/CSV.",
            "",
            "## Preliminary retrieval",
            "",
            f"- Documents: {retrieval['document_count']}",
            f"- p50: {retrieval['latency_ms_p50']:.3f} ms",
            f"- p95: {retrieval['latency_ms_p95']:.3f} ms",
            f"- Top-1: {retrieval['retrieval_top_1_rate']:.1%}",
            f"- Top-3: {retrieval['retrieval_top_3_rate']:.1%}",
            "",
            "## Claim boundary",
            "",
            *[f"- {item}" for item in report["limitations"]],
            "",
        ]
    )
    (output_dir / "comparison.md").write_text(markdown, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", required=True, type=Path)
    parser.add_argument("--result-root", required=True, type=Path)
    parser.add_argument("--retrieval", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    report = build_report(args.cases, args.result_root, args.retrieval)
    write_outputs(report, args.output_dir)
    print(json.dumps({key: value for key, value in report.items() if key != "configurations"}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
