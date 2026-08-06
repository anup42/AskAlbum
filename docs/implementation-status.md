# Public implementation status

Last reviewed: 2026-08-06

## 2026-08-06 - Deterministic hard screenshot exclusions

- Hard negative screenshot predicates now remove items identified by
  filename, title, album, or tags before lexical/vector top-K ranking.
- Caption and OCR text are deliberately not used for this deterministic
  exclusion; other negative visual predicates still fail closed through the
  existing Kotlin polarity and visual-verification path.
- Added focused policy coverage; no gallery data, vectors, People data,
  captions, or model packs are changed.

## 2026-08-06 - Truthful semantic no-result wording

- Semantic `UNAVAILABLE` and `FAILED` results no longer claim that a bounded
  search found no matches when the required search did not run or produced no
  usable results.
- `PARTIAL` and `SUCCESS` retain bounded top-K wording, with indexed coverage
  shown in the answer detail.
- Added focused regression coverage; no gallery data, vectors, captions,
  People data, or model packs are changed.

## 2026-08-06 - Complete semantic count uses durable scan results

- Explicit complete semantic counts now read the persisted full-scan hit set,
  rather than the bounded top-K preview channel.
- Duplicate media IDs are collapsed before the count is reported; incomplete
  scans remain estimated or partial.
- Added focused JVM regression coverage; no gallery data or vectors are changed.

## 2026-08-06 - Fixture-device acceptance evidence

- On the isolated `fixtureCi` device, People editing, semantic provenance and
  coverage, caption-vector storage, smoke navigation/search, persistent
  follow-up, and the primary gallery shell completed without reported failures.
- The external corpus evaluator was explicitly skipped because no
  `galleryRunId`/seed archive was supplied; it did not run Q01-Q13.
- Bundled SigLIP2 verification was ignored because model-independent variants
  intentionally omit the external archive. Cancellation acceptance was ignored
  because the fixture query completed before an active model call.
- The production device was not launched or modified; its private app state
  remains outside this fixture validation.

## 2026-08-06 - Generic protected OCR field resolution

- Generic `password` and `passcode` questions now resolve to the protected
  password OCR field, not only queries containing the phrase `Wi-Fi password`.
- Missing OCR field selection no longer falls back to the first allowlisted
  field for document QA, preventing an unrelated receipt total from becoming a
  candidate or exact answer.
- Focused query-planner and semantic-count regressions plus consumer,
  offlineDemo, and fixtureCi assemblies completed with zero reported failures.

## 2026-08-06 - Merchant LIST OCR executor

- `LIST` plans now preserve an allowlisted OCR field, allowing merchant lists
  to return distinct merchant values with document evidence instead of media
  titles.
- Empty lexical OCR queries are represented as null and remain valid under the
  typed plan validator.
- Planner and executor regressions plus all three variant assemblies completed
  with zero reported failures or skips.

## 2026-08-06 - Deterministic event LIST execution

- Plain event and occasion list queries now select `Grouping.EVENT` and
  enumerate eligible event memberships even when there is no search term.
- Filtered event lists use the complete successful event channel and preserve
  deterministic exactness instead of returning only ranked media titles.
- Event planner/executor regressions plus all three variant assemblies completed
  with zero reported failures or skips.

## 2026-08-06 - URL/link OCR intent mapping

- Natural-language `link` questions now select the allowlisted URL OCR field
  and execute through deterministic document QA instead of falling through to
  media search.
- URL, event-list, and merchant-list regressions plus all three variant
  assemblies completed with zero reported failures or skips.

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

## 2026-08-05 - Connected-test safety correction

- The model-backed planner executed on the primary consumer device after the
  verified Gemma E2B pack finished downloading. The grounded-answer test
  exposed an incorrect assertion: a zero incremental model-load time is valid
  when the shared Gemma session was already initialized. The acceptance test
  now checks reuse and generation instead of requiring a second initialization.
- The Android Gradle configuration now refuses connected instrumentation against
  the production `consumer` or `offlineDemo` package unless
  `-PallowProductionDeviceTests=true` is explicitly supplied. Routine connected
  tests must use the isolated `fixtureCi` application ID or a disposable device.
- A connected consumer test run performed before this guard was added removed
  the primary package during instrumentation cleanup. The APK was restored with
  replacement-install semantics, but the old app-private database, indexes,
  People data, and model files could not be recovered. Device gallery media was
  not targeted. The primary consumer installation is therefore a fresh baseline
  and must not be described as preserving the prior private state.
- The complete `fixtureCi` JVM suite, both debug variant builds, and the
  isolated fixture smoke test completed successfully. The full production
  connected acceptance suite remains unverified on the restored primary device.

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
### 2026-08-05 enforce scoped recovery API
- FIXED: Debug seeder recovery now scopes Media Analysis and Embeddings independently; recovery verification scopes only Media Analysis.
- HARDENED: Repository and database recovery APIs no longer default to `ALL`, preventing future callers from silently reclaiming unrelated pipeline leases.
- PASS: repository-wide no-argument recovery scan; targeted recovery test; all debug assemblies; replacement install without data reset.

### Foreground status/cancellation correction (2026-08-05)
- Foreground media and embedding runs now remain visibly `RUNNING` even after their WorkManager records are cancelled for foreground ownership.
- User cancellation no longer gets converted into a failure that silently re-enqueues background indexing.
- Foreground notifications show media/vector count progress after gallery discovery instead of remaining indeterminate.
- Verified with `IndexingWorkProgressTest` and `consumerDebug` assembly; replacement device install and launch smoke check passed without changing app data.

## 2026-08-05 - Durable foreground pause and resume

- Added a persisted foreground pause control that blocks all indexing scheduler admission without changing completed media, vectors, People data, semantic facts, or model state.
- Added `PAUSED_BY_USER` runtime reporting, notification Pause/Resume/Stop actions, and cancellation of all background indexing work before publishing the paused notification.
- Added focused state coverage for user pause; `consumerDebug`, `offlineDemoDebug`, and `fixtureCiDebug` assemble successfully; consumer lint passes; offlineDemo has no INTERNET permission.
- Replacement-installed consumerDebug on the connected device with `adb install -r -d`; first-install time remained unchanged and the demo index remained 14/14.
- Direct shell service-action validation was not available because the service is intentionally non-exported and the device had no pending indexing work; app-owned notification action execution remains unverified on-device.

## 2026-08-05 - Indexing rate and ETA reporting

- Added optional rate-per-minute and ETA fields to typed indexing progress snapshots, preserving null when a worker has no estimate.
- Foreground indexing notifications now identify the active media/vector lane and show a bounded rate and estimated remaining duration when enough progress exists.
- `IndexingWorkProgressTest` covers parsing estimates; all debug variants, consumer lint, offline permission validation, replacement install, and launch smoke passed.

## 2026-08-05 - Publish worker rate and ETA estimates

- Added one shared bounded estimator and published optional rate/ETA fields from media analysis, SigLIP2, People, caption-vector, and Gemma semantic-memory progress payloads.
- Estimates use completed work and current pending counts; zero-progress and completed queues intentionally expose no invented rate or ETA.
- Focused progress tests, all debug variants, consumer lint, offline INTERNET validation, replacement install, and prior launch smoke are PASS; no Room migration or index data mutation was performed.

### 2026-08-05 - JSON boundary hardening and deployment gate
- Added typed JSON string decoding for optional metadata, tags, and semantic attribute arrays; null, non-string, empty, and placeholder values are omitted instead of persisted as text.
- `JsonValuePolicyTest` PASS; `consumerDebug` and `fixtureCiDebug` compilation PASS.

### 2026-08-06 - Protect direct sensitive LIST answers
- Hardened `CapabilityAnswerExecutor` so an explicit `LIST` request for a sensitive allowlisted OCR field is locked before protected values can be formatted into answer text, even when the executor is called directly.
- Added regression coverage for password evidence; no Room migration, data repair, or index mutation was introduced.
- `CapabilityRegistryTest` PASS; `consumerDebug` and `offlineDemoDebug` assembly PASS.
- Fresh offline manifest permission scan was NOT RUN because this AGP layout did not emit the expected merged-manifest path; device validation was NOT RUN.

### 2026-08-06 - Preserve event predicate semantic failures
- Replaced the auxiliary event-expansion semantic `runCatching(...).getOrDefault(emptySet())` path with a typed `EVENT_PREDICATE_SEMANTIC` channel report; failures and partial vector coverage remain visible while contextual expansion fails closed.
- Cancellation is no longer converted into an empty event candidate set; the primary semantic channel and exactness calculation remain independent.
- Focused retrieval tests, consumer/offlineDemo assemblies, and device validation status are recorded with this change after the gates complete.
- `consumerDebug` and `fixtureCiDebug` APK assembly PASS; replacement install and launch smoke PASS on `SM-F731U`.
- `fixtureCiDebug` INTERNET permission: ABSENT.
- Long-running 5k/20k, Doze, process-death, foreground-service-timeout, E4B, and full acceptance-query gates remain unverified.

### 2026-08-06 - Re-expand reviewed identities after face moves
- Files changed: `android/app/src/main/java/io/github/askalbum/GalleryRepository.kt`.
- Moving a face into an existing reviewed cluster now schedules the same asynchronous identity expansion used by tagging and merging, while retaining personal semantic-memory invalidation.
- The revised cluster can therefore discover additional matching media without resetting completed indexes or People corrections.
- Tests and build validation: NOT RUN in this phase.
- Device validation: NOT RUN in this phase.

### 2026-08-06 - Preserve possible-inference uncertainty in answer headers
- `GroundedAnswerCodec` now validates headline and detail text against possible-inference evidence, preventing a definitive occasion header when only uncertain visual evidence exists.
- Added a regression test covering a certain headline/detail paired with an uncertain claim.
- Existing claim-level uncertainty validation remains unchanged.

### 2026-08-06 - Keep multi-clause semantic counts estimated
- The resumable exact semantic scan now rejects plans with multiple positive semantic clauses because its executor evaluates one embedding predicate, not a deterministic conjunction.
- Added a regression test ensuring multi-condition counts remain estimated until a conjunction scan executor exists.

### 2026-08-06 - Lock sensitive fact answers before formatting
- `ANSWER_FACT` and `DOCUMENT_QA` now use the same device-authentication lock as sensitive OCR `LIST` answers before exposing a password or other sensitive allowlisted fact.
- Added direct-executor coverage proving raw password text is absent from the locked answer.

### 2026-08-06 - Bind event wording to event-channel coverage
- `CapabilityAnswerContext` now carries the repository’s event-channel completion state into the executor.
- Event summaries and timelines no longer claim complete event membership from generic `EXACT` result-set coverage.
- Added executor coverage for incomplete and complete event-channel states.

### 2026-08-06 - Prioritize user-requested personal captions
- User-requested semantic enrichment now replaces a stale queued semantic WorkRequest so charging/idle constraints from representative backlog work cannot delay newly tagged Me/family captions.
- Non-user-requested background enrichment continues to use `KEEP`, preserving healthy existing work.
- Added scheduling-policy unit coverage; durable job leases and database priority remain authoritative.

### 2026-08-05 - Semantic scan and caption-vector completion hardening
- Complete predicate scans now consume typed vector coverage and do not advance a batch on unavailable, failed, partial, or missing-vector results.
- Caption-vector reconciliation failures are reported as `FAILED` and retried; they cannot be hidden behind `COMPLETE`.
- Tests: semantic scan batch policy and caption reconciliation policy PASS; `consumerDebug` and `fixtureCiDebug` compilation PASS.
- Build/device: both APKs assembled PASS; consumer replacement install and launch smoke PASS on `SM-F731U`; fixture INTERNET permission ABSENT.
- Long-running 5k/20k, Doze, process-death, foreground-service-timeout, E4B, and full acceptance-query gates remain unverified.

### 2026-08-05 - People cancellation recovery
- People indexing now propagates coroutine cancellation instead of converting WorkManager stops into per-item failures.
- `PeopleIndexWorkerPolicyTest` PASS; `consumerDebug` and `fixtureCiDebug` compilation PASS.
- Both APKs assembled PASS; consumer replacement install and launch smoke PASS on `SM-F731U`; fixture INTERNET permission ABSENT.
- Process-death, screen-off/Doze, six-hour foreground-service timeout, 5k/20k workload, E4B, and full acceptance-query gates remain unverified.

### 2026-08-05 - Lease recovery correctness
- Normal pipeline recovery now reclaims only expired durable leases; stale `updated_at` or `last_progress_at` no longer interrupts a live slow item.
- Explicit startup orphan recovery remains available only when the owning WorkManager pipeline has no active work.
- `IndexingLeaseRecoveryPolicyTest` PASS; `consumerDebug` and `fixtureCiDebug` compilation PASS.
- Both APKs assembled PASS; consumer replacement install and launch smoke PASS on `SM-F731U`; fixture INTERNET permission ABSENT.
- Process-death, screen-off/Doze, foreground-service-timeout, 5k/20k workload, E4B, and full acceptance-query gates remain unverified.

## 2026-08-05 - Deterministic answer evidence closure

- Fixed capability answers so deterministic hits used for LIST, OCR fact selection, aggregation, event/timeline summaries, and comparison also supply the returned evidence IDs.
- Added `CapabilityEvidenceClosureTest` to prevent answers from being computed from one hit set while exposing evidence from another.
- Tests: focused evidence-closure test PASS; full `consumerDebug` unit suite PASS; `git diff --check` PASS.
- Builds: `consumerDebug` PASS; `fixtureCiDebug` PASS. Fixture APK declares no `INTERNET` permission.
- Device: replacement-installed `consumerDebug` with `adb install -r -d`; package `firstInstallTime` unchanged; `MainActivity` resumed; no recent fatal/ANR match.
- Remaining: complete device acceptance queries, process-death/Doze/FGS-timeout tests, and 5k/20k workload gates remain NOT RUN.

## 2026-08-05 - OCR channel coverage truthfulness

- OCR retrieval coverage now comes from durable `media_index_stage` OCR states, not generic media readiness. `COMPLETE` and `SKIPPED` are covered; pending, running, and failed stages remain uncovered.
- `ANSWER_FACT`, `DOCUMENT_QA`, `SUM`, and `MIN_MAX` now require the OCR channel. Missing model coverage reports `UNAVAILABLE`; incomplete stage coverage reports `PARTIAL` with an explicit error code.
- Tests: `OcrChannelCoveragePolicyTest` PASS; full `testConsumerDebugUnitTest` PASS; consumer lint PASS; `git diff --check` pending final staging check.
- Builds: `consumerDebug`, `offlineDemoDebug`, and `fixtureCiDebug` PASS. Offline variant has no `INTERNET` permission.
- Device: replacement-installed consumer APK with unchanged `firstInstallTime`; `MainActivity` resumed; no recent fatal/ANR match. A model-backed OCR query was not run.
- Remaining: process-death/Doze/FGS-timeout, 5k/20k workload, and full acceptance-query gates remain NOT RUN.

## 2026-08-05 - Deterministic OCR fact answers

- `ANSWER_FACT` and `DOCUMENT_QA` now build candidates from the complete hard-filtered eligible OCR entity set, so a valid field below ranked top-K is not silently missed.
- `SUM` and `MIN_MAX` exactness now also depends on complete OCR-stage coverage; selected document facts retain media-bound evidence and existing sensitive-evidence authentication.
- Tests: focused document-fact and evidence-closure tests PASS; full `testConsumerDebugUnitTest` PASS; consumer lint PASS; `git diff --check` PASS.
- Builds: `consumerDebug`, `offlineDemoDebug`, and `fixtureCiDebug` PASS. No migration or destructive data change was introduced.
- Device: replacement-installed consumer APK; `firstInstallTime` unchanged; `MainActivity` resumed; no recent fatal/ANR match. A real OCR query remains unverified on-device.
- Remaining: process-death/Doze/FGS-timeout, 5k/20k workload, and full acceptance-query gates remain NOT RUN.
## 2026-08-05 - Person visual verification cannot be disabled by planner output

- Fixed the runtime verification policy so a semantic clause with `PERSON` subject or a reviewed-person binding always requires targeted visual verification, even if planner output requests `VerificationPolicy.NEVER`.
- Preserved `NEVER` for ordinary non-person searches and added regression coverage for both cases.
- This closes a fail-open path where face presence or caption retrieval could otherwise confirm a person-specific clothing, action, or relation predicate without body association verification.
## 2026-08-05 - Deterministic answer evidence remains authentication-protected

- Fixed answer-level sensitive-evidence detection to inspect both ranked hits and the complete deterministic evidence set used for OCR facts and aggregations.
- Added regression coverage proving a password present only in deterministic answer evidence still requires authentication before the answer is returned.
- No database migration or data rewrite was required.
## 2026-08-05 - Event expansion now reports and requires real coverage

- Event, timeline, and event-group comparison expansion now uses deterministic scope evidence only when the eligible media set has complete `EVENTS` stage coverage.
- Partial or missing event indexing reports `PARTIAL` or `UNAVAILABLE`, prevents complete-scope wording, and leaves the answer on the bounded retrieval path.
- Ordinary non-event queries no longer require event coverage when no event candidate was found.
- Added policy tests for partial, unavailable, and not-required event coverage.
## 2026-08-05 - Align protected-branch CI check with model-free variants

- Named the Android workflow job `Fixture tests and offline build` to match the protected AskAlbum `main` required status check.
- Updated CI to run `fixtureCiDebug` unit tests and assembly plus `offlineDemoDebug` assembly, and to verify both generated APK manifests for Internet permission.
- This is a CI-only change; production model validation and app data are unchanged.
## 2026-08-05 - Bind every person visual condition to a reviewed visible face

- Visual verification now includes all `relationToPerson` identities when loading candidate face bindings, even if the plan omitted a redundant `peopleClause`.
- A person-specific condition fails closed unless its cluster ID or alias resolves to exactly one reviewed, visible face in that media item.
- Added alias, missing-binding, and ambiguous-binding regression tests; no migration or media data was changed.
## 2026-08-05 - Preserve the planner distinction between terms and predicates

- `GemmaPlanCodec` no longer converts ordinary lexical `terms` into `semanticClauses` when the model correctly returns no structural predicates.
- Original-query, lexical, concept, and caption retrieval remain available through `terms`; relational and fine-grained clauses remain model-supplied typed predicates.
- Numeric OCR aggregation plans now retain deterministic execution semantics instead of being misclassified as bounded semantic work.
- Added codec regressions for ordinary search and SUM plans.

## 2026-08-05 - Correct deterministic document ordering and duplicate aggregation

- `ANSWER_FACT` and `DOCUMENT_QA` now apply the validated plan sort before selecting the first document, so `latest` cannot depend on incidental input order and still fails closed when that document lacks the requested field.
- Numeric `SUM`, `MIN`, and `MAX` collapse only media rows with the same verified exact-content digest; rows without a digest remain distinct.
- Added regressions for newest-document selection and exact-duplicate aggregation. No migration or destructive data change was introduced.

## 2026-08-05 - Normalize reviewed-person identity lookup consistently

- Reviewed-person media filtering and group resolution now use one NFKC, whitespace-normalized, case-insensitive identity representation.
- Token-boundary matching remains Unicode-aware, so Hindi, Hinglish, decomposed accents, and compatibility-width aliases resolve without substring false positives.
- Added regression coverage; no database migration or People-data rewrite was introduced.

## 2026-08-05 - Preserve the winning video-keyframe timestamp

- Visual verification and video evidence playback now prioritize the semantic image-text keyframe timestamp, then lexical keyframe/OCR timestamps, instead of choosing the first or earliest unrelated timestamp in a fused hit.
- Parent videos remain the returned media item while verification and playback use the matched frame time.
- Added timestamp-priority regression coverage; no media, vector, or database data changed.
### 2026-08-05 - Normalize verifier-side reviewed-person binding

- Reused the Unicode-safe reviewed-person normalization contract in both Gemma visual-verification identity checks.
- Added regression coverage for decomposed accents and full-width aliases so multilingual person conditions fail closed only on genuine ambiguity.
### 2026-08-05 - Make bounded retrieval coverage explicit

- Semantic no-result wording now distinguishes indexed coverage from bounded top-K retrieval.
- Retrieval coverage UI labels vector channels as indexed and bounded instead of implying exhaustive evaluation.
- Added regression coverage for truthful bounded semantic wording.
### 2026-08-05 - Close grounded-answer evidence scope

- Grounded evidence now retains scope, subject, evidence-media, cluster, and applicability provenance.
- Person-conditioned answer composition accepts only same-media visual-verification evidence; event/context evidence is limited to event capabilities.
- Possible-inference claims must preserve uncertainty wording, with regression coverage for cross-media and contextual leakage.
### 2026-08-05 - Separate deterministic OCR amounts from receipt totals

- The existing generic `AMOUNT` OCR entities are now exposed through a distinct allowlisted `amount` field and `document_amount` evidence source.
- `amount paid` remains mapped to the receipt `total` field; generic amount queries now compile to the correct deterministic executor path.
- Added extraction, compiler, allowlist, and deterministic answer regressions. No migration or data rewrite was required.
### 2026-08-05 - Preserve deterministic evidence during grounded answer composition

- Grounded-answer packets now merge ranked and deterministic hits by media ID, retaining OCR, aggregation, event, and ranked evidence together for the optional Gemma wording stage.
- Deterministic answers remain authoritative when composition is unavailable or fails; no model, media, or database migration was required.
- Added a regression proving same-media evidence is not dropped before composition.
### 2026-08-05 - Report exhausted indexing items truthfully

- Media-analysis and SigLIP2 progress now report quarantined item failures instead of hard-coded zero values.
- Checkpoint progress no longer reports a full phantom batch as in-flight after the batch has returned.
- `FAILED_EXHAUSTED` media rows now contribute to degraded pipeline status, so poison items cannot be mistaken for a complete healthy index.
### 2026-08-05 - Stop re-enqueuing exhausted SigLIP2 work

- Index summaries now distinguish SigLIP2 stages that are pending/retryable from stages that are exhausted or permanently failed.
- Runtime status, supervisor scheduling, ViewModel activity, foreground notifications, and worker ETA use that durable coverage instead of `discovered - ready`.
- Caption-vector progress now exposes durable delayed-retry and quarantined counts when its queue is otherwise exhausted.

### 2026-08-05 - Keep verified visual counts non-exhaustive

- A complete semantic predicate scan is no longer reported as exact when bounded visual verification is also required.
- Added regression coverage for person-conditioned or other visual predicates that cannot be evaluated exhaustively by the semantic scan alone.

### 2026-08-05 - Exclude superseded semantic jobs from visible totals

- Semantic-memory global totals now exclude jobs explicitly marked `superseded`, matching the existing personal-job coverage query and preventing stale failures or skipped counts from appearing after a caption-policy/model refresh.
- Added database coverage proving replacement personal jobs do not double-count the superseded generation.
### 2026-08-05 - Preserve WorkManager recovery during foreground indexing
- Foreground media and SigLIP2 indexing no longer cancel their durable WorkManager fallback when the explicit foreground service starts. Background workers yield while the foreground lane is active, then resume through their existing lease/checkpoint path if the service is killed or reaches its platform timeout.
- Service destruction and media-processing timeout now hand off recovery, while explicit pause/stop actions cancel the fallback as requested by the user.
- Added policy coverage for foreground lane exclusion and explicit-stop recovery behavior. Long-running process-death and six-hour device tests remain unverified.
### 2026-08-05 - Seed recovery work before foreground indexing
- Settings-driven foreground indexing now creates the media/vector WorkManager recovery requests immediately after the foreground lane claims ownership, covering starts that had no pre-existing queued worker.
- Added connected coverage proving `startIndexing` leaves a durable `gallery-index` request without clearing app data.
### 2026-08-05 - Add controlled foreground process-death coverage
- Added a connected instrumentation harness that starts the real foreground service, verifies the durable media recovery request, kills only the target app process, and confirms non-cancelled recovery work survives.
- The test cleanup cancels only the two indexing work names and stops the service; it does not uninstall, clear data, reset indexes, or delete device media.
### 2026-08-05 - Add forced-Doze recovery coverage
- Extended the connected recovery harness to force device idle, verify recovery work is not cancelled, unforce idle, and verify the request remains available.
- The test restores device idle state in `finally` and cancels only its two indexing work names.
- The two recovery tests pass on `SM-F966B` and `SM-F731U`.
### 2026-08-05 - Run 5k/20k vector workload gate
- `VectorIndexBenchmarkTest` passed native FP16 vector-store construction and retrieval at 5,000 and 20,000 vectors on both connected devices.
- This validates vector-store scale only; full MediaStore indexing at 5,000/20,000 items and six-hour foreground duration remain separate unverified gates.
### 2026-08-05 - end-to-end 5,000-item MediaStore gate

- PASS: `fixtureCiDebug` and its instrumentation APK built and replacement-installed on `R3CY30QFWLP` under the isolated package `io.github.anup42.askalbum.fixture`.
- PASS: a valid 5,000-item, 320x240 JPEG corpus was adopted, seeded, imported, and indexed through the foreground coordinator. The first pass processed `4,996` media items in `237.7s` with zero retryable or permanent failures; after the two-minute durable-lease recovery window, the corrected report showed `5,000/5,000` rows `READY`, all media stages complete, and no active claims.
- PASS: the report driver now clears stale status files before asynchronous report broadcasts, preventing a superseded `COMPLETE` report from masking current database state.
- PASS: exact run-scoped cleanup removed `5,000/5,000` MediaStore rows and imported database rows; no unrelated media was targeted.
- NOT RUN: full device SigLIP2 vector indexing. The fixture producer is intentionally absent (`vectorProducer=null`), while the consumer device lacked a verified SigLIP2 runtime and produced `0` media-analysis progress with retryable embedding failures; no consumer data was changed and that run was cleaned.
- NOT RUN: 20,000-item full MediaStore indexing and six-hour foreground duration. The synthetic vector-store 5,000/20,000 benchmark remains separate coverage.

### 2026-08-05 - debug corpus operation handoff

- Fixed the debug-only seeded-gallery foreground service handoff so a completed seed operation can queue the following import/index/cleanup action while the prior coroutine releases its lease.
- This prevents a transient `Another test gallery operation is active` result from being reported as a real import failure.
- The corpus driver now removes only the current run's stale operation status before starting a new operation, so superseded failures cannot abort a fresh run.

### 2026-08-05 - truthful foreground embedding availability
- PASS `IndexBatchResult` now distinguishes unavailable embedding producers from an exhausted queue; missing verified retrieval packs report `UNAVAILABLE` with `NO_VERIFIED_RETRIEVAL_PACK`.
- PASS foreground media analysis can finish without being falsely reported as complete for embeddings; the service notification names the unavailable retrieval pack state.
- PASS focused `ForegroundIndexCompletionPolicyTest` and existing foreground run-limit tests.
- PASS `consumerDebug` assembled and replacement-installed on `R3CW408WE4J` with `adb install -r` through the Android build/install workflow; existing app data was preserved.
- NOT RUN full device SigLIP2 indexing because the connected consumer installation does not have a verified external retrieval pack available for this validation.

### 2026-08-05 - truthful runtime status for missing retrieval packs
- PASS indexing snapshots now expose `UNAVAILABLE` and `NO_VERIFIED_RETRIEVAL_PACK` when embeddings are enabled but no verified SigLIP2 producer is active; this is separate from `COMPLETE` and `DEGRADED`.
- PASS explicit user pause remains higher priority than unavailable model state, and the ViewModel does not poll an unavailable pipeline as if it were active work.
- PASS focused indexing-state, reliability, and foreground-completion unit tests.
- PASS `consumerDebug` rebuilt and replacement-installed on `R3CW408WE4J`; `offlineDemoDebug` assembled successfully.
- NOT RUN model-backed vector indexing on device; the connected consumer validation still lacks a verified external retrieval-pack path for that gate.

### 2026-08-05 - reject empty semantic vector coverage
- PASS semantic retrieval now reports `PARTIAL` with `VECTOR_COVERAGE_PARTIAL` when eligible media have no vector IDs or only a covered subset; it no longer treats `0 of 0` vector IDs as `SUCCESS`.
- PASS empty hard-filtered scopes are `NOT_REQUIRED` before model availability checks, avoiding misleading retrieval-pack warnings for a query with no eligible media.
- PASS caption-vector search follows the same empty-scope rule.
- PASS focused retrieval-channel and caption-coverage tests.
- PASS `consumerDebug` rebuilt and replacement-installed on `R3CW408WE4J`; `offlineDemoDebug` assembled successfully.
- NOT RUN model-backed vector search on device because a verified retrieval-pack runtime remains unavailable for the full acceptance gate.

## 2026-08-05 - Fence superseded personal enrichment jobs

- Fixed personal semantic-memory policy replacement so live `RUNNING` jobs retain their lease while being marked superseded; healthy workers are not reclaimed by queue polling.
- Added completion/failure generation fencing so stale callbacks cannot persist captions or facts after a policy replacement.
- Added a regression test covering live lease preservation and stale completion rejection.
- Validation: fixture unit tests PASS; isolated `PersonalSemanticMemoryDatabaseTest` on secondary device PASS; `consumerDebug` and `offlineDemoDebug` builds PASS.
- Primary production connected instrumentation remains intentionally unrun after the documented package/data cleanup incident; no further production-device test was performed.

## 2026-08-06 - Preserve negative visual-verification verdicts

- Fixed query-time person-attribute caching so the verifier's `VERIFIED_FALSE`, `AMBIGUOUS`, or `NOT_VISIBLE` verdict is persisted instead of being hardcoded to `VERIFIED_TRUE`.
- Negative visual predicates therefore cannot become positive person evidence during later retrieval.
- Added an isolated device regression test for the stored verdict.
- Validation: `PeopleIdentityProtectionDeviceTest` PASS on the secondary fixture device; fixture unit tests PASS; `consumerDebug` and `offlineDemoDebug` builds PASS.

## 2026-08-06 - Exclude stale captions from coverage

- Fixed direct caption coverage accounting to exclude `STALE_PERSON_BINDING` captions, matching the existing search, chunk-backfill, and personal-queue behavior.
- Added an isolated database regression proving stale media captions do not count as current caption coverage.
- Validation: `SemanticEnrichmentDatabaseTest` PASS on the secondary fixture device; fixture unit tests PASS; `consumerDebug` and `offlineDemoDebug` builds PASS.

## 2026-08-06 - Count verified exact-duplicate caption coverage

- Fixed caption coverage accounting to expand only verified `EXACT_DUPLICATE` visual-group members for captions explicitly marked `SAFE_FOR_EXACT_DUPLICATES`.
- Representative evidence no longer makes a verified duplicate member appear uncovered, while event and visual-group context remains non-direct coverage.
- Validation: exact-duplicate coverage regression PASS on the secondary fixture device; fixture unit tests PASS; `consumerDebug` and `offlineDemoDebug` builds PASS.

## 2026-08-06 - Channel report evidence closure

- Retrieval channel reports now retain only evidence produced by their own semantic, event, caption, or caption-embedding channel instead of exposing empty `SearchHit` evidence.
- Fixture unit tests and `consumerDebug`/`offlineDemoDebug` assembly passed.
- The fixture connected gate was run on the isolated secondary device and failed on pre-existing absent bundled model assets plus an existing empty-query status assertion; no production acceptance result is claimed.
- Commit: `71c1f94` pushed to `anup42/AskAlbum`.

## 2026-08-06 - Model-free fixture acceptance gate

- Embedded SFace and SigLIP2 asset acceptance tests now skip only when `MODEL_INDEPENDENT` is true; production variants retain the full verification tests.
- Empty caption-vector searches with zero eligible media are asserted as `NOT_REQUIRED`; unavailable remains reserved for required searches lacking verified retrieval coverage.
- Fixture connected tests on `R3CY30QFWLP`, fixture unit tests, `consumerDebug`, and `offlineDemoDebug` passed.
- Commit: `5ca6d95` pushed to `anup42/AskAlbum`.

### 2026-08-06 - Fingerprint exhaustive semantic-scan coverage

- Added the non-destructive Room v26-to-v27 migration with an exact eligible-media vector-coverage fingerprint for durable semantic predicate scans.
- A completed scan is no longer reusable as exhaustive when vectors were removed or replaced, even if the covered-item count is unchanged; dormant scans reset and re-evaluate, while live leases are preserved.
- Validation: fixture unit tests, `consumerDebug`, `offlineDemoDebug`, and the isolated v26-to-v27 migration test on `SM-F966` passed. Existing app data and media were not modified.
- Commit `f0e83ad` was pushed to `anup42/AskAlbum`.

### 2026-08-06 - Repair invalid reviewed-person face vectors

- Reviewed-person expansion now validates that a representative face vector is readable and has the current SFace embedding dimension before reusing it.
- Missing, corrupt, or stale-dimension representative vectors are re-embedded from the source image through the existing on-device face engine; existing valid vectors remain untouched.
- Validation: fixture unit tests and `consumerDebug`/`offlineDemoDebug` assembly passed. Commit `710906f` was pushed to `anup42/AskAlbum`.

## 2026-08-06 - Typed semantic retrieval enforcement

- Removed the legacy hit-only semantic text search API that converted missing or unavailable retrieval into an empty result.
- Updated the stored 5k retrieval acceptance test to consume `searchTextReport` and require `ChannelStatus.SUCCESS` before evaluating hits.
- Validation: `:app:testFixtureCiDebugUnitTest`, `:app:assembleConsumerDebug`, `:app:assembleOfflineDemoDebug`, and `:app:compileFixtureCiDebugAndroidTestKotlin` PASS.
- Published to AskAlbum branch `codex/current-agentic-gallery-sync` as commit `db511be`.

## 2026-08-06 - Resume personal memory after identity expansion

- Reviewed-person expansion now resumes when a cluster is unhidden.
- Automatic face assignments re-queue personal semantic-memory work after database invalidation, closing the race where tagging completed before newly discovered media was captioned.
- Validation: fixture unit tests and `consumerDebug`/`offlineDemoDebug` assembly PASS; `PeopleEditingDatabaseTest` and `PersonalSemanticMemoryDatabaseTest` PASS on secondary fixture device `R3CY30QFWLP`.

## 2026-08-06 - Reject unsupported OCR capability fields

- `ANSWER_FACT`, `DOCUMENT_QA`, `SUM`, and `MIN_MAX` no longer default an unknown or missing requested field to receipt `total`.
- Unsupported fields now return an explicit non-answer, preserving truthful capability behavior.
- Validation: `CapabilityRegistryTest` and `consumerDebug`/`offlineDemoDebug` assembly PASS.
- Published to AskAlbum as commit `b4d8a93` on `codex/current-agentic-gallery-sync`.

## 2026-08-06 - Queue personal memory after Gemma installation

- Successful verified Gemma installation now immediately queues eligible personal semantic-memory media and schedules the existing worker when enabled.
- This closes the same-process gap where tagging occurred before model availability and captions remained at zero until the next app restart.
- Validation: fixture unit tests and `consumerDebug`/`offlineDemoDebug` assembly PASS; live download/install behavior NOT RUN because no model download was performed.
- Published to AskAlbum as commit `4acdafd`.

## 2026-08-06 - Refresh semantic indexing status

- Index-manager polling now refreshes semantic-memory progress together with media, People, and admission state before calculating pipeline snapshots.
- This prevents stale or zero Gemma counts from being shown while durable semantic jobs are running.
- Validation: fixture unit tests and `consumerDebug`/`offlineDemoDebug` assembly PASS; production device validation NOT RUN.
- Published to AskAlbum as commit `acd3881`.
2026-08-06 - Foreground indexing lane admission
- Confirmed the supervisor could still schedule People, semantic enrichment, and caption-vector background work while the explicit media-processing foreground service was active.
- Added a shared supervisor gate and worker-side checks; caption-vector claims are released before retry when the foreground lane takes priority.
- Added a policy regression test. No completed gallery, People, vector, semantic, caption, event, or model data is modified.
2026-08-06 - Grounded text responses for semantic media search
- Added a typed policy that sends non-empty semantic FIND_MEDIA queries through the existing shared Gemma grounded-answer composer when requested output allows a summary.
- RESULTS_ONLY, empty/metadata-only, and unavailable-model paths remain deterministic; no per-image Gemma processing or extra vision pass was added.
- Added three policy regressions. Existing media, indexes, People data, captions, events, and model packs are unchanged.
2026-08-06 - Broadened bounded OCR fact extraction
- Receipt totals now accept a labeled currencyless amount without treating arbitrary numbers as totals.
- ISO dates and common `Wi-Fi password is ...` wording are extracted into the existing allowlisted entity types.
- Added extractor regressions; sensitive values remain protected and no OCR value is sent to Gemma before authentication.### 2026-08-06 - Protect raw sensitive evidence in the viewer
- Search-hit evidence now uses the same allowlist and content classifier as answer gating.
- Sensitive OCR evidence is masked in the evidence viewer until the existing biometric/device-credential unlock completes; non-sensitive metadata remains visible.
- Added a regression for allowlisted password evidence.
### 2026-08-06 - Recycle media-analysis bitmaps on every exit path

`GalleryIndexBatchProcessor` now tracks every decoded video frame, PDF page, and image bitmap as soon as ownership enters the batch. The processor recycles those bitmaps from a single `finally` block even when ML Kit, OCR, decoding, completion, cancellation, or a poison item fails. This prevents repeated media-analysis failures from retaining native pixel buffers and making indexing progressively slower or unstable. No completed index rows or media data are changed.
### 2026-08-06 - Reject partial caption-vector batches

Caption embedding now validates that the text encoder returned exactly one vector per claimed chunk before persisting any result. A cardinality mismatch retries every claimed chunk with a bounded item attempt instead of silently dropping rows through `zip` and leaving them in-flight. Added a regression test; existing captions and image vectors are unchanged.
### 2026-08-06 - Resume every durable pipeline after foreground timeout

Unexpected foreground-service destruction and the Android `mediaProcessing` timeout now hand off media analysis, SigLIP2 vectors, People, caption embeddings, and semantic-memory work to their existing guarded WorkManager schedulers. This preserves durable leases/checkpoints and avoids leaving secondary queues dormant after a long foreground indexing session. No completed data or model state is changed.
### 2026-08-06 - Render the newest People photo without losing representatives

People cluster summaries now expose both the user-selected representative face and the newest face derived from the existing capture-time ordering. The People list card renders the newest face, while the editor and representative controls continue to use the selected representative. Added a database acceptance assertion; no face assignments or corrections are changed.

## 2026-08-06 - Truthful SigLIP2 coverage denominator

- Corrected vector coverage to use the accessible `EMBEDDING` stage population instead of the gallery-wide media count.
- Updated the Index Manager, runtime pipeline snapshot, unavailable-pack gate, and foreground notification to use the same eligible denominator.
- No schema migration or data mutation; existing media, stages, vectors, People data, captions, and models are preserved.
- Regression coverage added for separating discovered media from vector-stage eligibility.

## 2026-08-06 - Correct video-keyframe semantic coverage

- Semantic completeness now tolerates the additional keyframe vector entries associated with an eligible video.
- Missing vector coverage relative to the eligible media scope remains partial, and displayed counts stay in media units.
- Added a regression test for a fully indexed parent video plus keyframe vector.

## 2026-08-06 - Read media-analysis coverage from stage state

- Metadata, OCR, and visual-label coverage now use their durable stage statuses instead of inferring readiness from media state or non-empty tag text.
- A completed media-analysis item with zero detected labels is counted as processed without fabricating a label.
- Added an Android database regression for stage-derived summary counts.

## 2026-08-06 - Preserve exact-scan batch coverage denominators

- Exact semantic-scan batches now report the real number of eligible media when no vector IDs are available instead of a hard-coded count of one.
- The durable scan runner passes its batch size into the typed semantic channel report; zero-eligible scopes remain `NOT_REQUIRED`.
- Added a regression for the 64-item missing-vector batch case. No completed media, vectors, People data, semantic facts, captions, events, or model state is modified.

## 2026-08-06 - Restrict exact-duplicate semantic reuse

- Exact-duplicate caption and semantic-fact reuse now requires explicit `SAFE_FOR_EXACT_DUPLICATES` applicability in addition to the existing normalized-pixel digest and reviewed-face binding checks.
- Media-only evidence, possible inferences, and contextual facts are no longer silently promoted to exact-duplicate truth; those targets remain eligible for direct generation.
- Added a provenance regression. Existing media, captions, facts, People data, vectors, events, and model packs are unchanged.

## 2026-08-06 - Match exact-duplicate person facts to the source generation

- Exact-duplicate reuse now copies only person facts matching the source caption's generation, media, model, prompt, and body-region versions.
- Older person observations on the same source image cannot leak into a newer duplicate caption; the existing caption provenance policy remains the single matching rule.
- No media, People corrections, captions, facts, vectors, events, or model packs were modified.

## 2026-08-06 - Repair legacy shared provenance on current installations

- Added the non-destructive v27-to-v28 migration for already-upgraded databases.
- Shared media facts and captions remain shared only when a distinct source-generation record and matching exact-content digest prove pixel-equivalent reuse; event/group rows become contextual and ambiguous legacy rows become `LEGACY_SCOPE_UNCERTAIN`.
- Affected caption chunks and FTS rows are invalidated for provenance-safe regeneration without deleting captions, facts, media, People data, vectors, events, or models.
- Added an Android migration regression covering valid reuse, ambiguous rows, event scope repair, and chunk invalidation.

## 2026-08-06 - Self-heal missing face vectors

- People worker now compares persisted face-embedding metadata with the face vector index.
- Missing indexed vectors clear only the affected embedding metadata and requeue the owning media FACES stage.
- Existing user-corrected cluster assignments, reviewed identities, media, and other indexes are preserved while SFace regenerates the affected embeddings.
- Added model-free policy tests for missing-vector detection.

## 2026-08-06 - Record typed follow-up removals

- Follow-up patches now compare the previous and replacement plans so removed time, place, people, media, sort, grouping, and semantic constraints are represented as typed REMOVE operations.
- Added coverage for removing a prior place scope while retaining the active result set.
## 2026-08-06 - Preserve direct evidence in grounded-answer packets

- Grounded answer packet construction now prioritizes media-specific visual verification and direct media/query evidence before applying the bounded evidence limit. This prevents a later high-trust record from being omitted behind lower-priority lexical context.
- Added a regression test covering the evidence-limit case.
## 2026-08-06 - Close non-Gemma answer evidence to the cited media

- Capability answer citation collection now rejects evidence whose media ID does not match the cited result and prioritizes direct visual/media evidence before the bounded citation limit.
- Added regression coverage for both cross-media leakage and trusted-evidence truncation.

## 2026-08-06 - Preserve video and spatial evidence in grounded prompts

- Grounded evidence JSON now carries the existing keyframe timestamp and spatial region alongside source, scope, and media provenance.
- Added a regression proving those fields reach the single grounded-answer call without another image encoding pass.

## 2026-08-06 - Refresh People after asynchronous identity expansion

- People cluster summaries now use a lightweight Room revision and refresh only after cluster or face membership changes, including background reviewed-identity expansion.
- Face moves and exclusions update the affected cluster revision without changing media or face-index consent; unchanged polling no longer reloads the full People summary.
- Added policy coverage for skipping unchanged revisions and forcing an explicit refresh.

## 2026-08-06 - Respect accessible gallery scope in People

- People summaries, samples, paged cluster faces, reviewed-person resolution, and reviewed-identity expansion now use only accessible media rows.
- Inaccessible face assignments remain stored for permission restoration but no longer make an unreviewed cluster pass the five-media visibility threshold or appear as an unopenable People image.
- Added an isolated database regression for partial-gallery access.

### 2026-08-06 - Normalize EXIF orientation before visual verification

- `GalleryImageLoader` now applies the same EXIF transform used by gallery thumbnails and People crops before sending decoded media to Gemma verification or semantic enrichment.
- Added an instrumented regression test proving a JPEG tagged with `ORIENTATION_ROTATE_90` is delivered upright.

### 2026-08-06 - Label every visible face during person verification

- Person-conditioned verification now labels all visible faces in its composite. Reviewed identities use deterministic `P*` labels; unreviewed or hidden faces use `U*` context labels without identity terms.
- Required identity checks still resolve only reviewed clusters, and the prompt explicitly prevents `U*` faces from satisfying a requested identity condition.
- Added an isolated database regression test for reviewed versus unreviewed binding provenance.

### 2026-08-06 - Show grounded claims in the active Ask answer card

- The active Compose answer card now displays validated Gemma claims, cited evidence IDs, confidence, and coverage warnings alongside the deterministic headline/detail.
- This closes the user-facing Q&A path without exposing raw sensitive evidence when the answer is authentication-locked.

### 2026-08-06 - Enforce caption search provenance

- Caption FTS and caption-vector eligibility now require each chunk to match its parent caption's scope, scope ID, evidence media, model, prompt, generation, and current chunk policy.
- Vector hit resolution revalidates complete current chunks before turning them into evidence; stale or cross-generation rows cannot surface through an old vector-store entry.
- Added unit coverage for cross-scope, cross-generation, stale-policy, and incomplete-vector rejection.

### 2026-08-06 - Close capability citations to grounded evidence policy

- Capability answer citations now apply the same typed scope, applicability, event, and person-condition policy used by grounded answer composition.
- Ordinary media answers no longer cite visual-group or event context as direct sources; direct media/OCR evidence remains available.
- Added regression coverage for contextual citation exclusion.

### 2026-08-06 - Serialize Gemma idle eviction with active leases

- Gemma engine reuse now cancels and reschedules idle eviction while holding the session mutex, preventing a queued planner, verifier, answer, or enrichment call from being closed by an older request's cleanup.
- Memory-pressure eviction clears the pending idle job under the same mutex; model generation selection and one-heavy-call serialization remain unchanged.

### 2026-08-06 - Preserve Unicode reviewed-person references

- Query-plan validation now accepts bounded Unicode person labels and aliases such as Hindi names while retaining strict validation for OCR fields and structural IDs.
- Unsafe URI, path-separator, control-character, and comment-like person references remain rejected before People resolution or retrieval.
- Added Hindi person-reference validation coverage.
### 2026-08-06 - Make deterministic People fallback fail closed

- Added bounded known-identity detection for planner-free queries, including Unicode family terms and negative polarity.
- Merged planner, deterministic, and reviewed-alias clauses without re-adding a reviewed database match as a positive clause when the query explicitly excludes it.
- Added unit coverage for identity detection, arbitrary-name safety, negative polarity, and alternative-cluster merging.
### 2026-08-06 - Force visual rejection for unsupported negative predicates

- Negative visual clauses now force the bounded Kotlin/Gemma verification path even when a planner requests `NEVER` verification.
- Screenshot exclusions remain deterministic only when the hard metadata predicate is provable.
- Added regression coverage for unsupported negative predicates and deterministic screenshot exclusions.

### 2026-08-06 - Keep unavailable embedding work retryable

- Fixed `EmbeddingIndexWorker` so a missing verified SigLIP2/retrieval pack is not reported as successful completion.
- Propagated batch availability and error-code state into the shared worker-result policy and WorkManager progress.
- Added a regression test; existing media, vectors, People data, semantic facts, and model packs remain unchanged.

### 2026-08-06 - Preserve unavailable status in indexing snapshots

- Decoded worker `status=UNAVAILABLE`, boolean availability, and error codes into the shared runtime snapshot.
- Caption-vector and image-vector pack failures now remain visible as `UNAVAILABLE` while pending work exists instead of collapsing into generic queued or complete text.
- Added parser coverage; no indexed data or device state changed.

### 2026-08-06 - Prefer E2B for automatic Gemma provisioning

- Automatic model candidates now try Gemma E2B first on every compatible device.
- E4B remains an optional device-recommended fallback; the shared selected pack is still used by planning, verification, and answer composition.
- Updated the candidate-order regression test.
### 2026-08-06 - Safe-device vector scale gate

- `VectorIndexBenchmarkTest.exactFp16ScanMeetsReferenceDeviceGateAt5kAnd20k` PASS on fixture device `R3CY30QFWLP` using the native vector backend.
- 5,000 vectors: build `3,792 ms`, exact-scan p95 `38 ms`, snapshot `7.4 MB`.
- 20,000 vectors: build `18,067 ms`, exact-scan p95 `48 ms`, snapshot `29.6 MB`.
- The benchmark used app-private synthetic vectors only; it did not modify user gallery media or production-device data.
- Full run-scoped gallery ingestion, real E2B grounded-answer composition, and 20,000-item end-to-end indexing remain separate acceptance gates.

## 2026-08-06 - Pipeline-scoped recovery acceptance

- Files changed: `TestGallerySeederReceiver.kt`, `GalleryRepository.kt`.
- Recovery verification now cancels and reclaims media-analysis and embedding pipelines independently, then reschedules durable pending work.
- Tests: `IndexingReliabilityPolicyTest` PASS; `consumerDebug` and `consumerDebugAndroidTest` builds PASS.
- Device: safe fixture recovery gate PASS with 84 rows, 756 stage rows, 0 running stages, and 0 indexing rows before the rescheduling adjustment.
- Device: post-adjustment core acceptance NOT RUN to completion because `R3CY30QFWLP` disconnected from ADB; no production device was touched.
- Blocker: safe fixture must reconnect before validating post-recovery continuation and the full query/evidence path.

## 2026-08-06 - Personal semantic-memory coverage correction

- Personal completed coverage now counts current, valid MEDIA-scoped captions for eligible reviewed Me/family media instead of relying only on enrichment-job rows.
- Personal pending coverage is derived from eligible media after completed, failed, and authentication-required states; exact-reuse and stale counters are restricted to eligible personal media.
- Added an instrumentation regression for a completed media caption counting as personal coverage.
- `:app:testFixtureCiDebugUnitTest`: PASS.
- `:app:assembleFixtureCiDebugAndroidTest :app:assembleConsumerDebug`: PASS.
- Instrumentation execution: NOT RUN; the safe fixture device is disconnected. The production device was not used.

## 2026-08-06 - Personal job coverage eligibility filter

- Personal failed, pending, running, and authentication-required job counts now use distinct media IDs and are restricted to accessible, ready media containing reviewed, visible people included in personal semantic memory.
- `:app:testFixtureCiDebugUnitTest`: PASS.
- `:app:assembleFixtureCiDebugAndroidTest :app:assembleConsumerDebug`: PASS.
- Instrumentation execution: NOT RUN; only the protected production device is connected, so it was not used.

## 2026-08-06 - Truthful caption-vector unavailable status

- Caption embedding workers now publish `UNAVAILABLE` with `NO_VERIFIED_RETRIEVAL_PACK` whenever the verified text-embedding producer is absent, including when no pending rows remain; retry behavior is unchanged for pending work.
- Focused `CaptionEmbeddingWorkerTest`: PASS.
- `:app:testFixtureCiDebugUnitTest`: PASS.
- `:app:assembleConsumerDebug :app:assembleOfflineDemoDebug`: PASS.
- Merged offline APK permission audit: PASS, no `INTERNET` permission.
- Device execution: NOT RUN; safe fixture unavailable and production device untouched.

## 2026-08-06 - Fail-closed reviewed-identity expansion

- Reviewed-person auto-expansion now rejects candidate faces whose cluster reference is missing, reviewed, hidden, or user-corrected; missing metadata can no longer satisfy a vector threshold.
- Added unit coverage for missing-reference rejection and protected-face rejection while preserving strong-evidence acceptance for referenced unreviewed faces.
- Focused policy test: PASS.
- `:app:testFixtureCiDebugUnitTest`: PASS.
- `:app:assembleConsumerDebug`: PASS.
- Device execution: NOT RUN; safe fixture unavailable and production device untouched.

## 2026-08-06 - Gemma session replacement rollback

- Shared Gemma session replacement now initializes the new verified generation before closing the active engine.
- If replacement loading fails, the previous engine remains active and reusable; initialization counters do not report a failed load as an initialized engine.
- Added regression coverage for failed E4B replacement while retaining the active E2B session.
- Focused and full fixture unit tests: PASS.
- `:app:assembleConsumerDebug :app:assembleOfflineDemoDebug`: PASS.
- Device execution: NOT RUN; safe fixture unavailable and production device untouched.

### 2026-08-06 - Gemma installed-pack reactivation integrity

- Changed files: `android/app/src/main/java/io/github/askalbum/ModelPackManager.kt`, `android/app/src/test/java/io/github/askalbum/GemmaPackValidationTest.kt`.
- Installed Gemma generations now revalidate the bounded manifest, signature, exact file set, artifact sizes, and streamed SHA-256 digests during current-pack and fallback discovery.
- Tests: `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.GemmaPackValidationTest` PASS; full `:app:testFixtureCiDebugUnitTest` PASS; `:app:assembleConsumerDebug` PASS; `:app:assembleOfflineDemoDebug` PASS.
- Device validation: NOT RUN; the safe fixture device is unavailable. Production device data and model state were not touched.
- Next: commit and push this verified phase to `https://github.com/anup42/AskAlbum.git`.

### 2026-08-06 - Revalidate all production model packs after restart

- Retrieval generations now revalidate the bounded manifest, APK signature, exact installed file set, artifact sizes, and SHA-256 digests when the current pointer is reopened.
- PaddleOCR active packs now recompute every catalog artifact digest on reactivation; SFace now recomputes the model digest instead of trusting only its marker and file length.
- Tests: `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.RetrievalPackValidationTest --tests io.github.anup42.askalbum.InstalledModelPackIntegrityTest` PASS; full `:app:testFixtureCiDebugUnitTest` PASS; `:app:assembleConsumerDebug` PASS; `:app:assembleOfflineDemoDebug` PASS.
- Device validation: NOT RUN; the safe fixture device remains unavailable. No production model, gallery, People, or index data was touched.

### 2026-08-06 - Recover interrupted retrieval-pack activation

- Retrieval model activation now records the prior generation before replacing the current pointer and retains it for recovery.
- If the current pointer is missing or its generation fails validation after process death, a valid previous generation is restored without deleting model generations.
- Tests: `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.RetrievalPackValidationTest --tests io.github.anup42.askalbum.RetrievalGenerationPointerTest` PASS; full `:app:testFixtureCiDebugUnitTest` PASS; `:app:assembleConsumerDebug` PASS; `:app:assembleOfflineDemoDebug` PASS.
- Device validation: NOT RUN; safe fixture unavailable. Production device and installed packs were not touched.

### 2026-08-06 - Recover interrupted OCR-pack activation

- PaddleOCR activation now renames the active pack instead of deleting it, retains prior generations under app-private recovery names, and restores a valid staged or previous pack after process death before activation completes.
- Tests: `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.OcrPackActivationTest` PASS; full `:app:testFixtureCiDebugUnitTest` PASS; `:app:assembleConsumerDebug` PASS; `:app:assembleOfflineDemoDebug` PASS.
- Device validation: NOT RUN; safe fixture unavailable. Production OCR pack and gallery data were not touched.
- Remaining related gap: SFace activation still needs the same migration-safe recovery treatment.

### 2026-08-06 - Make SFace activation generation-safe

- New SFace installs now use app-private versioned generations with signed-by-catalog SHA-256 verification, durable current/previous pointers, and exact generation file validation.
- Existing legacy SFace model and marker paths remain readable as a non-destructive fallback; replacement installation never deletes them.
- Tests: `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.FaceGenerationPointerTest` PASS; full `:app:testFixtureCiDebugUnitTest` PASS; `:app:assembleConsumerDebug` PASS; `:app:assembleOfflineDemoDebug` PASS.
- Device validation: NOT RUN; safe fixture unavailable. Production SFace files, gallery data, People data, and consent were not touched.

## 2026-08-06 - Keep event expansion item-predicate grounded

- Fixed event-member expansion so typed place and comparison terms remain scope-only and cannot make unrelated event members look like matches for an item predicate.
- Event gating now uses predicate-only lexical hits, direct media-scoped caption evidence, direct caption-vector evidence, and a bounded predicate-only image-vector lookup when a scope term is present. Event summaries and explicit event grouping retain contextual member expansion.
- Tests: `EventExpansionPolicyTest` PASS; full `:app:testFixtureCiDebugUnitTest` PASS; `:app:assembleConsumerDebug` PASS; `:app:assembleOfflineDemoDebug` PASS.
- Device validation: NOT RUN; the safe fixture device is unavailable. The protected production device was not touched.
- Remaining: connected acceptance queries, process-death/Doze/foreground-service timeout, and 5k/20k workload gates remain NOT RUN.

## 2026-08-06 - Require the requested People cluster to be identity-ready

- Fixed the People query gate so a ready embedding in one reviewed cluster cannot unlock searches for a different reviewed cluster that has only face boxes or missing SFace embeddings.
- Required and excluded person clauses now fail closed per requested identity; alternative groups open only when at least one alternative has a usable reviewed identity embedding. No consent, face records, or completed indexes were changed.
- Tests: `PeopleQueryGateTest` PASS; full `:app:testFixtureCiDebugUnitTest` PASS; `:app:assembleConsumerDebug` PASS; `:app:assembleOfflineDemoDebug` PASS.
- Device/instrumentation validation: NOT RUN; the safe fixture device is unavailable. The protected production device was not touched.
- Remaining: database-level/device People acceptance, connected acceptance queries, process-death/Doze/foreground-service timeout, and 5k/20k workload gates remain NOT RUN.

## 2026-08-06 - Exclude face rows without usable identity embeddings

- Person media filtering and Gemma verification bindings now require the current SFace embedding dimension and a non-null vector offset. A reviewed face whose embedding is missing is retained as visible correction data but is labelled as an unreviewed/context face and cannot satisfy People search.
- Tests: `PeopleQueryGateTest` and full `:app:testFixtureCiDebugUnitTest` PASS; Android-test source compilation PASS via `:app:compileFixtureCiDebugAndroidTestKotlin`; `:app:assembleConsumerDebug` PASS; `:app:assembleOfflineDemoDebug` PASS.
- Device/database instrumentation: NOT RUN; only the protected production device is connected. No production data, People state, or models were touched.
- Remaining: execute the new database regression on the safe fixture, connected acceptance queries, process-death/Doze/foreground-service timeout, and 5k/20k workload gates remain NOT RUN.
### 2026-08-06 - Document fact answers now require complete OCR coverage

- Changed `CapabilityAnswerExecutor.factAnswer()` to refuse values from bounded or partial OCR retrieval passes.
- Exact document answers still use the existing deterministic selector when eligible OCR coverage is complete.
- Added `CapabilityRegistryTest.boundedDocumentFactDoesNotReturnAValueFromPartialOcrCoverage`.
- Tests/build: PASS - `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.CapabilityRegistryTest`, `:app:assembleConsumerDebug`, and `:app:assembleOfflineDemoDebug`.
- Device validation: NOT RUN; the safe fixture device is unavailable and the protected production device remains untouched.

### 2026-08-06 - Admit provenance-validated cached person evidence

- Grounded person-conditioned answers now admit direct cached caption evidence only when it is media/query scoped or verified exact-duplicate evidence, points to the same evidence media, carries a cluster binding, and matches the requested reviewed identity.
- Contextual group/event evidence, possible inference, legacy scope, foreign clusters, and unbound person chunks remain non-confirming; fresh visual verification remains accepted.
- Tests/build: PASS - `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.GroundedEvidenceClosureTest`, `:app:assembleConsumerDebug`, and `:app:assembleOfflineDemoDebug`.
- Device validation: NOT RUN; the safe fixture device is unavailable and the protected production device remains untouched.

### 2026-08-06 - Make verifier polarity Kotlin-owned

- Visual-verification decoding now treats Gemma's `overallMatch` as schema-only and derives hard-condition acceptance through `SemanticPolarityNormalizer`, including negative predicates and fail-closed `AMBIGUOUS`/`NOT_VISIBLE` verdicts.
- Added regression coverage proving a missing forbidden predicate can match and that arbitrary model summary booleans cannot override Kotlin-owned polarity.
- Tests/build: PASS - `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.GemmaVerificationCodecTest`, `:app:assembleConsumerDebug`, and `:app:assembleOfflineDemoDebug`.
- Device validation: NOT RUN; the safe fixture device is unavailable and the protected production device remains untouched.

### 2026-08-06 - Keep unsupported semantic counts estimated

- Exhaustive semantic predicate scans now refuse negative clauses and person-bound visual clauses because the current batch scanner evaluates only a positive whole-media embedding predicate.
- Those queries remain on the bounded/estimated path instead of presenting a positive-only scan as an exact count.
- Tests/build: PASS - `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.SemanticPredicateScanPolicyTest`, `:app:assembleConsumerDebug`, and `:app:assembleOfflineDemoDebug`.
- Device validation: NOT RUN; the safe fixture device is unavailable and the protected production device remains untouched.

### 2026-08-06 - Report incomplete caption-vector coverage truthfully

- Caption-vector eligibility now includes provenance-valid pending, retryable, exhausted, and wrong-version chunks in the coverage denominator while vector search uses only complete chunks from the active retrieval pack. Missing caption vectors therefore report `PARTIAL` instead of a successful empty search.
- Added regression coverage for pending and exhausted caption chunks; no captions, image vectors, People data, or model packs were changed.
- Tests/build: PASS - `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.CaptionVectorCoverageTest`, `:app:assembleConsumerDebug`, and `:app:assembleOfflineDemoDebug`.
- Device validation: NOT RUN; the safe fixture device is unavailable and the protected production device remains untouched.

### 2026-08-06 - Gate free-form activity facts by validated activity state

- Semantic enrichment now discards activity predicates from the generic `facts` array and persists activity facts only from the validated top-level `activityState`/activity fields. Static, ambiguous, or state-omitted images cannot retain a positive typed activity fact from contradictory model text.
- Added regression coverage for a `NONE_VISIBLE` image whose generic facts claim an activity; valid `OBSERVED` person actions remain covered by the existing activity-aware tests.
- Tests/build: PASS - `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.ActivityStateSafetyTest --tests io.github.anup42.askalbum.ActivityAwareSemanticCaptionValidationTest`, `:app:assembleConsumerDebug`, and `:app:assembleOfflineDemoDebug`.
- Device validation: NOT RUN; the safe fixture device is unavailable and the protected production device remains untouched.

### 2026-08-06 - Preserve contextual applicability for event and visual-group enrichment

- Generated EVENT and VISUAL_GROUP captions and facts now normalize to `GROUP_CONTEXT_ONLY`; `POSSIBLE_INFERENCE` remains uncertain, and exact-duplicate sharing still requires `SAFE_FOR_EXACT_DUPLICATES`.
- Added integration coverage proving non-media enrichment cannot be persisted with media-only applicability even when the model requests it.
- Tests/build: PASS - `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.SemanticScopeApplicabilityTest --tests io.github.anup42.askalbum.SemanticProvenanceApplicabilityTest --tests io.github.anup42.askalbum.ComprehensiveSemanticCaptionTest`, `:app:assembleConsumerDebug`, and `:app:assembleOfflineDemoDebug`.
- Device validation: NOT RUN; the safe fixture device is unavailable and the protected production device remains untouched.

### 2026-08-06 - Validate OCR capability fields before execution

- Gemma plans now publish the allowlisted OCR field keys, accept normalized field keys and canonical source-field names, and reject unknown OCR fields at the typed plan boundary instead of failing later in an executor.
- SUM and MIN_MAX plans now require an allowlisted numeric field before execution.
- Tests/build: PASS - `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.CapabilityRegistryTest --tests io.github.anup42.askalbum.GalleryQueryPlanValidatorTest --tests io.github.anup42.askalbum.QueryCompilerTest`, `:app:assembleConsumerDebug`, and `:app:assembleOfflineDemoDebug`.
- Device validation: NOT RUN; the safe fixture device is unavailable and the protected production device remains untouched.

### 2026-08-06 - Keep exclusion follow-ups negative-only

- Exclusion directives now remove positive terms and clauses derived from the directive before adding the normalized negative predicate. `Exclude screenshots` therefore cannot retrieve screenshots as a positive follow-up requirement.
- Tests/build: PASS - `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.Phase4OrchestrationTest --tests io.github.anup42.askalbum.ResultSetPlanPatchResolverTest --tests io.github.anup42.askalbum.FollowUpLanguageTest`, `:app:assembleConsumerDebug`, and `:app:assembleOfflineDemoDebug`.
- Device validation: NOT RUN; the safe fixture device is unavailable and the protected production device remains untouched.

### 2026-08-06 - Report caption-vector media coverage truthfully

- Caption-vector status now remains `PARTIAL` when eligible media have no caption chunks, even if every existing chunk vector is complete.
- Retrieval coverage separates media with at least one searchable chunk from media whose complete eligible chunk set is indexed; no image vectors or stored captions were changed.
- Tests/build: PASS - `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.CaptionVectorCoverageTest --tests io.github.anup42.askalbum.CaptionEmbeddingRetrievalTest --tests io.github.anup42.askalbum.RetrievalCoverageWordingTest`, `:app:assembleConsumerDebug`, and `:app:assembleOfflineDemoDebug`.
- Device validation: NOT RUN; the safe fixture device is unavailable and the protected production device remains untouched.

### 2026-08-06 - Keep the People pipeline opt-in by default

- The individual People indexing control now defaults off for new installations. Explicit consent enables the pipeline and schedules it; resetting People data disables the pipeline again.
- Existing media and derived People data are untouched; this changes only the durable worker control and preserves the separate consent gate in the worker.
- Tests/build: PASS - `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.IndexingJobControlsTest --tests io.github.anup42.askalbum.PeopleQueryGateTest --tests io.github.anup42.askalbum.IndexingReliabilityPolicyTest`, `:app:assembleConsumerDebug`, and `:app:assembleOfflineDemoDebug`.
- Device validation: NOT RUN; the safe fixture device is unavailable and the protected production device remains untouched.

### 2026-08-06 - Exclude hidden identities from People readiness
- People readiness now counts usable face embeddings only for visible reviewed clusters, and the query gate rejects a hidden-only identity state.
- This is a read-only status correction; gallery media, face vectors, reviewed labels, and hidden-cluster data are preserved.
- Tests/build: PASS - `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.PeopleQueryGateTest --tests io.github.anup42.askalbum.IndexingJobControlsTest --tests io.github.anup42.askalbum.PeopleClauseMergePolicyTest`, `:app:assembleConsumerDebug`, and `:app:assembleOfflineDemoDebug`.
- Device validation: NOT RUN; the safe fixture device is unavailable and the protected production device remains untouched.

### 2026-08-06 - Keep unavailable People coverage in media units
- The early People-unavailable answer now reports zero searched/indexed media instead of mixing face-instance counts with image counts.
- Tests/build: PASS - `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.PeopleQueryGateTest --tests io.github.anup42.askalbum.RetrievalCoverageWordingTest --tests io.github.anup42.askalbum.RetrievalChannelEvidenceTest`, `:app:assembleConsumerDebug`, and `:app:assembleOfflineDemoDebug`.
- Device validation: NOT RUN; the safe fixture device is unavailable and the protected production device remains untouched.

### 2026-08-06 - Promote explicitly requested personal jobs
- Re-requesting an existing personal semantic-memory job now updates its durable priority as well as `user_requested`, so the claim order can promote it immediately.
- Tests/build: PASS - `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.SemanticEnrichmentPriorityTest --tests io.github.anup42.askalbum.IndexingReliabilityPolicyTest --tests io.github.anup42.askalbum.IndexingJobControlsTest`, `:app:assembleConsumerDebug`, and `:app:assembleOfflineDemoDebug`.
- Device validation: NOT RUN; the safe fixture device is unavailable and the protected production device remains untouched.

### 2026-08-06 - Preserve typed OCR sensitivity in ordinary search
- Generic OCR search evidence now retains the matching allowlisted entity field, so passwords, contact values, order IDs, amounts, and similar protected entities cannot bypass the authentication gate outside document-answer intents.
- Tests/build: PASS - `:app:testFixtureCiDebugUnitTest --tests io.github.anup42.askalbum.SensitiveContentClassifierTest --tests io.github.anup42.askalbum.SensitiveSearchProjectionTest --tests io.github.anup42.askalbum.CapabilityRegistryTest --tests io.github.anup42.askalbum.RetrievalChannelEvidenceTest`, `:app:assembleConsumerDebug`, and `:app:assembleOfflineDemoDebug`.
- Device validation: NOT RUN; the safe fixture device is unavailable and the protected production device remains untouched.

## 2026-08-06 - Make personal-memory relationship eligibility locale-safe

- Personal semantic-memory defaults now use the shared NFKC and `Locale.ROOT` identity normalization instead of the device default locale.
- Added regression coverage for uppercase `SISTER` under a Turkish device locale; no gallery data, People corrections, captions, or model state were changed.

## 2026-08-06 - Hide sparse People clusters from the browser

- The People UI now displays only clusters represented in at least five accessible media items.
- Sparse cluster rows remain in the database and are not deleted, so indexing, corrections, and future review remain non-destructive.
- Added JVM regression coverage for the display boundary.
2026-08-06: Corrected the generic OCR amount regression test to reflect the existing financial-evidence authentication boundary. Amount selection remains covered by the deterministic document selector, while unauthenticated answer formatting stays locked and redacted.
2026-08-06: Corrected the generic OCR amount regression test to reflect the existing financial-evidence authentication boundary. Amount selection remains covered by the deterministic document selector, while unauthenticated answer formatting stays locked and redacted.
- Fixed reviewed-identity expansion coverage: accessible faces with no cluster are now returned as nullable references, while automatic clustering still ignores unassigned candidates; added policy and database regressions.
