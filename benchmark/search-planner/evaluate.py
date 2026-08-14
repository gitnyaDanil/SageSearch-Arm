#!/usr/bin/env python3
"""Evaluate SageSearch SearchPlan model outputs without third-party packages."""

from __future__ import annotations

import argparse
import json
import math
import statistics
from datetime import date, datetime
from pathlib import Path
from typing import Any, Iterable

ALLOWED_FIELDS = {
    "version",
    "textTerms",
    "contentKinds",
    "merchant",
    "amountRangeMinor",
    "currencyCode",
    "transactionDateRange",
    "mediaDateRange",
    "labels",
    "faceFilter",
    "albumHint",
}
CONTENT_KINDS = {"receipt", "picture", "mixed", "unknown"}
FACE_FILTERS = {"none", "any", "exactly_one", "multiple"}
PERFORMANCE_FIELDS = (
    "latency_ms",
    "ttft_ms",
    "peak_pss_mb",
    "prefill_tokens_per_second",
    "decode_tokens_per_second",
)


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            text = line.strip()
            if not text:
                continue
            value = json.loads(text)
            if not isinstance(value, dict):
                raise ValueError(f"{path}:{line_number}: expected a JSON object")
            records.append(value)
    return records


def parse_model_output(value: Any) -> tuple[bool, bool, dict[str, Any] | None, str | None]:
    if isinstance(value, dict):
        return True, True, value, None
    if not isinstance(value, str):
        return False, False, None, "output must be a JSON object or string"

    text = value.strip()
    try:
        parsed = json.loads(text)
        if isinstance(parsed, dict):
            return True, True, parsed, None
        return False, False, None, "strict JSON value is not an object"
    except json.JSONDecodeError as strict_error:
        object_start = text.find("{")
        if object_start < 0:
            return False, False, None, str(strict_error)
        try:
            parsed, _ = json.JSONDecoder().raw_decode(text[object_start:])
        except json.JSONDecodeError as recovery_error:
            return False, False, None, str(recovery_error)
        if not isinstance(parsed, dict):
            return False, False, None, "recoverable JSON value is not an object"
        return False, True, parsed, None


def _validate_string_list(
    plan: dict[str, Any], field: str, errors: list[str], allowed: set[str] | None = None
) -> None:
    if field not in plan:
        return
    value = plan[field]
    if not isinstance(value, list):
        errors.append(f"{field} must be an array")
        return
    if len(value) > 12:
        errors.append(f"{field} has more than 12 items")
    if any(not isinstance(item, str) or not item.strip() for item in value):
        errors.append(f"{field} must contain non-empty strings")
        return
    normalized = [item.strip().casefold() for item in value]
    if len(normalized) != len(set(normalized)):
        errors.append(f"{field} must not contain duplicates")
    if allowed is not None:
        invalid = sorted(set(value) - allowed)
        if invalid:
            errors.append(f"{field} contains unsupported values: {invalid}")


def _validate_range(
    plan: dict[str, Any], field: str, errors: list[str], value_type: type
) -> None:
    if field not in plan:
        return
    value = plan[field]
    if not isinstance(value, dict) or not value:
        errors.append(f"{field} must be a non-empty object")
        return
    unknown = set(value) - {"min", "max"}
    if unknown:
        errors.append(f"{field} contains unknown keys: {sorted(unknown)}")
    for bound in ("min", "max"):
        if bound in value and (not isinstance(value[bound], value_type) or isinstance(value[bound], bool)):
            errors.append(f"{field}.{bound} must be {value_type.__name__}")
    if isinstance(value.get("min"), value_type) and isinstance(value.get("max"), value_type):
        if value["min"] > value["max"]:
            errors.append(f"{field}.min must not exceed max")


def _validate_temporal_range(
    plan: dict[str, Any], field: str, errors: list[str], instant: bool
) -> None:
    if field not in plan:
        return
    value = plan[field]
    if not isinstance(value, dict) or not value:
        errors.append(f"{field} must be a non-empty object")
        return
    unknown = set(value) - {"start", "end"}
    if unknown:
        errors.append(f"{field} contains unknown keys: {sorted(unknown)}")
    parsed: dict[str, date | datetime] = {}
    for bound in ("start", "end"):
        if bound not in value:
            continue
        raw = value[bound]
        if not isinstance(raw, str):
            errors.append(f"{field}.{bound} must be a string")
            continue
        try:
            parsed[bound] = (
                datetime.fromisoformat(raw.replace("Z", "+00:00"))
                if instant
                else date.fromisoformat(raw)
            )
        except ValueError:
            errors.append(f"{field}.{bound} has invalid ISO format")
    if "start" in parsed and "end" in parsed and parsed["start"] > parsed["end"]:
        errors.append(f"{field}.start must not exceed end")


def validate_plan(plan: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    unknown = set(plan) - ALLOWED_FIELDS
    if unknown:
        errors.append(f"unknown fields: {sorted(unknown)}")
    if plan.get("version") != 1 or isinstance(plan.get("version"), bool):
        errors.append("version must equal integer 1")

    _validate_string_list(plan, "textTerms", errors)
    _validate_string_list(plan, "contentKinds", errors, CONTENT_KINDS)
    _validate_string_list(plan, "labels", errors)

    for field in ("merchant", "albumHint"):
        if field in plan and (not isinstance(plan[field], str) or not plan[field].strip()):
            errors.append(f"{field} must be a non-empty string")
    if "currencyCode" in plan:
        currency = plan["currencyCode"]
        if not isinstance(currency, str) or len(currency) != 3 or not currency.isalpha() or currency != currency.upper():
            errors.append("currencyCode must be three uppercase letters")
    if "faceFilter" in plan and plan["faceFilter"] not in FACE_FILTERS:
        errors.append("faceFilter is unsupported")

    _validate_range(plan, "amountRangeMinor", errors, int)
    _validate_temporal_range(plan, "transactionDateRange", errors, instant=False)
    _validate_temporal_range(plan, "mediaDateRange", errors, instant=True)
    return errors


def _normalize_string(value: str) -> str:
    return " ".join(value.split()).casefold()


def normalize_plan(plan: dict[str, Any]) -> dict[str, Any]:
    normalized: dict[str, Any] = {"version": plan.get("version")}
    for field in ("textTerms", "contentKinds", "labels"):
        values = plan.get(field, [])
        normalized[field] = sorted(_normalize_string(value) for value in values)
    for field in ("merchant", "albumHint"):
        value = plan.get(field)
        normalized[field] = _normalize_string(value) if isinstance(value, str) else None
    currency = plan.get("currencyCode")
    normalized["currencyCode"] = currency.upper() if isinstance(currency, str) else None
    normalized["faceFilter"] = plan.get("faceFilter")
    for field in ("amountRangeMinor", "transactionDateRange", "mediaDateRange"):
        value = plan.get(field)
        normalized[field] = dict(sorted(value.items())) if isinstance(value, dict) else None
    return normalized


def plan_tokens(plan: dict[str, Any]) -> set[str]:
    normalized = normalize_plan(plan)
    tokens: set[str] = set()
    for field in ("textTerms", "contentKinds", "labels"):
        tokens.update(f"{field}:{value}" for value in normalized[field])
    for field in ("merchant", "currencyCode", "faceFilter", "albumHint"):
        value = normalized[field]
        if value is not None:
            tokens.add(f"{field}:{value}")
    for field in ("amountRangeMinor", "transactionDateRange", "mediaDateRange"):
        value = normalized[field]
        if value:
            tokens.update(f"{field}.{bound}:{item}" for bound, item in value.items())
    return tokens


def percentile(values: list[float], proportion: float) -> float:
    ordered = sorted(values)
    if not ordered:
        raise ValueError("percentile needs at least one value")
    index = (len(ordered) - 1) * proportion
    lower = math.floor(index)
    upper = math.ceil(index)
    if lower == upper:
        return ordered[lower]
    weight = index - lower
    return ordered[lower] * (1 - weight) + ordered[upper] * weight


def evaluate_records(
    cases: Iterable[dict[str, Any]], outputs: Iterable[dict[str, Any]]
) -> dict[str, Any]:
    case_map = {case["id"]: case for case in cases}
    output_map = {output["id"]: output for output in outputs}
    details: list[dict[str, Any]] = []
    strict_count = recoverable_count = schema_count = exact_count = 0
    true_positive = false_positive = false_negative = 0

    for case_id, case in case_map.items():
        output_record = output_map.get(case_id)
        detail: dict[str, Any] = {"id": case_id}
        if output_record is None:
            detail.update({"status": "missing", "errors": ["missing output"]})
            expected_tokens = plan_tokens(case["expected"])
            false_negative += len(expected_tokens)
            details.append(detail)
            continue

        strict, recoverable, plan, parse_error = parse_model_output(output_record.get("output"))
        strict_count += int(strict)
        recoverable_count += int(recoverable)
        detail["strict_json"] = strict
        detail["recoverable_json"] = recoverable
        if plan is None:
            expected_tokens = plan_tokens(case["expected"])
            false_negative += len(expected_tokens)
            detail.update({"status": "parse_error", "errors": [parse_error or "parse error"]})
            details.append(detail)
            continue

        errors = validate_plan(plan)
        schema_valid = not errors
        schema_count += int(schema_valid)
        predicted_tokens = plan_tokens(plan) if schema_valid else set()
        expected_tokens = plan_tokens(case["expected"])
        true_positive += len(predicted_tokens & expected_tokens)
        false_positive += len(predicted_tokens - expected_tokens)
        false_negative += len(expected_tokens - predicted_tokens)
        exact = schema_valid and normalize_plan(plan) == normalize_plan(case["expected"])
        exact_count += int(exact)
        detail.update(
            {
                "status": "ok" if schema_valid else "schema_error",
                "schema_valid": schema_valid,
                "exact": exact,
                "errors": errors,
                "false_positive_tokens": sorted(predicted_tokens - expected_tokens),
                "false_negative_tokens": sorted(expected_tokens - predicted_tokens),
            }
        )
        details.append(detail)

    total = len(case_map)
    precision = true_positive / (true_positive + false_positive) if true_positive + false_positive else 0.0
    recall = true_positive / (true_positive + false_negative) if true_positive + false_negative else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0

    performance: dict[str, Any] = {}
    for field in PERFORMANCE_FIELDS:
        values = [
            float(record[field])
            for record in output_map.values()
            if isinstance(record.get(field), (int, float)) and not isinstance(record.get(field), bool)
        ]
        if values:
            performance[field] = {
                "count": len(values),
                "median": statistics.median(values),
                "p95": percentile(values, 0.95),
                "min": min(values),
                "max": max(values),
            }

    return {
        "case_count": total,
        "output_count": len(output_map),
        "strict_json_rate": strict_count / total if total else 0.0,
        "recoverable_json_rate": recoverable_count / total if total else 0.0,
        "schema_valid_rate": schema_count / total if total else 0.0,
        "exact_plan_rate": exact_count / total if total else 0.0,
        "plan_slot_precision": precision,
        "plan_slot_recall": recall,
        "plan_slot_f1": f1,
        "missing_ids": sorted(set(case_map) - set(output_map)),
        "unexpected_ids": sorted(set(output_map) - set(case_map)),
        "performance": performance,
        "details": details,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", required=True, type=Path)
    parser.add_argument("--outputs", required=True, type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    report = evaluate_records(read_jsonl(args.cases), read_jsonl(args.outputs))
    rendered = json.dumps(report, indent=2, ensure_ascii=False)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered + "\n", encoding="utf-8")

    summary = {key: value for key, value in report.items() if key not in {"details"}}
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
