# SageSearch A57 demo rehearsal

Exact query: **gym membership around March**

| App | Version | Result |
|---|---:|---|
| Samsung My Files | 15.4.09.5 | No results found |
| SageSearch | 0.1.0-debug | Matched `IMG_20260312_184522.png`, showed receipt evidence, and opened the original |

The original is a synthetic fitness-center membership receipt dated 12 Maret
2026 with a total of Rp 200.000. Its camera-style filename does not contain
`gym`, `membership`, or `March`.

Evidence:

- `artifacts/task11-myfiles-search.png`
- `artifacts/task11-sagesearch-result.png`
- `artifacts/task11-opened-receipt.png`
- `results/task11/a57/demo-rehearsal.json` contains screenshot hashes and the
  exact device/app metadata.

This is a controlled one-device, one-fixture rehearsal. It demonstrates the
product difference without claiming that all Android file managers or arbitrary
private corpora behave the same way.
