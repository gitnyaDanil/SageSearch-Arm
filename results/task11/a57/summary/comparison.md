# SageSearch Arm benchmark

| Configuration | Backend | Schema valid | Plan F1 | Median ms | p95 ms | Quality gate |
|---|---:|---:|---:|---:|---:|---:|
| gpu-baseline-unconstrained | GPU | 0.0% | 0.000 | unavailable | unavailable | no |
| cpu-baseline-unconstrained | CPU | 0.0% | 0.000 | 8008.5 | 12809.7 | no |
| cpu-optimized-unconstrained | CPU | 100.0% | 0.636 | 4617.0 | 8463.4 | no |
| cpu-optimized-constrained-hybrid | CPU | 100.0% | 1.000 | 8766.0 | 11820.3 | yes |

Winning passing configuration: **cpu-optimized-constrained-hybrid**.
Median planner latency improvement versus GPU baseline: **not measurable**.
The GPU row has no generation latency because all calls failed; its recorded error-return latency is preserved in JSON/CSV.

## Preliminary retrieval

- Documents: 10000
- p50: 8.971 ms
- p95: 12.069 ms
- Top-1: 100.0%
- Top-3: 100.0%

## Claim boundary

- Planner cases, documents, and retrieval queries are synthetic smoke fixtures.
- The synchronous LiteRT-LM API did not expose TTFT, prefill, decode, or energy metrics.
- No NPU, SME2, i8mm, KleidiAI, battery-life, or energy-efficiency claim is made.
