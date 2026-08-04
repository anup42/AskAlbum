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
