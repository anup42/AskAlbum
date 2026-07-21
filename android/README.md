# AskPhotos Android

Native, local-only Android implementation of the Agentic Gallery architecture. It is separate from the legacy GPU-server proof of concept in the repository root.

## Implemented

- Kotlin, Jetpack Compose, and app-private SQLite/FTS; no local HTTP server or cloud inference. The `offlineDemo` flavor has no `INTERNET` permission; `consumer` uses it only for user-started model downloads.
- MediaStore full/partial access, Android Photo Picker, and SAF import for images, videos, and PDFs.
- Idempotent per-item index states with constrained, resumable WorkManager batches.
- Bundled on-device ML Kit OCR, image labels, and face detection. OCR evidence retains normalized block regions.
- Video thumbnails and first-page PDF rendering; imported previews remain app-private.
- Typed `GalleryQueryPlan`, bounded validation, FTS/metadata/label/OCR retrieval, exact untruncated counts, receipt-total extraction, follow-up result-set filtering, and evidence records.
- Gemma 4 E2B/E4B Settings selection, capability policy, resumable foreground download from immutable Google AI Edge Gallery revisions, pinned LFS SHA-256/size verification, signed `.agemma` import, app-private atomic activation/rollback, LiteRT-LM 0.14.0 GPU/CPU runtime, one bounded plan plus one repair, and deterministic fallback.
- Sensitive OCR classification with biometric/device-credential gating.
- Ask, Library, Index Manager, coverage/exactness, execution status, and “Why this answer?” interfaces.
- Fourteen checksum-pinned CC0 sample photos for repeatable offline tests.

## Deliberate capability reporting

The app reports installed capabilities rather than pretending interfaces are models. SigLIP2 retrieval and face-identity search remain unavailable until compatible, redistributable model files are provided. ML Kit image labels are not presented as SigLIP embeddings, and face detection counts are not presented as person recognition.

See [the requirements audit](../docs/ANDROID_REQUIREMENTS_AUDIT.md) for the exact implementation matrix.

## Build and test

```powershell
.\gradlew.bat :app:testOfflineDemoDebugUnitTest :app:assembleOfflineDemoDebug
.\gradlew.bat :app:testConsumerDebugUnitTest :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest
```

APKs are emitted under `app/build/outputs/apk/offlineDemo/debug/` and `app/build/outputs/apk/consumer/debug/`.
