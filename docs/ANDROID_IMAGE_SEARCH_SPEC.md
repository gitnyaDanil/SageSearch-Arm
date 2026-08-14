# SageSearch Android Image Search - Technical Implementation Specification

| Field | Value |
|---|---|
| Status | Proposed implementation baseline |
| Last updated | 11 August 2026 |
| Product requirements | [`IMAGE_SEARCH_PRD.md`](IMAGE_SEARCH_PRD.md) |
| Current implementation | `android/` on `codex/image-search` |
| Current prototype commit | `c77a881` |
| Initial release mode | Fully local, phone-local media |
| Minimum Android version | Android 6.0 / API 23 |
| Compile and target SDK | API 36 |

## 1. Purpose and authority

This document is the complete technical implementation guide for the SageSearch
Android image-search application. It translates the product requirements into
Android architecture, data contracts, storage rules, background processing,
search behavior, AI-provider boundaries, tests, and release gates.

The documents have separate authority:

1. [`IMAGE_SEARCH_PRD.md`](IMAGE_SEARCH_PRD.md) defines what the product must do
   and why.
2. This specification defines how Android implements those requirements.
3. [`../android/README.md`](../android/README.md) remains the concise developer
   setup and build guide.
4. [`IMAGE_SEARCH_TEST_CHECKLIST.md`](IMAGE_SEARCH_TEST_CHECKLIST.md) remains the
   manual feedback checklist.

If this specification conflicts with the PRD on product behavior, privacy, or
scope, the PRD wins and this specification must be amended. If the running code
differs from this specification, the difference must be identified as either a
prototype limitation or an approved architecture decision record (ADR).

## 2. Product outcome

The Android application must privately index user-approved images and reduce a
collection of thousands of images to no more than 40 understandable candidates.
Users must be able to search using:

- visible text;
- receipt merchant, transaction date, total, currency, receipt number, item
  keywords, and payment method when supported by evidence;
- common objects and scenes after the visual-search milestone;
- face presence and approximate count, without recognizing identity;
- Android media metadata such as date and album/folder when available.

The core receipt workflow must work offline after required models are installed.
No image, OCR text, embedding, URI, result, or receipt field may be uploaded in
the baseline release.

## 3. Scope and non-goals

### 3.1 In scope

- Permissionless analysis of images chosen through Android Photo Picker.
- Explicitly authorized indexing of selected or all device images.
- On-device OCR and receipt analysis.
- A durable, resumable, observable background indexing pipeline.
- Local structured, full-text, and later vector retrieval.
- Result ranking capped at 40 with evidence-based explanations.
- Result thumbnails and opening the original image through its `content://` URI.
- Safe removal, reanalysis, migration, and index reset.
- Android phone and tablet layouts using Jetpack Compose.

### 3.2 Explicit non-goals for the first Android release

- Named-person or biometric identity recognition.
- Inferring age, ethnicity, health, emotion, or other sensitive traits.
- Editing, moving, deleting, or reorganizing original images.
- Automatic cloud upload or a hidden network dependency.
- Cross-device synchronization with Windows.
- Video-content indexing.
- Accounting-grade receipt extraction.
- Unrestricted filesystem access or indexing outside Android-granted media.

## 4. Current implementation snapshot

The prototype is a valid vertical slice, not the target architecture.

| Area | Implemented now | Required evolution |
|---|---|---|
| UI | One Compose activity and one vertically scrolling screen | Navigation, screen ViewModels, immutable UI state, result grid, detail and settings screens |
| Selection | Single-image Photo Picker | Multi-select and opt-in MediaStore library modes |
| OCR | Bundled ML Kit Latin text recognition | Bounded decoding, worker integration, evidence and model versioning |
| Classification | Receipt, mixed, picture, or unknown heuristics | Calibrated fixtures, confidence evidence, versioned rules |
| Receipt fields | Merchant, date, total, currency | Receipt number, item keywords, payment method, normalized values and evidence |
| Persistence | One Room `indexed_images` table | Normalized schema, FTS4, jobs, migrations, deletion and model invalidation |
| Search | Case-insensitive SQL `LIKE`, maximum 40 | FTS candidates, structured filters, hybrid ranking and explanations |
| Background work | None | Unique WorkManager reconciliation and bounded analysis batches |
| Visual AI | None | Labels, face presence and a benchmarked multimodal embedding provider |
| Tests | Five JVM heuristic/interpreter tests | DAO, migration, worker, repository, UI, permission and performance tests |
| CI | Unit tests, lint and debug APK | Instrumented tests, schema checks, release build and benchmark jobs |

Prototype classes may be refactored or replaced. Existing behavior must remain
covered by regression tests during the migration.

## 5. Fixed architecture decisions

The following decisions are the default unless replaced by an ADR.

1. **Native Android:** Kotlin, coroutines, Jetpack Compose, and AndroidX.
2. **Single activity:** Compose destinations hosted by one activity.
3. **Unidirectional data flow:** screen ViewModels expose immutable `StateFlow`
   UI state and accept user actions through methods.
4. **Layered code:** UI, domain contracts/use cases, and data/provider layers.
5. **Room is the local source of truth:** WorkManager and UI observe database
   state rather than maintaining competing job or result stores.
6. **Room FTS4 for text retrieval:** the current `LIKE` query is temporary.
   Room officially supports FTS3/FTS4; the Android design must not assume that
   SQLite FTS5 is available consistently on every supported device.
7. **WorkManager for persistent work:** indexing is divided into restartable
   batches and uses unique work names to prevent duplicate pipelines.
8. **Local-only baseline:** no `INTERNET` permission is required for core
   indexing and search.
9. **Photo Picker first:** selecting specific images remains the default and
   does not require broad media permission.
10. **Full-library access is explicit:** MediaStore permission is requested only
    after the user chooses library indexing and sees the privacy explanation.
11. **No face identity:** only presence, count, and detector confidence are
    stored.
12. **Version every derived signal:** OCR, receipt rules, labels, faces, and
    embeddings have independent version identifiers.
13. **One Gradle app module initially:** organize by packages first. Split into
    modules only when build time, ownership, or reusable-model boundaries justify
    the cost.
14. **Constructor injection:** adopt Hilt when the production repository and
    WorkManager layers are introduced; UI code must not construct databases or
    analyzers directly.

These decisions follow Android's current recommendations for Compose,
single-activity navigation, ViewModels, repositories, flows, and unidirectional
data flow: [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations).

## 6. Supported Android versions and access modes

### 6.1 Platform baseline

- `minSdk = 23`
- `compileSdk = 36`
- `targetSdk = 36`
- Java/Kotlin bytecode target 17
- Core-library desugaring enabled for `java.time` use on API 23-25
- Portrait and landscape phone support; adaptive tablet UI is required before
  production release.

The current bundled ML Kit Text Recognition v2 dependency also requires API 23,
so lowering `minSdk` is not planned.

### 6.2 Dependency baseline and version policy

The repository currently builds with:

| Component | Locked version |
|---|---|
| Android Gradle Plugin | 9.3.0 |
| Kotlin and Compose compiler plugin | 2.3.21 |
| Compose BOM | 2026.06.00 |
| Activity Compose | 1.13.0 |
| Room runtime/compiler | 2.8.4 |
| KSP | 2.3.9 |
| Bundled ML Kit Latin text recognition | 16.0.1 |

Milestone B introduces a Gradle version catalog and locks compatible stable
versions of Lifecycle/ViewModel Compose, lifecycle-aware state collection,
Navigation 3, WorkManager, Hilt, DataStore Preferences, ExifInterface and the
chosen thumbnail loader. Lock a compatible `desugar_jdk_libs` release and enable
`isCoreLibraryDesugaringEnabled` so domain code can use `java.time` on every
supported API. Later milestones add bundled ML Kit image labeling and face
detection plus the approved embedding runtime/model. Do not use dynamic versions
such as `latest.release`.

Dependency upgrades require unit tests, lint, debug assembly and Room migration
tests. ML/model upgrades also require the relevant evaluation fixtures and a
derived-signal version bump. Commit model licenses and model cards with any
bundled model.

### 6.3 Media access modes

The app presents two separate choices:

#### Selected images - default

- Use `PickMultipleVisualMedia` where available and fall back through the
  AndroidX Activity Result contract.
- Request no broad storage permission.
- Call `takePersistableUriPermission()` for each URI that will be analyzed or
  opened after the current process/device session.
- Treat a failed or revoked URI grant as `UNAVAILABLE_PERMISSION`, not as a
  corrupt image.
- The platform permits at most 5,000 persisted Photo Picker media grants; users
  who want a larger index must use library mode.

See [Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker).

#### Library index - explicit opt-in

- API 33 and newer: request `READ_MEDIA_IMAGES`.
- API 34 and newer: declare and handle
  `READ_MEDIA_VISUAL_USER_SELECTED`; the user may grant only selected-photo
  access even when the app requests the image library.
- API 23-32: request `READ_EXTERNAL_STORAGE`, with the manifest permission
  capped at `maxSdkVersion="32"`.
- Do not request `WRITE_EXTERNAL_STORAGE` or `MANAGE_EXTERNAL_STORAGE`.
- Do not request `ACCESS_MEDIA_LOCATION`; geographic EXIF search is not in the
  planned scope.
- Re-evaluate permission state whenever the app resumes. Android settings may
  change full access to partial or denied access while the app is stopped.
- The UI must say whether the active scope is selected images, partial library,
  full image library, or no access.

Android 14 partial access behavior is defined by
[Selected Photos Access](https://developer.android.com/about/versions/14/changes/partial-photo-video-access).
Broad image access must also remain compatible with Google Play's restricted
media-permission policy.

## 7. Target architecture

```text
Compose UI
  -> screen ViewModels (StateFlow UI state, user actions)
     -> domain use cases
        -> ImageIndexRepository / SearchRepository / SettingsRepository
           -> Room DAOs and transactions
           -> MediaStoreDataSource / PersistedUriDataSource
           -> WorkManager coordinator
           -> AnalysisPipeline
              -> ImageDecoder
              -> OcrProvider
              -> ReceiptAnalyzer
              -> ImageLabelProvider (milestone 5)
              -> FacePresenceProvider (milestone 6)
              -> MultimodalEmbeddingProvider (milestone 5)

WorkManager workers
  -> repositories and AnalysisPipeline
  -> transactionally update Room
  -> UI observes Room flows and progress

Search request
  -> QueryParser
  -> structured + FTS + label + vector candidate sources
  -> HybridRanker
  -> SearchResult with MatchEvidence
  -> Compose result grid
```

### 7.1 Threading

- Compose and ViewModel state reduction run on the main dispatcher.
- Room suspend queries use Room's dispatcher.
- decoding, hashing, OCR awaiting, parsing, and vector math run away from the
  main thread.
- At most one heavy image-analysis pipeline runs by default. A benchmarked
  setting may allow two workers on high-memory devices.
- ML provider clients are reused when thread-safe and closed at application or
  worker lifecycle boundaries.

### 7.2 Dependency injection

The production migration introduces:

- `@HiltAndroidApp` application class;
- singleton database, DAO, repository, settings and WorkManager bindings;
- provider bindings keyed by capability;
- `@HiltViewModel` screen state holders;
- injected workers through the Hilt/WorkManager integration.

Tests replace provider and repository bindings with fakes. No ViewModel receives
an `Activity`, `Context`, `ContentResolver`, DAO, or ML Kit client directly.
Implementation details and test bindings follow
[Android dependency injection with Hilt](https://developer.android.com/training/dependency-injection/hilt-android).

## 8. Package and file structure

The target remains one Gradle app module with the following package structure:

```text
android/app/src/main/java/com/sagesearch/android/
  SageSearchApplication.kt
  MainActivity.kt
  di/
    DatabaseModule.kt
    RepositoryModule.kt
    AnalysisModule.kt
    WorkManagerModule.kt
  ui/
    navigation/SageSearchNavHost.kt
    onboarding/OnboardingScreen.kt
    onboarding/OnboardingViewModel.kt
    search/SearchScreen.kt
    search/SearchViewModel.kt
    search/SearchUiState.kt
    results/SearchResultCard.kt
    detail/ImageDetailScreen.kt
    detail/ImageDetailViewModel.kt
    indexing/IndexStatusScreen.kt
    indexing/IndexStatusViewModel.kt
    settings/SettingsScreen.kt
    settings/SettingsViewModel.kt
    components/
  domain/
    model/ImageRecord.kt
    model/ImageAnalysis.kt
    model/ReceiptData.kt
    model/SearchRequest.kt
    model/SearchResult.kt
    model/MatchEvidence.kt
    repository/ImageIndexRepository.kt
    repository/SearchRepository.kt
    repository/SettingsRepository.kt
    usecase/AnalyzeSelectedImages.kt
    usecase/StartLibraryIndex.kt
    usecase/SearchImages.kt
    usecase/RetryFailedAnalysis.kt
    usecase/RemoveIndex.kt
  data/
    db/SageSearchDatabase.kt
    db/entity/
    db/dao/
    db/migration/
    media/MediaStoreDataSource.kt
    media/PersistedUriDataSource.kt
    repository/DefaultImageIndexRepository.kt
    repository/DefaultSearchRepository.kt
    settings/UserSettingsDataSource.kt
  analysis/
    AnalysisPipeline.kt
    ImageDecoder.kt
    ImageFingerprint.kt
    ocr/OcrProvider.kt
    ocr/MlKitOcrProvider.kt
    receipt/ReceiptAnalyzer.kt
    receipt/HeuristicReceiptAnalyzer.kt
    labels/ImageLabelProvider.kt
    faces/FacePresenceProvider.kt
    embedding/MultimodalEmbeddingProvider.kt
    embedding/VectorStore.kt
  search/
    QueryParser.kt
    FtsCandidateSource.kt
    StructuredCandidateSource.kt
    LabelCandidateSource.kt
    VectorCandidateSource.kt
    HybridRanker.kt
    ExplanationBuilder.kt
  work/
    ImageWorkCoordinator.kt
    MediaReconcileWorker.kt
    ImageAnalysisWorker.kt
    IndexCleanupWorker.kt
  privacy/
    MediaPermissionState.kt
    PrivacyText.kt
```

Test packages mirror production packages. Large model assets, licenses, and
model cards live under `android/app/src/main/assets/models/<model-id>/` only when
the chosen distribution mode is bundled.

## 9. Domain contracts

Platform and database objects must not cross every layer. The central domain
contracts are stable Kotlin data types.

```kotlin
enum class ContentKind { RECEIPT, PICTURE, MIXED, UNKNOWN }
enum class AnalysisStatus { DISCOVERED, QUEUED, RUNNING, COMPLETE, FAILED, STALE, UNAVAILABLE }
enum class AccessScope { PICKED, PARTIAL_LIBRARY, FULL_LIBRARY }

data class ReceiptData(
    val merchant: EvidenceValue<String>?,
    val transactionDate: EvidenceValue<LocalDate>?,
    val totalMinorUnits: EvidenceValue<Long>?,
    val currencyCode: EvidenceValue<String>?,
    val receiptNumber: EvidenceValue<String>?,
    val itemKeywords: List<EvidenceValue<String>>,
    val paymentMethod: EvidenceValue<String>?,
)

data class EvidenceValue<T>(
    val value: T,
    val confidence: Float,
    val source: EvidenceSource,
    val sourceText: String?,
)
```

Money is stored as integer minor units plus an ISO 4217 currency code whenever
the currency is known. A raw display string is retained as evidence. Do not use
`Double` as the production source of truth for receipt totals.

Database timestamps are integer epoch values and calendar-only transaction dates
use an ISO `yyyy-MM-dd` string. Room mappers convert them to desugared
`java.time` domain types; locale-sensitive display formatting belongs in the UI.

Search returns domain objects, not Room entities:

```kotlin
data class SearchResult(
    val mediaKey: String,
    val uri: Uri,
    val score: Float,
    val kind: ContentKind,
    val thumbnail: ThumbnailReference,
    val evidence: List<MatchEvidence>,
)
```

`MatchEvidence` identifies the signal, matched value or excerpt, confidence, and
date semantics. It must be sufficient to render explanations without invoking a
model after retrieval.

## 10. Room data model

### 10.1 Database rules

- Database name: `sagesearch-image-index.db`.
- Enable foreign keys and transactions for multi-table analysis commits.
- Set `exportSchema = true`; commit exported JSON schemas.
- Every released schema version has an explicit auto or manual migration.
- Never use destructive migration in production.
- Derived records use `ON DELETE CASCADE` from the media record.
- The database lives in internal app storage and is excluded from Android cloud
  backup.

### 10.2 Tables

#### `media_items`

One row per authorized image.

| Column | Purpose |
|---|---|
| `media_key` | Stable app key; `volumeName:mediaStoreId` for MediaStore or a SHA-256 URI key for picked items |
| `content_uri` | Canonical `content://` URI; never convert to a guessed filesystem path |
| `access_scope` | Picked, partial library, or full library |
| `display_name` | User-visible media name when exposed by the provider |
| `mime_type` | Validated supported image MIME type |
| `width`, `height`, `size_bytes` | Decode planning and UI metadata |
| `date_taken_ms`, `date_added_s`, `date_modified_s` | Searchable metadata with explicit semantics |
| `volume_name`, `media_store_id`, `generation_modified` | MediaStore reconciliation identity |
| `relative_path` | Album/folder clue when MediaStore exposes it |
| `persisted_grant` | Whether a selected URI grant was persisted successfully |
| `first_seen_ms`, `last_seen_scan_id` | Reconciliation and cleanup |
| `availability` | Available, permission revoked, missing, unsupported, or corrupt |

#### `image_analyses`

One current aggregate row per media item.

| Column | Purpose |
|---|---|
| `media_key` | Primary/foreign key |
| `status`, `attempt_count`, `last_error_code` | Durable job state |
| `content_kind`, `content_confidence` | Receipt/picture/mixed/unknown result |
| `ocr_text`, `ocr_confidence` | Raw searchable OCR evidence |
| `ocr_version`, `receipt_rules_version` | Selective invalidation |
| `labels_version`, `face_version`, `embedding_version` | Optional signal versions |
| `content_fingerprint` | Change detection without retaining source pixels |
| `analyzed_at_ms`, `next_retry_at_ms` | Scheduling and UI status |

#### `receipt_fields`

One row per analyzed image with nullable normalized fields and source evidence.
Repeated item keywords are stored in `receipt_item_keywords` rather than a
comma-separated string.

#### `image_labels`

Zero or more rows containing stable label ID, localized display label,
confidence and provider version. Generated labels are supporting evidence and
must not overwrite OCR or structured receipt evidence.

#### `face_summaries`

At most one row per image: `has_faces`, `face_count`, `count_reliable` and
detector version. ML Kit does not expose a calibrated overall face-detection
confidence, so SageSearch must not invent one. `count_reliable` is a conservative
provider decision based on supported input quality and minimum detected face
size. Do not store crops, landmarks, contours, tracking IDs, embeddings, names,
or sensitive classifications.

#### `search_documents` and `search_documents_fts`

`search_documents` is a denormalized, transactionally maintained document with
separate columns for OCR, merchant, receipt number, item keywords, payment
method, labels, display name and relative path. `search_documents_fts` is a Room
`@Fts4` external-content entity.

Search uses `MATCH` to collect a bounded candidate pool, then ranks candidates
in Kotlin with field-specific boosts. User input must be tokenized and escaped
by `FtsQueryBuilder`; never interpolate untrusted syntax into SQL.

See [Room full-text entities](https://developer.android.com/training/data-storage/room/defining-data)
and [Room migration guidance](https://developer.android.com/training/data-storage/room/migrating-db-versions).

#### `image_embeddings`

One row per image/model pair: dimension, numeric format, L2-normalized vector
BLOB, model ID and model version. The DAO is hidden behind `VectorStore` so an
exact cosine implementation can later be replaced with an approximate-nearest-
neighbor implementation without changing repositories or UI.

#### `index_state`

Stores access scope, scan ID, MediaStore version/generation per volume, pipeline
version, aggregate counts, last successful reconciliation and cancellation
state. It is the source for progress UI.

### 10.3 Prototype migration

The first production migration converts `indexed_images` rows into the new
schema where possible. Existing URI, OCR, kind, confidence and receipt values
are preserved. Rows using temporary grants that can no longer be read become
`UNAVAILABLE`; they are not silently deleted until reconciliation or user
confirmation.

## 11. Media discovery and reconciliation

### 11.1 Selected-image ingestion

1. User launches the Photo Picker.
2. App attempts to persist read access for returned URIs.
3. Repository validates scheme, MIME type and readable metadata.
4. Room transaction upserts `media_items` and marks changed/new items
   `DISCOVERED`.
5. Coordinator enqueues the unique analysis pipeline.
6. UI immediately shows queued items and persistent progress.

### 11.2 MediaStore ingestion

1. Confirm current permission state and access scope.
2. Enumerate authorized `MediaStore.Images` rows from applicable external
   volumes using a narrow projection.
3. Upsert metadata in pages; do not open pixels during discovery.
4. Mark each observed row with the active scan ID.
5. After a successful scan, mark unseen rows unavailable and cascade-delete
   derived data according to the cleanup policy.
6. Queue only new, changed, stale, or explicitly retried images.

Use `MediaStore.getVersion()` to detect substantial store changes and
`MediaStore.getGeneration(volume)` to detect changes more reliably than wall-
clock media dates. If the version changes, perform a full reconciliation. See
[Access media files from shared storage](https://developer.android.com/training/data-storage/shared/media).

### 11.3 Change key

The first-pass change key combines provider identity, size, dimensions,
generation-modified value and modified date. A bounded content fingerprint is
computed when pixels are opened. Reanalysis occurs when:

- the change key or fingerprint changes;
- a required provider version changes;
- analysis is missing, failed and retryable, or explicitly rebuilt;
- previously unavailable media becomes readable again.

## 12. Persistent work design

WorkManager is the scheduler, while Room is the durable job ledger.

### 12.1 Unique work

| Work name | Policy | Responsibility |
|---|---|---|
| `image-index-reconcile` | `KEEP` for ordinary triggers; `REPLACE` only for explicit rebuild | Reconcile grants/MediaStore and queue analysis |
| `image-analysis-pipeline` | `KEEP` | Claim and analyze bounded database batches |
| `image-index-cleanup` | `KEEP` | Remove orphaned derived data and expired errors |
| `image-index-periodic-reconcile` | unique periodic | Lightweight permission/media change check |

Unique work prevents duplicate scheduling. See
[Managing WorkManager work](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work).

### 12.2 Worker behavior

- Implement workers as `CoroutineWorker`.
- Claim a transactionally locked batch, initially 25 images.
- Complete and checkpoint within the ordinary WorkManager execution window.
- Return `Result.retry()` only for transient failures and use bounded backoff.
- Return success after recording stable per-item unsupported/corrupt failures;
  one image must not fail the entire queue.
- Check `isStopped` between images and before expensive provider calls.
- Release `RUNNING` rows back to `QUEUED` if a worker is interrupted.
- Enqueue the next batch only when eligible work remains.
- Publish progress to `index_state`; do not use WorkManager progress as the sole
  durable UI source.
- Implement pause as `index_state.pause_requested = true`. Workers checkpoint
  between images and stop claiming batches; the coordinator cancels pending
  pipeline work after the checkpoint. Resume clears the flag and enqueues the
  unique pipeline again. Pause never deletes queued rows or derived results.

Constraints default to `requiresBatteryNotLow` and `requiresStorageNotLow`.
Visual embeddings may optionally require charging. OCR of a manually selected
image may start immediately while the app is foregrounded.

For an explicit, user-visible rebuild that cannot be divided into short batches,
use WorkManager long-running work with an ongoing notification and the required
foreground service type. Prefer bounded batches first. See
[WorkManager task scheduling](https://developer.android.com/develop/background-work/background-tasks/persistent)
and [long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running).

## 13. Bounded image decoding

Original images are never copied or modified. `ImageDecoder` opens the authorized
URI through `ContentResolver` and produces bounded, correctly oriented inputs.

Rules:

- Reject unsupported MIME types before decoding.
- Catch provider, permission, malformed-image and out-of-memory failures as
  distinct stable error codes.
- Read bounds before allocating a bitmap.
- Default to a maximum decoded pixel budget of 6 megapixels and maximum edge of
  4096 pixels; benchmark and tune these constants.
- Preserve enough resolution for OCR: target at least 16 pixels per character
  where practical.
- Tile unusually long receipts rather than downsampling all text below useful
  resolution.
- Apply EXIF/media orientation exactly once.
- Reuse the bounded bitmap across compatible providers within one pipeline run.
- Release bitmaps and provider inputs promptly; never retain source pixels in
  Room or logs.
- UI thumbnails use MediaStore thumbnail APIs or an image-loading library with
  bounded memory/disk caching, not the analysis bitmap.

ML Kit's input-quality and performance guidance is documented in
[Text Recognition v2 for Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android).

## 14. Analysis pipeline

`AnalysisPipeline.analyze(mediaKey)` is the only production entry point for
derived image data.

1. Re-read the media row and permission state.
2. Open and bounded-decode the current content.
3. Compute the content fingerprint.
4. Run OCR on every supported image.
5. Normalize OCR while preserving original text and line/block evidence.
6. Run receipt classification and field extraction.
7. If enabled, run general labels.
8. If enabled, run face-presence detection.
9. If enabled, run the image side of the multimodal encoder.
10. Validate provider output and any confidence ranges the provider actually
    exposes.
11. Commit analysis, receipt, label, face, embedding and FTS rows in one Room
    transaction.
12. Mark the item complete and update aggregate progress.

If optional labels, faces, or embeddings fail, preserve successful OCR and
receipt results and mark only the affected signal stale/failed. A later retry
must not redo valid expensive signals whose input fingerprint and version match.

### 14.1 Receipt classification

- Output one of receipt, mixed, picture or unknown plus confidence.
- Thresholds are constants tied to `receipt_rules_version`.
- Picture/unknown results retain OCR but expose no receipt-only fields.
- Mixed results may retain provisional fields, visibly marked as uncertain.
- Field extraction returns source text and confidence for every value.
- Date normalization preserves whether a day/month order was inferred.
- IDR parsing supports grouped whole amounts without mistaking separators for
  decimal cents.
- The analyzer must preserve raw OCR even when parsing fails.

### 14.2 OCR provider

```kotlin
interface OcrProvider {
    val version: ProviderVersion
    suspend fun recognize(input: DecodedImage): OcrResult
}
```

The baseline implementation remains bundled ML Kit Latin Text Recognition
(`com.google.mlkit:text-recognition:16.0.1`) so the first analysis works offline
without a model download. Bundled versus Play-services delivery may be revisited
only with APK-size and first-run reliability measurements. ML Kit documents both
paths in its [model installation guide](https://developers.google.com/ml-kit/tips/installation-paths).

### 14.3 Face-presence provider

Configure ML Kit face detection for fast performance with landmarks, contours
and classifications disabled. Store only presence, count, a conservative
count-reliability flag and detector version. ML Kit does not provide an overall
detection-confidence value, so the implementation must not present one. Never
persist face rectangles after analysis unless a separate, reviewed UI
requirement is approved. See
[ML Kit face detection](https://developers.google.com/ml-kit/vision/face-detection/android).

### 14.4 Labels and semantic embeddings

General-picture search has two separate signals:

1. **Image labels:** ML Kit's general model can provide common object/scene labels
   and confidence. These are useful supporting text evidence but are not a claim
   of open-vocabulary semantic retrieval.
2. **Multimodal embeddings:** natural-language queries such as "bicycle near a
   beach" require image and text encoders in the same vector space. An image-only
   embedder cannot satisfy this requirement.

```kotlin
interface MultimodalEmbeddingProvider {
    val model: EmbeddingModelManifest
    suspend fun encodeImage(input: DecodedImage): NormalizedEmbedding
    suspend fun encodeText(query: String): NormalizedEmbedding
}
```

The selected model must pass an ADR benchmark covering Indonesian and English
queries, Recall@40, APK/model size, RAM, median/p95 encoding time, supported CPU
architectures, license, and offline availability. Candidate implementations may
use LiteRT or MediaPipe Tasks, but no experimental dependency is accepted merely
because it is newer. MediaPipe's
[Android Image Embedder](https://ai.google.dev/edge/mediapipe/solutions/vision/image_embedder/android)
is suitable for image embedding mechanics; SageSearch still requires a
compatible text encoder for text-to-image retrieval.

ML Kit image labeling is documented at
[Image labeling on Android](https://developers.google.com/ml-kit/vision/image-labeling/android).

## 15. Search architecture

### 15.1 Query contract

```kotlin
data class SearchRequest(
    val rawQuery: String,
    val textTerms: List<String>,
    val contentKinds: Set<ContentKind>,
    val merchant: String?,
    val amountRangeMinor: LongRange?,
    val currencyCode: String?,
    val transactionDateRange: ClosedRange<LocalDate>?,
    val mediaDateRange: ClosedRange<Instant>?,
    val labels: List<String>,
    val faceFilter: FaceFilter?,
    val albumHint: String?,
)
```

The first Android release uses a deterministic local parser for explicit terms,
amounts, dates, receipt language and visible filter chips. A future cloud or
local language model may translate a query into this same validated contract,
but receives no local results and cannot directly query Room.

### 15.2 Candidate generation

Candidate sources run independently:

- structured SQL for reliable content kind, receipt values, dates, faces and
  media metadata;
- FTS4 `MATCH` over OCR and normalized searchable text;
- exact/partial label candidates;
- vector cosine candidates after the semantic milestone.

Hard constraints are applied before final ranking. Each source returns at most a
bounded pool, initially 200 candidates. Empty text queries must not accidentally
scan and render the entire database; the UI shows a recent/limited state instead.

### 15.3 Ranking

`HybridRanker` combines source ranks using reciprocal-rank fusion, then applies
documented boosts:

1. exact structured receipt match;
2. exact merchant/amount/date agreement;
3. exact OCR phrase;
4. multiple requested clues agreeing on one image;
5. label evidence;
6. vector similarity;
7. metadata support and recency only when requested.

Scores from different models are never added as though they share calibration.
RRF operates on ranks; provider-specific thresholds decide whether evidence is
eligible. The default response is capped at 40 and the strongest 20 appear
first.

### 15.4 Explanations

Every result includes one or more plain-language explanations, for example:

- `Merchant matches Toko ABC (receipt text)`
- `Visible text contains coffee`
- `Transaction date: 18 July 2026`
- `Detected label: bicycle (82%)`
- `Visual similarity to "beach bicycle"`
- `Contains approximately 2 faces`

The explanation must name transaction date versus media date. Low-confidence
AI values are labeled as estimates. No explanation may cite evidence that is not
stored for that result.

## 16. UI and navigation

### 16.1 Destinations

1. **Onboarding/privacy:** local processing explanation and selected versus
   library scope choice.
2. **Search:** search field, useful filter chips, current index completeness and
   privacy-safe suggested searches. Search history is not persisted in the
   baseline release.
3. **Results:** adaptive grid/list, count capped at 40, thumbnails and evidence.
4. **Image detail:** larger preview, indexed fields, evidence, open-original and
   reanalyze actions.
5. **Index status:** discovered/queued/running/complete/failed counts, progress,
   pause/cancel/retry and failure categories.
6. **Settings:** access scope, charging/battery behavior, optional models, index
   size, rebuild, clear index and privacy details.

### 16.2 UI state

Each screen has one immutable `${Screen}UiState` exposed as `StateFlow` and
collected with `collectAsStateWithLifecycle`. One-off failures become durable or
acknowledgeable state, not lossy callback events. Compose functions receive
state and callbacks; they do not call DAOs, WorkManager, ContentResolver or ML
providers.

### 16.3 Required states

- Empty index with a clear first action.
- Permission denied, partial, full and revoked.
- Initial scan, analyzing, paused, incomplete and complete.
- Search available while indexing.
- No results with searched evidence and one useful refinement.
- Broad results with a narrowing suggestion.
- Missing/revoked original with retained explanation until cleanup.
- Model unavailable or optional feature not installed.
- Low storage and battery deferral.

Accessibility requirements include content descriptions for image actions,
keyboard/focus support, scalable text, minimum touch targets, and status that
does not rely on color alone.

## 17. Privacy and security

### 17.1 Local data boundary

The baseline app processes and stores the following only inside the Android app
sandbox: media URIs/metadata, OCR, receipt fields, labels, face summary,
embeddings, thumbnails/cache references, jobs and results. Original images stay
in their provider and are never modified.

### 17.2 Manifest and backup

- Do not declare `INTERNET` for the local-only release unless a separately
  reviewed feature requires it.
- Set `android:allowBackup="false"`, or define data extraction/backup rules that
  explicitly exclude the Room database, preferences containing media scope, ML
  output and thumbnail caches.
- Do not export providers, services, receivers or workers unnecessarily.
- Declare only version-appropriate media permissions.
- Release builds disable debug logging and are minified with R8.

Android backup security guidance is at
[Security recommendations for backups](https://developer.android.com/privacy-and-security/risks/backup-best-practices).

### 17.3 Logging and diagnostics

Logs and crash reports must not contain:

- OCR text or receipt fields;
- full content URIs, display names or album paths;
- embeddings or source pixels;
- search queries unless the user explicitly submits diagnostic data after a
  preview/redaction step.

Use stable error codes, provider versions, elapsed time buckets and aggregate
counts for diagnostics.

### 17.4 Deletion

- Clearing the index deletes Room rows, embedding/model-derived data and bounded
  thumbnail caches, then cancels unique work.
- Removing a picked image releases its persistable URI permission when no other
  app record uses it.
- Revoked or removed library images are reconciled and all derived rows cascade
  from `media_items`.
- Uninstall relies on the Android sandbox and disabled/excluded backup so derived
  data is not silently restored.

## 18. Failure model and recovery

Stable failure categories:

| Code | Retry | User behavior |
|---|---|---|
| `PERMISSION_REVOKED` | After access changes | Show unavailable and access action |
| `MEDIA_MISSING` | Reconcile only | Remove derived data after confirmed scan |
| `UNSUPPORTED_FORMAT` | No | Explain limitation |
| `DECODE_CORRUPT` | Manual after source changes | Keep stable failure; do not block queue |
| `OUT_OF_MEMORY_GUARD` | Once with lower decode budget | Explain if bounded retry fails |
| `OCR_TRANSIENT` | Bounded backoff | Preserve other valid signals |
| `MODEL_UNAVAILABLE` | After install/model state changes | Show optional feature state |
| `DATABASE_TRANSIENT` | Bounded retry | Transaction rolls back completely |
| `STORAGE_LOW` | When constraint clears | Show deferred status |
| `UNKNOWN` | One automatic retry, then manual | Redacted diagnostic code |

At application startup, reset abandoned `RUNNING` rows to `QUEUED` unless an
active worker owns the same lease. Retry counts and timestamps prevent infinite
loops.

## 19. Performance, battery and storage budgets

Initial validation collection: 10,000 images.

| Metric | Target/gate |
|---|---|
| Warm text/structured search | First results begin rendering within 2 seconds on a representative mid-range phone |
| Result count | No more than 40 by default |
| UI responsiveness | No image decode, OCR, hashing or vector scan on main thread |
| Analysis concurrency | 1 by default; 2 only after memory benchmark |
| Worker batch | 25 images initially, remotely unchangeable in local-only build but configurable in code/tests |
| Peak bitmap memory | Bounded by decoder budget; no unbounded original decode |
| Restart behavior | No loss of completed work; interrupted batch resumes safely |
| Battery | Background work honors battery-not-low; embeddings may require charging |
| Storage | Index screen reports database, model and cache sizes independently |

Benchmarks record device model, API, available RAM, image mix, thermal state,
model versions, throughput, p50/p95 latency, peak memory and energy/battery
impact. Emulator numbers do not serve as release performance evidence.

Add Macrobenchmark and a Baseline Profile only after the critical user journeys
stabilize. Android documents this workflow at
[Create Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile).

## 20. Testing strategy

### 20.1 Shared evaluation fixtures

Create a consented fixture manifest shared conceptually with Windows. Images are
not committed if licensing or privacy does not permit it. Each fixture records:

- expected OCR tokens rather than requiring a brittle full-string match;
- expected kind and minimum/maximum confidence band;
- expected receipt fields and acceptable alternatives;
- object/scene and face-presence truth when applicable;
- language, currency, orientation, lighting and degradation tags.

Include clean, rotated, low-light, long, wrinkled and mixed Indonesian/English
receipts; ordinary photos with incidental text; screenshots and signs; zero,
one and multiple faces; duplicates; corrupt and unsupported files.

### 20.2 JVM unit tests

- Receipt amount/date/currency normalization.
- Receipt/picture/mixed/unknown classification boundaries.
- No receipt fields exposed for ordinary pictures.
- Query parsing and FTS escaping.
- Hybrid ranking and explanation generation.
- Model-version invalidation and retry policy.
- ViewModel `StateFlow` behavior using fake repositories.

### 20.3 Instrumented tests

- DAO queries, foreign-key cascade and transaction rollback.
- Every Room migration using exported schemas and `room-testing`.
- FTS synchronization and result limits.
- MediaStore projection mapping using test data/fakes where platform setup is
  impractical.
- WorkManager batch claim, interruption, retry and unique-work behavior.
- Compose navigation, empty/error/partial-access states and accessibility.
- Persisted Photo Picker URI behavior on supported API levels.

### 20.4 Device matrix

At minimum:

- API 23 legacy storage-permission behavior;
- API 32 legacy-to-modern boundary;
- API 33 `READ_MEDIA_IMAGES`;
- API 34+ selected-photo versus full-photo access;
- target/API 36 behavior;
- one low-memory physical device and one representative mid-range phone.

### 20.5 Evaluation metrics

- Receipt classification precision/recall.
- Merchant/date/total/currency/item extraction accuracy.
- OCR token recall by language and image condition.
- Query Recall@40 and precision in the first 20.
- Face-presence accuracy, without identity metrics.
- Cold/warm search latency and analysis throughput.
- Restart recovery, permission revocation and deletion completeness.
- Peak memory, database/model size and battery impact.

Threshold values are approved after the baseline fixture set is measured; they
must then be encoded in release-gate tests or evaluation reports.

## 21. Build, CI and release

### 21.1 Existing verification

The current GitHub Actions workflow uses Java 17 and Android SDK 36, then runs:

```text
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

### 21.2 Required CI evolution

1. Keep wrapper checksum verification and dependency caching.
2. Export and compare Room schemas.
3. Add instrumented tests on a Gradle Managed Device for a modern API.
4. Add dedicated migration tests and WorkManager tests.
5. Build a minified release variant on every release candidate.
6. Produce signed APK/AAB only through protected release secrets/environments.
7. Generate an SBOM/dependency report and retain model license/model-card files.
8. Run Macrobenchmark separately; do not block every small PR on unstable
   emulator performance.

Debug artifacts remain explicitly labeled test-only. Production version codes
increase monotonically, and release notes identify database and model-version
changes that trigger reanalysis.

## 22. Delivery milestones and gates

### Milestone A - current vertical slice (complete)

- Single Photo Picker image.
- Bundled on-device OCR.
- Receipt/mixed/picture/unknown interpretation.
- Manual Room persistence and basic search.
- Unit tests, lint, CI and debug APK.

Gate: verified by commit `c77a881` and its successful Android CI run.

### Milestone B - production foundation

- Refactor to UDF/ViewModels/repositories/injection.
- Introduce normalized Room schema, FTS4, exported schemas and migrations.
- Add result thumbnails, open-original, detail, remove and clear-index actions.
- Preserve prototype behavior through regression tests.

Gate: 100 manually selected images remain searchable after restart and upgrade;
no UI class directly accesses a DAO or ML client.

### Milestone C - authorized library ingestion

- Add selected/partial/full scope onboarding.
- Implement MediaStore discovery and reconciliation.
- Implement WorkManager batches, progress, cancellation and retry.
- Handle permission changes across API tiers.

Gate: index 1,000 authorized images without blocking search; kill/restart resumes
without duplicate analysis; revoked media becomes unavailable and is cleaned.

### Milestone D - receipt-search beta

- Complete receipt fields and evidence.
- Add structured filters, FTS candidate generation, hybrid ranking and result
  explanations.
- Build the receipt evaluation set and tune thresholds.

Gate: agreed receipt Recall@40 and field-accuracy thresholds pass; every result
explanation matches stored evidence; core workflow works offline.

### Milestone E - general-picture AI

- Add supporting image labels.
- Select and integrate a licensed, benchmarked multimodal encoder.
- Add vector candidate generation and hybrid ranking.

Gate: representative object/scene queries reduce the evaluation library to
20-40 useful candidates with acceptable latency, storage and battery use.

### Milestone F - face presence

- Add non-identifying face presence/count.
- Add face filters and explanations.
- Complete privacy and false-positive review.

Gate: evaluation threshold passes and an audit confirms that no identity,
landmark, crop or face embedding is stored.

### Milestone G - scale, privacy and release candidate

- Benchmark 10,000 images.
- Complete deletion, model invalidation, corruption and low-resource behavior.
- Add accessibility, adaptive layout, release build, backup exclusions and
  complete privacy copy.

Gate: all functional mappings below pass, warm search meets the target, release
artifacts are signed, and the final manual checklist passes on physical devices.

## 23. PRD requirement traceability

| PRD | Android implementation | Primary verification |
|---|---|---|
| FR-01 | Access-scope onboarding, Photo Picker/MediaStore data sources and permission-aware reconciliation | Permission matrix tests; no unauthorized row discovered |
| FR-02 | Unique WorkManager pipeline plus Room job state | Process-kill/restart and duplicate-enqueue tests |
| FR-03 | `OcrProvider` runs for every supported image | Fixture OCR-token recall and provider integration tests |
| FR-04 | Versioned receipt/picture/mixed/unknown classifier | Boundary fixtures and low-confidence retrieval tests |
| FR-05 | Normalized `receipt_fields`, keyword table and evidence values | Field extraction evaluation and raw-OCR preservation tests |
| FR-06 | Structured candidate source over receipt and media metadata | Combined-filter repository tests |
| FR-07 | Bounded sources, HybridRanker and hard maximum 40 | 10,000-row query and UI result-count tests |
| FR-08 | `MatchEvidence` and `ExplanationBuilder` | Every displayed explanation traced to stored evidence |
| FR-09 | Bundled OCR, local Room/FTS/vector providers, no required network | Airplane-mode end-to-end test |
| FR-10 | No cloud provider or `INTERNET` permission in baseline | Manifest/privacy review and network inspection |
| FR-11 | FacePresenceProvider stores only summary | Face fixture evaluation and schema/privacy audit |
| FR-12 | MultimodalEmbeddingProvider and vector candidates | Object/scene Recall@40 evaluation |
| FR-13 | Media generation/change key, fingerprint and provider versions | Unchanged-skip and selective-invalidation tests |
| FR-14 | Foreign-key cascades, reconciliation and clear-index use case | Removal/revocation/deletion-completeness tests |
| FR-15 | Stable error codes, bounded retry and retry/rebuild UI | Worker retry and manual recovery tests |
| FR-16 | `VectorStore` and image-query extension point | Deferred; acceptance defined by a future image-similarity ADR |

## 24. Definition of Android release readiness

The Android application is ready for its planned release only when:

- a new user understands that image pixels are read locally and derived data is
  stored on the device;
- only explicitly authorized media is indexed;
- indexing thousands of images does not prevent searching;
- interrupted work resumes and unchanged work is skipped;
- receipt OCR and fields are locally searchable with evidence;
- general-picture object/scene search and face presence meet their gated scope;
- results are capped at 40 and explain why they matched;
- core receipt search works in airplane mode;
- permission revocation, source removal and clear-index delete derived data;
- no backup, log, crash report or network request leaks private index content;
- named-person recognition and other excluded sensitive inference are absent;
- unit, lint, migration, worker, UI, device and evaluation gates pass.

## 25. Decisions still requiring an ADR

The architecture is implementable without resolving every future product
choice. The following must be decided before their named milestone:

1. **ADR-001, media scope positioning:** whether library mode is a primary
   onboarding choice or an advanced setting. Required before Milestone C UX is
   finalized.
2. **ADR-002, multimodal model:** model, license, dimension, quantization,
   distribution and runtime. Required before Milestone E implementation.
3. **ADR-003, vector index:** exact cosine versus an ANN/native extension based
   on the 10,000-image benchmark. Required before Milestone E exits.
4. **ADR-004, optional model delivery:** bundled versus Play-services/downloaded
   models based on APK size and offline-first behavior.
5. **ADR-005, Android/Windows relationship:** phone-local only versus future
   synchronized indexes. Synchronization remains outside the planned Android
   release until this decision is separately specified and privacy-reviewed.
6. **ADR-006, cloud enrichment:** provider, consent, payload, retention, quota
   and deletion. No cloud image feature may be implemented before this ADR and
   a PRD privacy update.

An ADR cannot silently weaken the PRD's local baseline, access boundaries,
result explanations, deletion requirements, or prohibition on named-person
recognition.

## 26. Implementation order

Developers should implement the specification in this dependency order:

1. Domain contracts and new Room schema with migration tests.
2. Repositories, dependency injection and ViewModel/UDF UI migration.
3. FTS4 search, result evidence, thumbnails and index controls.
4. Permission-state model and selected-image persistence.
5. MediaStore reconciliation and WorkManager batches.
6. Receipt evaluation and structured/hybrid ranking.
7. Labels and multimodal model benchmark/integration.
8. Face-presence provider.
9. Scale, privacy, accessibility and release hardening.

Every step must leave `testDebugUnitTest`, `lintDebug`, `assembleDebug`, Room
migration tests, and applicable device tests passing. Each milestone is a
revertible commit series and may not claim the next milestone's capability in
the UI before its gate passes.
