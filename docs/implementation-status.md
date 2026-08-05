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
