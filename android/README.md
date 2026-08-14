# SageSearch Android offline file search

The complete target architecture, data contracts, milestone gates, and PRD
traceability are defined in the
[Android Image Search technical specification](../docs/ANDROID_IMAGE_SEARCH_SPEC.md).
This README describes the currently runnable hackathon build.

SageSearch lets the user approve folders or individual files through Android's
system document picker. It persists those read grants, discovers files through
`content://` URIs, and runs bounded WorkManager indexing entirely on-device.
The current pipeline:

- keeps all discovered common files searchable by metadata;
- runs bundled ML Kit Latin OCR on supported images after bounding the longest
  decoded edge to 2048 pixels and applying available EXIF orientation;
- renders and OCRs PDF pages sequentially, capped at the first five pages;
- deterministically extracts receipt merchant, date, amount, and currency fields;
- commits extraction facts and Unicode61 FTS text together in Room;
- checkpoints, retries, and resumes source-scoped indexing without blocking search.

For this early validation, `picture` means that the receipt rules found
insufficient receipt evidence. Object labels, scene classification, and faces
are intentionally not claimed yet.

It does not upload files, declare Android's Internet permission, request broad
storage access, or guess filesystem paths. Gemma is not used to invent document
facts; it is prepared as a constrained query-planning engine while deterministic
search remains available without it.

## Run it

1. Install current Android Studio with Android SDK 36 and JDK 17.
2. Open the `android` directory as a project.
3. Allow Gradle sync to download the Android and ML Kit dependencies.
4. Run `app` on an Android 7.0 (API 24) or newer emulator/device.
5. Approve a folder or one or more individual files through Android's picker.
6. Optionally choose a compatible `.litertlm` model under **Prepare AI model**.
7. Tap **Search indexed files** and enter remembered filename or extracted text.

From a Windows terminal configured with Android Studio's JDK and SDK, the same
verification can be run with:

```powershell
cd android
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.
Install it on a connected test device with `adb install -r` if USB debugging is
already enabled and the device is authorized.

Pushing `codex/image-search` automatically starts the **Android prototype**
GitHub Actions workflow. It runs the unit tests and lint, builds `app-debug.apk`,
and publishes it as the `sagesearch-image-test-debug` workflow artifact for seven
days. Once the workflow file also exists on the default branch, it can be started
manually from GitHub's Actions tab.

The bundled OCR dependency increases the APK size but makes the first recognition
available immediately and keeps this prototype independent of a model download.

## On-device Gemma model

The APK does not bundle or redistribute Gemma. The user downloads a compatible
model separately and selects it with Android's document picker. SageSearch checks
private-storage headroom, copies through a removable `.partial` file, calculates
SHA-256, and shows **Ready** only after LiteRT-LM initialization succeeds. Model,
status, database, and runtime cache files are private and excluded from Android
backup; incomplete imports and superseded model/cache files are explicitly
cleaned up.

The reusable engine currently uses the LiteRT-LM 0.16.0 CPU backend. Each request
gets a new one-shot conversation with temperature 0, top-k 1, top-p 1, and a
160-token output limit. Heavy inference is serialized with OCR/index work. A
failed or cancelled model setup does not disable deterministic indexed search.

Validated A57 artifact (2026-08-14):

- Source repository: [`litert-community/gemma-4-E2B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
- Selected file: `gemma-4-E2B-it.litertlm` (the generic Android container)
- Local imported size: `2,588,147,712` bytes
- Local SHA-256: `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`
- Model card/license: [Gemma 4 model card — Apache 2.0](https://ai.google.dev/gemma/docs/core/model_card_4)
- Runtime compatibility: LiteRT-LM Android `0.16.0`, API 24+, CPU initialization
  verified on Samsung SM-A576B / Android API 36

The source repository can change independently; compare the locally calculated
hash rather than assuming a mutable download URL still serves the validated
artifact. The project publishes neither the model file nor redistribution rights.

## Debug-only Gemma evidence tools

The normal debug app connects the prepared engine to the validated query planner
and retains deterministic search when inference is unavailable. It only lets a
strict, allowlisted `SearchRequest` reach Room; Gemma never writes SQL or indexed
facts directly.

The debug build also contains an isolated LiteRT-LM initialization check. Launch
it on an authorized device with:

```powershell
adb shell am start -a com.sagesearch.android.LITERT_SMOKE -p com.sagesearch.android
```

Choose a compatible `.litertlm` file through Android's document picker. The
screen copies the model to temporary app-private cache, computes SHA-256,
initializes one CPU engine off the main thread, closes it, and writes a
privacy-safe `litert-smoke-report.json` in app-private files. The report records
only runtime/backend, model size/hash, timing, and device/API identity; it does
not record the picker URI, original path, filename, or model content.

## Current search journey

The search screen supports one low-friction refinement thread: enter a remembered
detail, inspect evidence-backed matches, add another detail to strengthen the
same search, or tap **New search** to clear the thread. Results show a thumbnail,
filename, matched fields, and an outlined **Open file** action. All planning,
validation, indexed retrieval, and file opening remain on the phone.

## Build baseline

- Android Gradle Plugin 9.3.0
- Kotlin / Compose compiler plugin 2.3.21
- Compose BOM 2026.06.00
- ML Kit bundled text recognition 16.0.1
- Room 2.8.4 with KSP 2.3.9
- WorkManager 2.11.2
- LiteRT-LM Android 0.16.0
- Minimum API 24; compile/target API 36
