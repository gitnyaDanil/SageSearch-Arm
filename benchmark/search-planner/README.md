# SageSearch query-planner benchmark

This benchmark answers one narrow question before Gemma is integrated into the
Android application:

> Can an on-device model translate natural-language image-search requests into
> the validated `SearchRequest` contract accurately enough, quickly enough, and
> within the memory limits of the target Arm phones?

The benchmark does not give the model access to images, OCR text, Room rows, or
file URIs. The model only produces a search plan. Trusted application code will
validate and execute the plan later.

## Contents

- `search-plan.schema.json`: versioned output contract.
- `cases.jsonl`: public, synthetic labeled queries; no private image data.
- `prompts/baseline.txt`: straightforward mobile baseline prompt.
- `prompts/optimized.txt`: compact task-constrained prompt.
- `evaluate.py`: dependency-free evaluator and performance summarizer.
- `test_evaluate.py`: evaluator regression tests.
- `build_task11_report.py`: combines the fixed A57 matrix and preliminary
  retrieval evidence into JSON, CSV, and Markdown without treating failed
  backend calls as successful generation latency.
- `test_build_task11_report.py`: Task 11 report regression tests.

## Feasibility sequence

1. Confirm Gemma 4 E2B loads on each target phone using Google AI Edge Gallery
   or LiteRT-LM.
2. Record the exact device, Android build, model checksum, runtime version,
   backend, thread count, and thermal state.
3. Run every case with the baseline prompt and save raw output.
4. Repeat with the optimized prompt using the same model, backend, sampler, and
   device state.
5. Evaluate both output files and compare quality and resource measurements.
6. Integrate the planner only after the feasibility gates pass.

Do not hide `OOM`, timeout, load failure, malformed JSON, or backend fallback.
Those are benchmark results.

## Model output capture

Create one JSON Lines record per case:

```json
{"id":"q001","output":"{\"version\":1,\"contentKinds\":[\"receipt\"],\"merchant\":\"Alfamart\"}","latency_ms":1840,"ttft_ms":620,"peak_pss_mb":1320,"prefill_tokens_per_second":85.2,"decode_tokens_per_second":18.4}
```

Only `id` and `output` are required. Performance fields are optional until the
device harness is connected. Preserve the raw output exactly, including code
fences or extra prose, so strict-JSON failures remain visible.

For the production hybrid pipeline, `output` is the final strictly validated
plan that application code may execute and `raw_output` preserves Gemma's exact
response beside it. Never replace or omit `raw_output` when deterministic
reconciliation changes a value. This makes model-only and end-to-end quality
independently auditable.

Suggested filenames:

```text
results/query-planner/<device>/<configuration>/outputs.jsonl
results/query-planner/<device>/<configuration>/report.json
results/query-planner/<device>/<configuration>/metadata.json
```

## Evaluate

From the repository root:

```powershell
python benchmark/search-planner/evaluate.py `
  --cases benchmark/search-planner/cases.jsonl `
  --outputs results/query-planner/a57/baseline/outputs.jsonl `
  --report results/query-planner/a57/baseline/report.json
```

Run evaluator tests with:

```powershell
python -m unittest benchmark/search-planner/test_evaluate.py
```

The report includes:

- strict JSON rate;
- recoverable JSON rate;
- schema-valid rate;
- exact-plan rate;
- micro precision, recall, and F1 over plan slots;
- missing and unexpected case IDs;
- median and p95 for any supplied performance fields.

## Project feasibility gates

These are SageSearch engineering gates, not official hackathon thresholds:

- every labeled case has an output or an explicit recorded failure;
- schema-valid rate is at least 99%;
- plan-slot F1 is at least 0.95 on the frozen set;
- no unknown field can reach application execution;
- the model loads repeatedly without an out-of-memory failure on a claimed
  supported device;
- the optimized configuration improves at least two measured resource metrics
  without reducing quality below the gates;
- the deterministic parser remains available when model loading or validation
  fails.

With the current 20-case smoke set, a 99% gate effectively means all cases must
be schema-valid. Expand the frozen set before making final quality claims.

The A57 evidence under `results/query-planner/a57/cpu-optimized-hybrid` is a
small synthetic smoke result, not a claim about arbitrary user queries. Its
metadata pins the model, runtime, prompt, schema, APK, device build, and both raw
and executed-plan evidence.

## Task 11 A57 matrix

The fixed 20-case matrix is under `results/task11/a57/matrix`; the combined
report is under `results/task11/a57/summary`. Regenerate the checked-in summary
from the repository root with:

```powershell
python benchmark/search-planner/build_task11_report.py `
  --cases benchmark/search-planner/cases.jsonl `
  --result-root results/task11/a57/matrix `
  --retrieval results/task11/a57/preliminary-search.json `
  --output-dir results/task11/a57/summary
```

On the Samsung SM-A576B, only the CPU optimized constrained-hybrid
configuration passed the planner gates: 20/20 schema-valid plans and plan-slot
F1 1.0. Its median end-to-end planner latency was 8.766 seconds and p95 was
11.820 seconds on this small synthetic set. The GPU baseline initialized but all
20 generation calls returned `LiteRtLmJniException`; therefore a valid GPU
speedup is not measurable and no GPU acceleration claim is made.

The separate bounded Room/FTS instrumented smoke run seeded 10,000 synthetic
documents and measured p50 8.971 ms and p95 12.069 ms over 25 recorded runs,
with the expected document at rank 1 for all three queries. This timing excludes
OCR, Gemma inference, and UI rendering.

The controlled user-journey comparison is recorded in
`results/task11/a57/demo-rehearsal.json`: Samsung My Files returned no result for
`gym membership around March`, while SageSearch matched the camera-named
membership receipt and opened it. That is one device and one synthetic fixture,
not a universal comparison with Android file managers.

## Arm measurements

Capture at minimum:

- model file size and SHA-256;
- cold model-load time;
- warm time to first token;
- prefill and decode tokens per second;
- end-to-end query-plan latency;
- peak PSS;
- CPU or GPU backend and any fallback;
- battery and thermal state before and after the fixed suite;
- device CPU features actually reported by the device.

Do not claim SME2, i8mm, KleidiAI, GPU, or NPU acceleration unless the runtime
and device evidence demonstrate that path.
