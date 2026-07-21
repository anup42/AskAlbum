# Android requirement audit

Audit date: 21 July 2026

Legend: **Done** is executable in the Android app; **Partial** is useful but does not yet meet the full requirement; **External pack required** means the code boundary exists but a licensed/converted model artifact is not present in this repository or on the test device.

| Requirement area | Status | Current implementation / remaining gap |
|---|---|---|
| Native Android, no server | **Done** | Kotlin/Compose, direct repositories and workers; no Docker, Python, HTTP server, React, or `INTERNET` permission. |
| Sample gallery | **Done** | 14 checksum-pinned CC0 images packaged and indexed idempotently. |
| Full/partial Android gallery access | **Done** | Version-aware MediaStore permissions including Android 14 selected access; access is re-queried on resume. |
| Private selection and documents | **Done** | Photo Picker plus SAF image/video/PDF import and persistable URI attempt. |
| Structured local memory | **Done** | Room schema v7 over private SQLite stores media, normalized OCR blocks/FTS, typed OCR entities, events, event membership, evidence, queries, explicit per-stage state, and versioned visual features. Additive migrations and the legacy v3-to-v7 chain are device-tested. |
| Background recovery | **Done** | WorkManager, battery/storage constraints, interrupted-state recovery, bounded continuation batches. A user-visible long-running foreground notification is not yet used. |
| Metadata | **Done** | MIME, kind, URI, dates, dimensions, duration, size, source, state, and version. EXIF GPS/date confidence hierarchy is **Partial**. |
| OCR | **Done / Partial** | OCR likelihood gating, bundled offline Latin ML Kit recognition, normalized blocks with language/page/region, FTS, typed entities, provenance, and deterministic receipt scoring are implemented. The exact synthetic Swiggy `INR 1,248` evidence flow passed on device. A benchmarked open-source multilingual OCR pack and additional scripts remain required. |
| Image semantic indexing | **Implemented, real-pack gate pending** | A separate resumable worker now decodes eligible media, runs a verified LiteRT image encoder in bounded batches, stores normalized FP16 vectors by pack revision, and reconciles deletions. Without an installed signed pack, deterministic fixture semantics remain active. |
| SigLIP2 vector retrieval | **Implemented, real-pack gate pending** | LiteRT 2.1.0 dual-encoder runtime, pinned conversion/parity tooling, exported tokenizer contract, APK-key signature verification, per-file hashes, calibrated no-match threshold, exact vector scan, and RRF are present. No converted `.agretrieval` pack was available, so real Recall@K/inference remains NOT RUN. |
| Exact FP16 vector scan | **Implemented and device-verified** | Production reference oracle plus crash-safe mmap FP16 snapshots/WAL and native ARM64 FP16 scan. At 768 dimensions on SM-F731U: 5k p95 34 ms, 20k p95 15 ms. A concrete real encoder/model version is still pending. |
| Face detection | **Done** | Bundled local detector and per-item face counts. |
| Face embeddings, clusters, people UI | **External pack required** | Requires a licensed mobile embedding model and explicit opt-in/label workflow. Detection is not treated as identity. |
| Duplicate collapse and result diversity | **Done / Partial** | Deterministic pHash/quality compilation, conservative exact/near-burst grouping, representative selection, expandable duplicate IDs, UI similar-count badge, and event round-robin after RRF are implemented. Richer learned quality and user-tunable grouping remain optional. |
| Events | **Partial** | Deterministic day grouping, persisted membership, and event-aware result diversity are implemented. GPS distance, people overlap, prototypes, merge/split/rename UI remain. |
| Videos | **Partial** | Metadata and thumbnail labeling/OCR. Scene-change keyframes and timestamp-specific evidence remain. |
| PDFs | **Partial** | First page is rendered, labeled, OCR-indexed, and cached privately. Per-page child records for every page remain. |
| Typed query plan | **Done** | Bounded enums/terms/result limits; neither model nor user input can emit SQL, paths, URIs, or tools. |
| Gemma planning | **Done when pack installed** | `.litertlm` SAF import, space check, SHA-256 record, LiteRT-LM 0.14.0, GPU-to-CPU fallback, schema validation, deterministic fallback. No model weight is bundled. |
| Gemma visual verification | **External pack required** | Runtime interface exists, but candidate image prompting/strict verifier schema is not connected until a compatible multimodal Gemma pack is installed and tested. |
| Deterministic count | **Done** | Counts use the complete matching set, not the 100-card UI limit, and report coverage. |
| Receipt total | **Done / Partial** | Local OCR plus deterministic Total/Grand Total/Amount Paid selection. Broader receipt calibration remains. |
| Comparisons/timelines/change over time | **Partial** | Typed intents and events exist; dedicated comparison and aligned before/after execution are not implemented. |
| Evidence and post-validation | **Done / Partial** | Evidence IDs, source fields, normalized OCR regions, producer versions, coverage, and exactness. Gemma answer composition/citation regeneration is not active without a pack. |
| Follow-ups | **Done / Partial** | “Only…” and “What about…” retain the active result set. General Gemma PlanPatch and persisted multi-session pronoun resolution remain. |
| Sensitive OCR | **Done / Partial** | Password/card/identity/medical patterns require biometric or device credential before evidence display. At-rest field-level/database encryption remains. |
| Model/resource management | **Partial** | Private import, checksum record, pinned runtime, serialized Gemma engine, GPU/CPU fallback. Signed manifests, downloads, E2B/E4B benchmark selection, thermal policies, rollback, and model-specific reindex are pending. |
| Privacy | **Done / Partial** | App-private data, no Internet permission/cloud inference/raw logging, biometric evidence gate. Keystore-backed database encryption and face-data purge UI await their respective implementations. |
| Evaluation | **Partial** | Unit tests, connected-device Compose test, lint, sample query and privacy checks exist. The requested 200–300-query multilingual/retrieval/thermal benchmark suite does not. |

## Honest completion boundary

The application is now a functional offline Android gallery indexer and evidence-backed search product, not merely a UI mock. It is not yet the complete production system described in the supplied document. The remaining model/evaluation blockers are concrete Gemma 4 E2B/E4B `.litertlm` packs, an actually converted and calibrated SigLIP2 `.agretrieval` pack, a licensed face-embedding model, and a consented labeled evaluation gallery. The SigLIP2 conversion, signing, import, runtime, indexing, and fusion path is implemented, but it is not counted as real-model acceptance until that pack is produced and measured.
