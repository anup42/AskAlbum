# Public implementation status

Last reviewed: 2026-08-04

AskAlbum is an early open-source Android implementation of private, on-device
photo search. The public source snapshot contains the Android application,
fixture CI path, tests, model-pack validation code, sample-gallery tooling, and
architecture documentation. It does not contain user media, generated indexes,
model binaries, APKs, device logs, or private credentials.

## Build status

- `ciDebug`: intended model-independent fixture build for CI and contributors.
- `offlineDemoDebug`: local-only demo variant with no Internet permission.
- `consumerDebug`: production-style variant with explicit verified model-pack
  download support; model packs are deliberately external.

The repository workflow is the authoritative build check. Device acceptance
requires a configured Android device and locally available model packs, so it is
not represented as a GitHub Actions pass.

## Verification log

- Phase 0: `offlineDemoDebug` assembly PASS; wrapper executable bit PASS.
- Phase 0: full combined fixture test and `ciDebug` assemble NOT RUN; the local
  Gradle command exceeded the time limit before reporting a result.
- Phase 1: semantic zero-vector coverage regression test PASS.
- Phase 1: semantic retrieval now reports PARTIAL when eligible media have no
  indexed vectors; no model, media, or index data was changed.
- Phase 7 hardening: durable evidence and person-fact IDs now use SHA-256
  derivation instead of JVM `hashCode()`; stability regression test PASS.
- Phase 7 hardening: native vector scanning now uses the portable arm64
  baseline instead of universally compiling ARMv8.2 FP16 instructions.
- Phase 7 hardening: semantic fact persistence now preserves EVENT and
  VISUAL_GROUP provenance; migration 18->19 repairs known legacy copies and
  quarantines ambiguous scope as contextual evidence.

## Privacy boundary

Media analysis, OCR, face indexing, embeddings, retrieval, Gemma planning,
verification, and grounded answer composition are designed to run on device.
Face indexing remains explicit opt-in. Sensitive OCR values remain protected.
See `PRIVACY.md` and `THIRD_PARTY_NOTICES.md`.

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
