# Public implementation status

Last reviewed: 2026-08-05

AskAlbum is an early open-source Android implementation of private, on-device
photo search. The public source snapshot contains the Android application,
fixture CI path, tests, model-pack validation code, sample-gallery tooling, and
architecture documentation. It does not contain user media, generated indexes,
model binaries, APKs, device logs, or private credentials.

## Build status

- `ciDebug` and `fixtureCiDebug`: model-independent fixture builds for CI and contributors.
- `offlineDemoDebug`: local-only demo variant with no Internet permission.
- `consumerDebug`: production-style variant with explicit verified model-pack
  download support; model packs are deliberately external.

The repository workflow is the authoritative build check. Device acceptance
requires a configured Android device and locally available model packs, so it is
not represented as a GitHub Actions pass.

## Privacy boundary

Media analysis, OCR, face indexing, embeddings, retrieval, Gemma planning,
verification, and grounded answer composition are designed to run on device.
Face indexing remains explicit opt-in. Sensitive OCR values remain protected.
See `PRIVACY.md` and `THIRD_PARTY_NOTICES.md`.

## Retrieval exactness

- Bounded semantic retrieval is reported as estimated or partial and never as
  an exhaustive count.
- An explicitly requested exact semantic count is stored in Room version 19 as
  a durable scan scope, cursor, lease, and deduplicated media-hit set. Small
  vector batches checkpoint transactionally and resume through the recovery
  worker after process death or cancellation.
- The exact path is exhaustive over available indexed vectors. If any eligible
  media lacks vector coverage, the result remains partial and is not promoted
  to `COMPLETE_MODEL_SCAN`.
- Full per-item Gemma verification is intentionally not run for every image;
  targeted verification remains the confirmation path for person-conditioned
  visual predicates.

## 2026-08-05 validation checkpoint

- Added the model-independent `fixtureCi` variant and wired its deterministic
  planner, verifier, answer, OCR, face, and embedding fixtures without changing
  consumer/release model-backed providers.
- Eligible-scope coverage now drives channel reports and deterministic exactness;
  non-semantic retrieval is never labeled a complete model scan, and event
  membership cannot promote unsupported member media for predicate queries.
- Verified `consumerDebug`, `offlineDemoDebug`, `fixtureCiDebug`, consumer lint,
  focused unit tests, Room v18-to-v19 migration on a connected Android 16
  device, and replacement launches for consumer and fixture APKs.
- Debug test components use an app-owned signature permission; nonessential
  provider, service, and download-activity entry points are not exported.
- Person verification now includes labelled lower-body/feet crops, and the
  deterministic follow-up fallback handles natural references such as “make
  them close-ups” and “same event but videos”.
- Native vector scanning ships a portable scalar baseline instead of assuming
  ARMv8.2 FP16; derived evidence IDs use SHA-256 and the Gradle wrapper is
  executable in Git.
- High-risk OCR, query-history, People-label, person-attribute, and semantic-fact
  values are encrypted with an Android Keystore AES-GCM envelope; legacy
  plaintext rows remain readable through non-destructive migrations. FTS keeps
  only a redacted searchable projection for protected OCR values.
- Current checkpoint was replacement-installed only; no app data, People data,
  indexes, media, or model packs were cleared or reset.

## Known limitations

- Optional model packs are large external artifacts and are not reproducible
  from the source tree alone.
- Hardware acceleration and throughput vary by Android device and LiteRT/ONNX
  runtime support.
- Real-gallery acceptance requires user-provided media and must be run without
  uploading that media to issue trackers or CI artifacts.
- Experimental capabilities may report unavailable or partial coverage rather
  than claiming exhaustive search.

Historical device-specific reports and generated diagnostics are intentionally
not part of the public source snapshot. Record new reproducible results in a
redacted issue or pull request instead of committing private paths, serials,
media, databases, or logs.

### Secure derived storage checkpoint (2026-08-05)

- Added the non-destructive Room v19-to-v20 migration and an idempotent Keystore backfill marker.
- OCR blocks, video-keyframe OCR, query history, follow-up session text, result-set queries, and semantic-scan query text are protected at rest while existing read/search APIs transparently reveal values at the database boundary.
- Existing media rows, vectors, People data, semantic facts, captions, events, and model packs are preserved; no uninstall or data reset was used.
- Unit tests, the v19-to-v20 migration test, Keystore instrumentation, replacement install, and consumer launch passed on SM-F966B.
- Searchable FTS intentionally retains redacted labels rather than ciphertext;
  raw protected values are revealed only at the repository/evidence boundary.

### Deterministic aggregation checkpoint (2026-08-05)

- SUM, MIN, MAX, and MIN_MAX now consume bulk OCR facts from the complete hard-filtered eligible media set instead of bounded ranked hits.
- Aggregation evidence remains media-bound and currency checks remain deterministic; ordinary visual retrieval and semantic counts remain bounded/estimated unless fully scanned.
- Added regression coverage for a valid fact below ranked top-K and for distinct MIN/MAX operations.
- Consumer, offlineDemo, fixtureCi, unit tests, lint, and connected launch gates passed after the change.

### Encrypted OCR aggregate checkpoint (2026-08-05)

- Encrypted `media_item.ocr_text` and all new aggregate writes with the existing Keystore envelope; reads remain compatible through the database boundary.
- Rebuilt the media FTS projection from a deterministic redacted view that retains searchable labels but never stores raw passwords, contact values, or payment-card candidates.
- Added email/phone/contact detection and cached the Keystore key for bounded migration cost.
- Existing device database startup after replacement install reached MainActivity in 12 seconds without SQLite, Keystore, fatal-exception, or ANR evidence; device Keystore test and all build/lint gates passed.
- Financial OCR and semantic-fact migrations are covered by the later sensitive
  storage checkpoints below; no plaintext backfill or data reset is required.
- 2026-08-05: Hardened activity/person parsing: negative predicates, unknown interactions, and placeholder JSON values are rejected before typed person facts; recognized actions remain cluster-bound.
- 2026-08-05: Deterministic LIST grouping now uses complete eligible source hits for places, events, and date buckets; added a below-top-K regression test.
- 2026-08-05: Event summary, timeline, and comparison answers now consume complete resolved event membership when available, carry deterministic scope evidence, and disclose ranked-pass fallback when a scope cannot be resolved.
- This phase passed full consumer unit tests, consumer/offlineDemo/fixtureCi builds, consumer lint, and replacement-installed launch on the retained-data connected device.
- 2026-08-05: Confirmed the application-level GemmaSessionManager is shared by planning, visual verification, answer composition, and adaptive enrichment. Removed unreachable direct Engine factories from those consumers.
- Added fake-engine coverage proving text/vision/text calls reuse one initialized multimodal engine, while model-generation or modality changes close and replace it. Full unit/variant/lint gates and retained-data replacement launch passed.
- 2026-08-05: Added priority-aware serialized inference leases. Interactive Gemma planning, visual verification, answer composition, and text retrieval now outrank queued background embedding/enrichment work; canceled waiters are removed without starving later requests.
- Background embedding APIs retain default background priority, while semantic and caption query vectors use the interactive entry point. Full unit/variant/lint gates and retained-data replacement launch passed.
- 2026-08-05: Scoped expired-lease recovery by pipeline so media analysis cannot reclaim embedding claims or vice versa. Semantic enrichment now continues after retryable item failures, quarantines through the existing per-item retry policy, and schedules the next due retry without whole-worker backoff after mixed progress.
- 2026-08-05: Preserved semantic fact scope during completion. Exact-duplicate sharing now stores the original scoped fact plus verified digest-member media copies; event and visual-group facts are no longer rewritten as media facts. Connected database regression coverage passed.
- 2026-08-05: Added non-destructive Room v20-to-v21 semantic generation provenance. One Gemma response now carries a shared generation ID through its caption, facts, person facts, and caption chunks; legacy captions remain readable and are chunked from caption text without uncorrelated structured/person facts. Added same-generation chunk isolation and migration coverage.
  Validation: consumer unit tests, consumerDebug, offlineDemoDebug, fixtureCiDebug, and consumer lint PASS; connected v20-to-v21 migration test PASS; replacement-installed consumerDebug and launched MainActivity with no app fatal/ANR markers.

### Activity and indexing-state checkpoint (2026-08-05)

- Added the non-destructive Room v21-to-v22 migration with durable semantic-job priority and deterministic priority backfill. Personal media work now outranks representative background work, and superseded/obsolete errors are excluded from the visible latest-error query while history remains stored.
- Activity-aware caption parsing now stores `image_subject` and explicit activity state, suppresses typed actions for explicit static-image states, rejects negative or unknown interaction predicates, and ignores JSON null/non-string placeholder values before persistence.
- Focused and full consumer unit tests, offlineDemo/fixtureCi assembly, consumer lint, connected v21-to-v22 migration test, consumer replacement install, and retained-data MainActivity launch passed. A combined lint/variant invocation hit a concurrent fixtureCi generated-stub tooling race; the same tasks passed when run separately.
- No app data, People corrections, indexes, captions, media, or model packs were cleared or reset.

### Shared Gemma generation-budget checkpoint (2026-08-05)

- Added typed `GemmaGenerationOptions` for seed, temperature, structured-output mode, and per-call maximum output tokens. Planner, visual verification, grounded answers, and adaptive captions now use explicit bounded budgets through the shared Gemma session.
- Updated LiteRT-LM from `0.14.0` to `0.15.0` because the resolved runtime is the first locally available version exposing real per-conversation output-token and structured-response controls. No second engine or image-encoding pass was introduced.
- Full consumer unit tests, consumer/offlineDemo/fixtureCi assemblies, consumer lint, replacement installation, and retained-data MainActivity launch passed. The launch smoke check found no fatal exception or ANR markers; it did not invoke a model-backed query.

## 2026-08-05 - Semantic provenance repair

- Status: PASS for focused implementation gates.
- Added Room v22-to-v23 migration with idempotent event/group scope repair, digest-backed exact-duplicate preservation, and legacy ambiguity quarantine.
- Invalid legacy and cross-generation caption chunks are invalidated for deterministic text-only backfill; valid Gemma captions, image vectors, OCR, People data, and model packs are preserved.
- Retrieval direct-evidence scoring now rejects contextual, legacy-uncorrelated, legacy-uncertain, stale, and possible-inference applicability.
- Focused JVM tests: PASS.
- Migration instrumentation test on SM-F966: PASS.
- Consumer lint and consumer/offlineDemo/fixtureCi assemblies: PASS.
- Replacement consumer install and launch smoke check on SM-F966: PASS; no sampled fatal exception or ANR.
- Remaining gap: full backlog repair and model-backed semantic acceptance queries require longer device observation.

## 2026-08-05 - People identity data-at-rest protection

- Changed files: `android/app/src/main/java/io/github/askalbum/SensitiveDataAtRest.kt`, `android/app/src/main/java/io/github/askalbum/GalleryDatabase.kt`, `android/app/src/androidTest/java/io/github/askalbum/PeopleIdentityProtectionDeviceTest.kt`.
- Reused the existing Keystore envelope and advanced the sensitive-data migration marker from v3 to v4; no Room schema or destructive data migration was introduced.
- Protected reviewed People labels, relationships, aliases, and person-bound visual values/attributes on new writes and legacy backfill; database-boundary reads preserve People search and reviewed corrections.
- Tests: focused JVM People/provenance tests PASS; connected People identity, People privacy, and sensitive-data tests PASS (3/3); consumerDebug, offlineDemoDebug, and fixtureCiDebug assembly PASS; consumer lint PASS.
- Device: replacement-installed consumerDebug with `adb install -r -d` semantics; app launched on `R3CY30QFWLP`, process alive, no recent fatal exception or ANR observed.
- Remaining: broader model-backed 5k/20k acceptance and other historical semantic identity surfaces remain unverified.

## 2026-08-05 - People indexing lease recovery

- Added an explicit `PEOPLE` recovery pipeline and included only expired `FACES` leases in its recovery scope; global startup recovery now also covers expired People claims without reclaiming live embedding claims.
- Face indexing now uses atomic owner leases, delayed retry eligibility, three-attempt exhaustion behavior, completion fencing, and lease cleanup on success/failure.
- Process-death recovery preserves the existing reviewed People assignments, vectors, gallery rows, and consent; no Room schema or destructive migration was introduced.
- Focused JVM reliability test: PASS. Connected `PeopleIndexRecoveryDatabaseTest` on `SM-F966B`: PASS; expired People lease returned to `PENDING` while the live embedding lease remained `RUNNING`.
- Remaining: broader model-backed 5k/20k throughput and long screen-off recovery observation remain unverified.

## 2026-08-05 - Scope direct Gemma coverage correctly

- Corrected the home coverage metric to count only `MEDIA`-scoped semantic facts; event, visual-group, and other contextual records no longer inflate direct image coverage.
- Renamed the metric to `Direct Gemma fact coverage` and labels it as media-scoped evidence.
- Connected `SemanticEnrichmentDatabaseTest`: PASS, including event-only coverage `0` and media-scoped coverage `1`.
- No records were deleted or migrated; this changes reporting only.

## 2026-08-05 - Fence stale People failure updates

- Wrapped People face failure ownership checks and stage updates in one SQLite transaction, preventing an expired worker from overwriting a reclaimed lease.
- Added `PeopleIndexLeaseFenceDatabaseTest`: PASS on `SM-F966B`; recovered and newly claimed face work remained `RUNNING` after the stale owner reported failure.
- No migration or destructive data operation was introduced.

## 2026-08-05 - Keep event context out of item predicates

- Semantic-only searches no longer treat every event member as a lexical predicate hit when no lexical terms exist.
- Event expansion is filtered to media with item-level lexical, image-semantic, caption, or caption-embedding evidence; event summary/grouped event queries retain intentional contextual expansion.
- Follow-up refinement now uses the same filtered event member set.
- Added `EventExpansionPolicyTest`: PASS for semantic-only filtering and event-summary expansion.

## 2026-08-05 - Indexing recovery acceptance fix

- Recovered all media-analysis stages (`THUMBNAIL`, `OCR`, `EVENTS`, `ENRICHMENT`) by pipeline, reset orphaned parent items, and added progress-lease renewal after worker checkpoints.
- Recovered stale `RUNNING` rows even when legacy claim metadata was missing; live WorkManager chains remain protected by progress heartbeats.
- Marked allowlisted OCR document answers exact only when the selected deterministic fact and complete eligible coverage support it.
- Validation: `IndexingReliabilityPolicyTest`, `RetrievalExactnessPolicyTest`, `consumerDebug`, `offlineDemoDebug`, and `fixtureCiDebug` passed; preserved 83-item device corpus passed 11/11 executable Q01-Q13 cases, with 2 People cases honestly skipped because face consent/model coverage was disabled.

## 2026-08-05 - Restore redacted OCR search projection

- Fixed the post-encryption FTS regression: media OCR search now indexes the classifier-redacted projection instead of the Keystore ciphertext.
- Advanced the idempotent sensitive-data backfill marker to version 5 and rebuilt `media_fts` once for existing rows; raw OCR remains protected in the database.
- Added a regression test proving searchable labels remain while credential values are excluded.

## 2026-08-05 - Fence stale semantic workers

- Semantic enrichment completion and failure updates are now bound to the claimed lease owner and an unexpired lease.
- Completion changes the job state and derived evidence in one transaction, so a reclaimed worker cannot overwrite a newer attempt.
- Added connected database coverage for stale-owner completion/failure rejection; no migration or data deletion was introduced.

## 2026-08-05 - Keep person chunks out of contextual captions

- Caption chunk generation now rejects person-bound facts for event and visual-group captions even when generation, model, and evidence IDs match.
- Media, query-verification, and verified exact-duplicate scopes retain cluster-bound chunks; contextual captions remain candidate-only.
- Added a regression test covering event captions with a same-generation person action.

## 2026-08-05 - Name complete predicate scans truthfully

- Replaced the ambiguous `COMPLETE_MODEL_SCAN` runtime exactness with `COMPLETE_PREDICATE_SCAN`.
- Added a non-destructive Room 23-to-24 migration that rewrites only historical result-set exactness labels; media, indexes, People data, and models are untouched.
- Added migration coverage for preserving the result set while renaming its exactness value.
### 2026-08-05 - Fence caption-vector leases

- Caption-vector completion and failure now require the current lease owner and producer version, preventing stale workers from overwriting reclaimed chunks.
- Missing verified retrieval packs now produce explicit WorkManager retry state instead of a successful empty embedding run.
- Added a temporary-database instrumentation regression for stale-owner completion and failure.

### 2026-08-05 - Report lexical FTS failures

- Media FTS lookup now returns a typed lexical result; empty terms are `NOT_REQUIRED` and corrupt/unavailable FTS is `FAILED` rather than a successful empty set.
- Search channel reports preserve the failure code while metadata scoring remains available as a partial fallback.
- Added isolated database coverage for a corrupt FTS table.

### 2026-08-05 - Keep contextual captions out of direct coverage

- Direct caption coverage now excludes event and visual-group representative captions; only media, query-verification, and verified exact-duplicate scopes count.
- Added connected database coverage proving an event caption cannot inflate an individual media caption count.

### 2026-08-05 - Treat empty caption searches as not required

- Caption-vector retrieval now short-circuits blank queries as `NOT_REQUIRED` before model loading; real queries still expose a missing retrieval pack as `UNAVAILABLE`.
- Duplicate and whitespace-only query variants are removed before embedding; unavailable model packs are reported only for searches that actually require the channel.

### 2026-08-05 - Surface caption FTS failures

- Caption lexical retrieval now returns typed `SUCCESS`, `PARTIAL`, `FAILED`, or `NOT_REQUIRED` status.
- FTS corruption or query failure is no longer reported as a successful empty channel; the legacy caption fallback is explicitly marked partial.

### 2026-08-05 - Fence exact-duplicate provenance

- Direct caption evidence now requires explicit exact-duplicate applicability.
- Caption and chunk expansion only targets visual groups whose persisted kind is `EXACT_DUPLICATE`; perceptual/burst groups remain contextual.

### 2026-08-05 - Avoid repeated caption openings

- Activity-aware caption composition now compares the first two sentences with the scene summary using normalized, inflection-tolerant tokens.
- Prepended summaries are sentence-bounded and never truncated in the middle of a sentence.

### 2026-08-05 - Fail closed on missing activity state

- Typed activity, action, and interaction facts now require an explicit `activityState=OBSERVED` value.
- Missing or malformed state preserves safe scene/image-subject facts but cannot create observed activity claims.

### 2026-08-05 - Order People thumbnails by latest media

- People cluster sample media and supporting thumbnails now use capture/modified timestamps instead of media-ID ordering or face quality.
- Explicitly selected representatives remain visible without displacing the latest thumbnail ordering.


### 2026-08-05 - Remove unreachable legacy answer path

- Deleted the unreachable repository answer switch after `CapabilityAnswerExecutor` so planner-visible capabilities have one active executor and no stale receipt-only fallback can be restored accidentally.

### 2026-08-05 - Enable release shrinking with runtime keep rules

- Consumer release now enables R8 and resource shrinking.
- JNI vector scanning and typed local model entry points have explicit keep rules; release assembly is required to validate the configuration.

### 2026-08-05 - Correct personal semantic progress coverage

- Progress without an active model version now counts current personal jobs by their durable prefix and excludes superseded generations instead of reporting zero pending work.
- Connected UI tests now wait for asynchronous navigation and assert that debug seeder services remain non-exported and signature-protected.

### 2026-08-05 - Use explicit foreground indexing service types

- Initial gallery indexing now calls the platform typed `startForeground` API, using `mediaProcessing` on Android 15+ and `dataSync` on older supported releases.
- The instrumentation contract now verifies the active service type on the connected API 36 device.

### 2026-08-05 - Make model acceptance prerequisites explicit

- SFace, PaddleOCR, and SigLIP2 connected acceptance tests now skip before expensive model work when their licensed/CC0 fixtures or verified packs are not retained on the device.
- The SFace settings test now verifies automatic model provisioning and guards against removed replacement controls instead of requiring obsolete UI.

### 2026-08-05 - Connected validation after acceptance-test fix

- Consumer debug and its instrumentation APK were replacement-installed with `adb install -r -d` without clearing app data.
- The focused settings, personal-progress, and smoke tests passed; the full connected suite completed 73 tests with no assertion failures.
- Tests requiring `galleryRunId` or device-retained OCR/face/SigLIP2 fixtures were reported as explicit skips, not passes.

### 2026-08-05 - Persist video-keyframe embedding failures

- Room v24-to-v25 adds per-keyframe embedding state, attempt count, retry time, and bounded error text; existing completed keyframe embeddings migrate to `COMPLETE`.
- Keyframe embedding now isolates decode, encoder, and vector-write failures per frame, quarantines exhausted frames, and includes delayed frame retries in truthful scheduling.
- Migration, app-launch/search, and bundled SigLIP2 validation passed on the connected device; the seeded video acceptance remained an explicit `galleryRunId` skip.

### 2026-08-05 - Format event evidence dates

- Repository event evidence now renders localized date-time ranges instead of exposing raw epoch milliseconds in search evidence.
- Consumer unit tests, replacement installation, and grounded local-search smoke validation completed successfully.

### 2026-08-05 - Validate offline and release gates

- `offlineDemoDebug` assembles successfully and its merged manifest contains no `android.permission.INTERNET`.
- `consumerRelease` assembles with R8/resource shrinking enabled; existing Kotlin-metadata warnings remain non-fatal.
- The supplied review’s remaining physical 5k/20k workload and external-fixture model tests remain unverified or explicitly skipped because no `galleryRunId`/retained fixtures were supplied.

### 2026-08-05 - Validate model-free CI variant

- `fixtureCiDebug` unit tests and APK assembly pass without private Gemma, OCR, SFace, or retrieval model artifacts.
### Scoped People channel coverage

- `GalleryRepository` now builds the People coverage universe before the People filter and reports `PARTIAL` until every query-eligible image has a terminal face-stage result.
- Deterministic People answers now inherit partial exactness during an incomplete face scan instead of treating known reviewed-cluster hits as complete coverage.
- Added database coverage regression assertions without changing the schema or existing derived People data.
### Deterministic comparison and list scopes

- Added typed `comparisonScopes` to the validated planner contract so `Goa` and `Singapore` are retained together instead of one becoming a hard filter.
- Compare now builds complete per-scope deterministic evidence; offline LIST plans now support complete place, day, merchant, and reviewed-person value extraction without relying on ranked top-K.
- Added codec, compiler, and executor regression tests; no schema or media-data migration was introduced.
- Complete term-free LIST and explicit two-scope COMPARE answers now report `EXACT` only when the eligible local coverage is complete; bounded semantic retrieval remains estimated.
- SUM and MIN/MAX fallback plans now use the complete allowlisted OCR fact set without a semantic predicate pass or top-K arithmetic.

### 2026-08-05 - SFace settings disclosure
- Kept the pinned OpenCV SFace model name and version visible when the verified pack is not installed; installation state is shown separately.
- Validation: `SFaceSettingsUiTest` PASS on SM-F731U; consumer unit suite and `assembleConsumerDebug` PASS; replacement install PASS.
- The broad connected CI report remains limited by missing private SFace/SigLIP2 artifacts; those acceptance cases are not marked PASS.

### 2026-08-05 - Empty capability executor routing
- Empty non-visual capability results now reach their typed executor; visual search and post-verification failures remain fail-closed.
- Validation: focused capability/exactness tests PASS; full `testConsumerDebugUnitTest` PASS; `assembleConsumerDebug` PASS.

### 2026-08-05 - Deterministic Gemma list plans
- Sanitized list-structural planner terms and hard-place duplicates so `LIST` scope queries remain deterministic; meaningful filters remain searchable.
- Validation: Gemma plan, query compiler, capability tests, and consumer assemble PASS.

### 2026-08-05 - Complete metadata count path
- Metadata-only counts now use the complete eligible set instead of ranked top-K; deterministic aggregation remains exact even when no compatible numeric facts exist.
- Validation: focused tests, full `testConsumerDebugUnitTest`, and `assembleConsumerDebug` PASS.

## Shared Gemma session ownership

- `LiteRtLmQueryPlanner` now requires the application-owned `GemmaSessionManager`; it no longer constructs a private session from an `InferenceResourceManager`.
- The real Gemma planner acceptance test uses `context.services.gemmaSessions`, matching production planner, verifier, composer, and enrichment ownership.
- This prevents accidental duplicate Gemma initialization while retaining model generation replacement and memory-pressure eviction behavior.

## Typed follow-up planning

- Follow-up planning now passes the active conversation state and previous validated plan summary into the on-device planner.
- The validated planner schema accepts an app-checked boolean `followUp` decision; result-set IDs remain app-owned and are never emitted by Gemma.
- Existing language/prefix heuristics remain the deterministic fallback when the model omits the field, while standalone requests can explicitly remain gallery-wide.
- Added JVM coverage for contextual utterances without fixed prefixes and for standalone requests after an active result set.

## Reveal encrypted semantic fact values at the database boundary

- Semantic facts are stored with the Keystore envelope and all three read paths now reveal the value before constructing `SemanticFactRecord`.
- This restores cached Gemma fact display, semantic evidence text, and deterministic fact matching without exposing plaintext at rest.
- The existing temporary-database semantic-enrichment instrumentation test covers the round trip.

## Compose grounded text for factual and document answers

- Grounded answer composition now also covers `ANSWER_FACT`, `DOCUMENT_QA`, `SUM`, and `MIN_MAX` plans when the verified model pack is installed.
- Ordinary media search remains deterministic unless person/query verification is applied, avoiding an extra Gemma call for every image search.
- Composer failure still falls back to the deterministic, evidence-backed answer rather than fabricating a result.

## Gate financial OCR behind the existing sensitive-evidence boundary

- Receipt totals and extracted amounts are now high-risk OCR fields, encrypted with the existing Keystore envelope and included in migration version 6 for existing rows.
- FTS retains safe labels such as `receipt total` while redacting currency values; financial evidence requires device authentication before answer composition or display.
- Deterministic sums and min/max remain available through the existing authenticated evidence path; no arithmetic is delegated to Gemma.

## Protect semantic fact values at rest

- `semantic_fact.value` is now written through the Keystore envelope and migration version 7 upgrades existing plaintext fact rows in place.
- Reads continue to reveal values only at the repository boundary, preserving semantic matching and evidence display without storing plaintext in SQLite.
- A connected temporary-database test covers both new writes and the legacy-row migration path.

## Protect comprehensive caption text at rest

- Stored comprehensive captions now use the same Keystore envelope through migration version 8; caption chunks remain the separate searchable projection.
- Caption retrieval, evidence display, and deterministic chunk generation continue to receive plaintext only after the repository read boundary.

### Runtime indexing snapshot progress

- WorkManager-backed pipeline snapshots now consume durable worker progress for last progress time, next retry time, delayed retries, quarantined items, and in-flight counts.
- Media analysis, image embeddings, People, semantic memory, and caption-vector workers publish the common progress fields without changing their lease or retry policies.
- Missing legacy progress remains unknown rather than being presented as a complete or delayed scan.
- The model-free fixture build and real-model paths retain the same production validation boundaries.

### People indexing coverage correction

- People runtime status now uses the complete accessible-ready image universe and completed FACES stages, rather than all discovered media.
- Pending face work is clamped to that eligible universe, and the remaining eligible face-stage rows are reported as failures instead of being presented as completed.
- No Room migration or destructive data operation was introduced.

### Caption-vector pipeline control

- Caption-chunk embedding is now exposed as an independent indexing job with its own persisted toggle, runtime snapshot, retry/in-flight status, supervisor scheduling, and UI row.
- Existing installs default the new control to enabled; image-vector and caption-vector workers can now be stopped independently.
- Caption-vector availability still requires the verified SigLIP2 retrieval pack, and no caption, image vector, Gemma fact, or Room data is deleted by the control change.
- Required caption-vector searches now report `PARTIAL` when eligible media have no usable caption chunks, instead of silently reporting `NOT_REQUIRED` with empty hits.
- People indexing now treats mixed face-item success as progress, schedules delayed retryable face stages at their durable `next_attempt_at`, and uses WorkManager retry only for zero-progress/systemic stoppage.
- Caption chunks now preserve fact-level applicability, so possible occasions remain uncertain candidate evidence instead of becoming direct media facts.
- Pending semantic jobs now remain retryable when no verified multimodal Gemma pack is available; the worker reports `UNAVAILABLE` instead of completing successfully.
- Person action and appearance facts now fail closed when required body regions are cropped, face-only, occluded, or ambiguously associated; only visible, confident facts can produce confirming chunks.
### 2026-08-05 continuation correctness fixes

- Caption-vector maintenance now completes truthfully when no verified retrieval pack exists and there is no pending backfill; pending work remains unavailable and retryable instead of being reported complete.
- Caption chunk backfill now requires matching generation, scope, scope ID, evidence media, model, prompt, and body-region provenance. Legacy captions remain text-only, and contextual captions cannot inherit person facts.
- Gemma scalar placeholder values such as `null`, `undefined`, and `unknown` are rejected before typed semantic facts or chunks are persisted.
- Room v25-to-v26 adds body-region provenance to person visual facts with a data-preserving migration and migration coverage.
- Visual Gemma fact decoding now forces `occasion` and `possible_occasion` records to `POSSIBLE_INFERENCE`, preventing visual occasion text from becoming confirmed media evidence.
- Real Gemma planner acceptance now accounts for shared-session reuse: model-load timing is required at most once across English, Hindi, and Hinglish cases, and the test asserts no repeated Gemma initialization.
- Validation: 254 consumer unit tests passed; `consumerDebug`, `offlineDemoDebug`, and `fixtureCiDebug` assembled successfully. The connected real-Gemma planner gate was first blocked by a per-case load-time assertion, then correctly skipped because the current app-private state has no active verified E2B generation and only an incomplete `e2b.litertlm.part`; no real-model result is claimed.
- Real E2B planner validation found and fixed a model-output edge case: empty/null unfiltered objects now normalize to `TRUE`, while non-empty malformed filter objects remain rejected; regression coverage added.
- Hinglish E2B planner acceptance exposed missing filter discriminators; the typed codec now infers only unambiguous filter shapes and rejects unknown shapes, with regression coverage.
- Real E2B Hinglish planning also exposed `answerMode=LIST`; the typed codec maps this legacy/model alias to `RESULTS_AND_SUMMARY` without changing the validated capability or retrieval semantics.

### 2026-08-05 real E2B acceptance gate
- PASS: Direct connected-device instrumentation compiled English, Hindi, and Hinglish planner outputs without fallback; Hinglish used the bounded repair path and accepted the typed plan.
- PASS: Real Gemma E2B visual verification ran on GPU with one initialization and one vision call for the synthetic relationship fixture; three positive person conditions produced three media-scoped evidence records.
- PASS: Real Gemma E2B grounded-answer composition ran on GPU with one initialization and one generation; two claims were limited to supplied evidence and the no-evidence case did not bypass Gemma.
- FIXED: Planner decoding now accepts empty/null filter wrappers, unambiguous discriminator-free filter shapes, lexical terms wrappers, and the model's LIST answer-mode alias without weakening typed validation.
- FIXED: Person visual prompts expand negative existential clauses to the remaining stable P-labels and keep negative polarity owned by Kotlin; unit coverage verifies the visibility-based verdict contract.
- LIMITATION: The live synthetic verifier gate covers positive visual confirmation; negative-result behavior is covered by deterministic/unit tests because the installed E2B model over-accepted the synthetic negative predicate and the verifier correctly failed closed.
### 2026-08-05 scoped indexing recovery
- FIXED: Foreground indexing and UI restart, criteria, and job-toggle paths now recover only the owning pipeline instead of reclaiming unrelated active leases.
- PASS: Pipeline mapping regression test; no-argument recovery calls remain in Android runtime call sites.
- PASS: consumerDebug, offlineDemoDebug, and fixtureCiDebug assemblies; replacement-installed consumerDebug without data reset.
### 2026-08-05 bounded aggregation truthfulness
- FIXED: SUM and MIN_MAX now refuse exact-looking answers when the eligible scope was only partially evaluated; bounded ranked hits cannot be used as a complete arithmetic source.
- PASS: CapabilityRegistryTest coverage for complete deterministic aggregation and bounded partial aggregation.
- PASS: full consumer unit suite and consumerDebug, offlineDemoDebug, fixtureCiDebug assemblies; replacement-installed consumerDebug without data reset.
### 2026-08-05 non-exact count wording
- FIXED: Every bounded COUNT result now says it is from the current retrieval pass unless coverage is EXACT or a complete predicate scan, including lexical and caption-vector counts.
- PASS: regression coverage for non-semantic bounded counts; all debug variants and replacement install remain successful.
