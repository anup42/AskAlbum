# Android requirement audit

Audit date: 21 July 2026

Legend: **Done** is executable in the Android app; **Partial** is useful but does not yet meet the full requirement; **External pack required** means the code boundary exists but a licensed/converted model artifact is not present in this repository or on the test device.

| Requirement area | Status | Current implementation / remaining gap |
|---|---|---|
| Native Android, no server | **Done** | Kotlin/Compose, direct repositories and workers; no Docker, Python, HTTP server, React, or cloud inference. `offlineDemo` omits `INTERNET`; `consumer` permits only the allowlisted model downloader. |
| Sample gallery | **Done** | 14 checksum-pinned CC0 images packaged and indexed idempotently. |
| Full/partial Android gallery access | **Done** | Version-aware MediaStore permissions including Android 14 selected access; access is re-queried on resume. |
| Private selection and documents | **Done** | Photo Picker plus SAF image/video/PDF import and persistable URI attempt. |
| Structured local memory | **Done** | Room schema v7 over private SQLite stores media, normalized OCR blocks/FTS, typed OCR entities, events, event membership, evidence, queries, explicit per-stage state, and versioned visual features. Additive migrations and the legacy v3-to-v7 chain are device-tested. |
| Background recovery | **Done** | WorkManager, battery/storage constraints, interrupted-state recovery, bounded continuation batches. A user-visible long-running foreground notification is not yet used. |
| Metadata | **Done** | MIME, kind, URI, dates, dimensions, duration, size, source, state, and version. EXIF GPS/date confidence hierarchy is **Partial**. |
| OCR | **Done / Partial** | OCR likelihood gating, bundled offline Latin ML Kit recognition, normalized blocks with language/page/region, FTS, typed entities, provenance, and deterministic receipt scoring are implemented. The exact synthetic Swiggy `INR 1,248` evidence flow passed on device. A benchmarked open-source multilingual OCR pack and additional scripts remain required. |
| Image semantic indexing | **Implemented, real-pack gate pending** | A separate resumable worker now decodes eligible media, runs a verified LiteRT image encoder in bounded batches, stores normalized FP16 vectors by pack revision, and reconciles deletions. Without an installed signed pack, deterministic fixture semantics remain active. |
| SigLIP2 vector retrieval | **Installed and executable; semantic gate failing** | LiteRT 2.1.0 and ONNX Runtime Mobile 1.23.2 dual-encoder paths, pinned pack tooling, exported tokenizer contract, APK-key signature verification, per-file hashes, exact vector scan, and RRF are present. A signed q8 base/224 pack is installed and both towers ran on-device, but the first semantic discrimination gate failed, so Recall@K and threshold calibration are not accepted. |
| Exact FP16 vector scan | **Implemented and device-verified** | Production reference oracle plus crash-safe mmap FP16 snapshots/WAL and native ARM64 FP16 scan. At 768 dimensions on SM-F731U: 5k p95 34 ms, 20k p95 15 ms. A concrete real encoder/model version is still pending. |
| Face detection | **Done** | Bundled local detector and per-item face counts. |
| Face embeddings, clusters, people UI | **External pack required** | Requires a licensed mobile embedding model and explicit opt-in/label workflow. Detection is not treated as identity. |
| Duplicate collapse and result diversity | **Done / Partial** | Deterministic pHash/quality compilation, conservative exact/near-burst grouping, representative selection, expandable duplicate IDs, UI similar-count badge, and event round-robin after RRF are implemented. Richer learned quality and user-tunable grouping remain optional. |
| Events | **Partial** | Deterministic day grouping, persisted membership, and event-aware result diversity are implemented. GPS distance, people overlap, prototypes, merge/split/rename UI remain. |
| Videos | **Partial** | Metadata and thumbnail labeling/OCR. Scene-change keyframes and timestamp-specific evidence remain. |
| PDFs | **Partial** | First page is rendered, labeled, OCR-indexed, and cached privately. Per-page child records for every page remain. |
| Typed query plan | **Done** | Bounded enums/terms/result limits; neither model nor user input can emit SQL, paths, URIs, or tools. Kotlin overlays exact explicit/previous-year ranges and other recognized hard fields, then revalidates the merged plan. |
| Gemma planning | **Implemented; real E2B multilingual gate passed** | Strict full-plan JSON codec, one bounded repair, signed SAF import, immutable E2B/E4B catalog, SHA-256/size verification, LiteRT-LM 0.14.0, GPU-to-CPU fallback, rollback, and deterministic fallback. Pinned E2B produced valid English, Hindi, and Hinglish `FIND_MEDIA`/`IMAGES` plans on GPU with no fallback. This is three cases, not the full 200–300-query evaluation. |
| Gemma visual verification | **External pack required** | Runtime interface exists, but candidate image prompting/strict verifier schema is not connected until a compatible multimodal Gemma pack is installed and tested. |
| Deterministic count | **Done** | Counts use the complete matching set, not the 100-card UI limit, and report coverage. |
| Receipt total | **Done / Partial** | Local OCR plus deterministic Total/Grand Total/Amount Paid selection. Broader receipt calibration remains. |
| Comparisons/timelines/change over time | **Partial** | Typed intents and events exist; dedicated comparison and aligned before/after execution are not implemented. |
| Evidence and post-validation | **Done / Partial** | Evidence IDs, source fields, normalized OCR regions, producer versions, coverage, and exactness. Gemma answer composition/citation regeneration is not active without a pack. |
| Follow-ups | **Done / Partial** | “Only…” and “What about…” retain the active result set. General Gemma PlanPatch and persisted multi-session pronoun resolution remain. |
| Sensitive OCR | **Done / Partial** | Password/card/identity/medical patterns require biometric or device credential before evidence display. At-rest field-level/database encryption remains. |
| Model/resource management | **Partial** | App-private signed import and resumable foreground download, immutable revisions, pinned size/SHA-256, persistent E2B/E4B choice, 8/12 GB-class capability policy, serialized Gemma engine, GPU/CPU fallback, and atomic rollback are implemented. Three consecutive E2B load/generate/close cycles passed; measured peak PSS was 2,297,343 kB and thermal status stayed 0 during the 51-second suite. Sustained thermal policy remains pending; E4B is not tested. |
| Privacy | **Done / Partial** | App-private data, no cloud inference/raw logging, biometric evidence gate. `offlineDemo` has no Internet permission; `consumer` uses network only for user-selected immutable model downloads. Keystore-backed database encryption and face-data purge UI remain. |
| Evaluation | **Partial** | Unit tests, connected-device Compose test, lint, sample query and privacy checks exist. The requested 200–300-query multilingual/retrieval/thermal benchmark suite does not. |

## Honest completion boundary

The application is now a functional Android gallery indexer and evidence-backed search product, not merely a UI mock. It is not yet the complete production system described in the supplied document. The consumer app fetched and verified the official Gemma 4 E2B LiteRT-LM artifact; real multilingual planning, targeted visual verification, grounded composition, and no-answer gates passed on-device. E4B remains optional and untested. A pinned q8 SigLIP2 pack is now installed and executable, but semantic discrimination failed its acceptance assertion and is not counted as retrieval-quality acceptance. Other blockers include Recall@K/threshold calibration, a licensed face-embedding model, and a consented labeled evaluation gallery.
