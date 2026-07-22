# AskPhotos Android

Native, local-only Android implementation of the Agentic Gallery architecture. It is separate from the legacy GPU-server proof of concept in the repository root.

## Implemented

- Kotlin, Jetpack Compose, and app-private SQLite/FTS; no local HTTP server or cloud inference. The `offlineDemo` flavor has no `INTERNET` permission; `consumer` uses it only for user-started model downloads.
- MediaStore full/partial access, Android Photo Picker, and SAF import for images, videos, and PDFs.
- Idempotent per-item index states with constrained, resumable WorkManager batches.
- Pluggable on-device OCR providers. A verified PP-OCRv5 Mobile pack uses the official PaddleOCR Android/ONNX SDK with Latin and Devanagari recognizers; bundled ML Kit Latin remains the fallback. OCR evidence retains normalized block regions.
- Video thumbnails and first-page PDF rendering; imported previews remain app-private.
- Typed `GalleryQueryPlan`, bounded validation, FTS/metadata/label/OCR retrieval, exact untruncated counts, receipt-total extraction, follow-up result-set filtering, and evidence records.
- Gemma 4 E2B/E4B Settings selection, capability policy, resumable foreground download from immutable Google AI Edge Gallery revisions, pinned LFS SHA-256/size verification, signed `.agemma` import, app-private atomic activation/rollback, LiteRT-LM 0.14.0 GPU/CPU runtime, one bounded plan plus one repair, and deterministic fallback.
- Sensitive OCR classification with biometric/device-credential gating.
- Ask, Library, Index Manager, coverage/exactness, execution status, and “Why this answer?” interfaces.
- Verified SigLIP2 retrieval and OpenCV SFace identity model packs. SFace is selected through the same provider-registry pattern used by OCR, runs through ONNX Runtime, produces normalized 128-dimensional embeddings, stores them in an app-private FP16 index, and remains opt-in for people indexing.
- A checksum-pinned, openly licensed multi-domain core gallery plus deterministic 5k/20k stress profiles for repeatable offline tests.

## Deliberate capability reporting

The app reports installed capabilities rather than pretending interfaces are models. ML Kit image labels are not presented as SigLIP embeddings, and face detection counts are not presented as person recognition. Identity search requires the separately verified Apache-2.0 SFace pack, explicit people-index consent, and a user-reviewed person cluster; without all three it fails closed.

See [the requirements audit](../docs/ANDROID_REQUIREMENTS_AUDIT.md) for the exact implementation matrix.

## Build and test

```powershell
.\gradlew.bat :app:testOfflineDemoDebugUnitTest :app:assembleOfflineDemoDebug
.\gradlew.bat :app:testConsumerDebugUnitTest :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest
```

APKs are emitted under `app/build/outputs/apk/offlineDemo/debug/` and `app/build/outputs/apk/consumer/debug/`.
