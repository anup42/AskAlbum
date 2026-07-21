# AskPhotos Android

Native, local-only Android implementation of the Agentic Gallery architecture. It is separate from the legacy GPU-server proof of concept in the repository root.

## Implemented

- Kotlin, Jetpack Compose, and app-private SQLite/FTS; no local HTTP server and no `INTERNET` permission.
- MediaStore full/partial access, Android Photo Picker, and SAF import for images, videos, and PDFs.
- Idempotent per-item index states with constrained, resumable WorkManager batches.
- Bundled on-device ML Kit OCR, image labels, and face detection. OCR evidence retains normalized block regions.
- Video thumbnails and first-page PDF rendering; imported previews remain app-private.
- Typed `GalleryQueryPlan`, bounded validation, FTS/metadata/label/OCR retrieval, exact untruncated counts, receipt-total extraction, follow-up result-set filtering, and evidence records.
- Optional Gemma `.litertlm` model import with free-space validation, SHA-256 verification record, LiteRT-LM 0.14.0 GPU/CPU runtime, one bounded planner call, and deterministic fallback.
- Sensitive OCR classification with biometric/device-credential gating.
- Ask, Library, Index Manager, coverage/exactness, execution status, and “Why this answer?” interfaces.
- Fourteen checksum-pinned CC0 sample photos for repeatable offline tests.

## Deliberate capability reporting

The app reports installed capabilities rather than pretending interfaces are models. SigLIP2 retrieval and face-identity search remain unavailable until compatible, redistributable model files are provided. ML Kit image labels are not presented as SigLIP embeddings, and face detection counts are not presented as person recognition.

See [the requirements audit](../docs/ANDROID_REQUIREMENTS_AUDIT.md) for the exact implementation matrix.

## Build and test

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

The debug APK is emitted at `app/build/outputs/apk/debug/app-debug.apk`.
