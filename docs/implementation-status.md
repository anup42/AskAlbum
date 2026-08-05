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
- High-risk OCR entity values are encrypted with an Android Keystore AES-GCM
  envelope; legacy plaintext rows remain readable for non-destructive upgrade.
  OCR blocks, aggregate OCR text, and historical query fields still require a
  separate migration before they can be called encrypted at rest.
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
- Remaining privacy scope: media aggregate OCR/FTS fields and historical People-derived identity fields still require a separate compatibility-safe encrypted-index design.

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
- Remaining privacy scope: historical People-derived identity fields and some non-OCR semantic identity records still need a separate encrypted-index design.
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
