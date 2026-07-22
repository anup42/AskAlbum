# AskPhotos Android

Native, local-only Android implementation of the Agentic Gallery architecture. It is separate from the legacy GPU-server proof of concept in the repository root.

## Implemented

- Kotlin, Jetpack Compose, and app-private SQLite/FTS; no local HTTP server or cloud inference. The `offlineDemo` flavor has no `INTERNET` permission; `consumer` uses it only for user-started model downloads.
- MediaStore full/partial access, Android Photo Picker, and SAF import for images, videos, and PDFs.
- Idempotent per-item index states with constrained, resumable WorkManager batches.
- Pluggable on-device OCR providers. A verified PP-OCRv5 Mobile pack uses ONNX Runtime with pure Kotlin/Android Bitmap preprocessing and Latin/Devanagari recognizers; bundled ML Kit Latin remains the fallback. OCR evidence retains normalized block regions.
- Video thumbnails and first-page PDF rendering; imported previews remain app-private.
- Typed `GalleryQueryPlan`, bounded validation, FTS/metadata/label/OCR retrieval, exact untruncated counts, receipt-total extraction, follow-up result-set filtering, and evidence records.
- Gemma 4 E2B/E4B Settings selection, capability policy, resumable foreground download from immutable Google AI Edge Gallery revisions, pinned LFS SHA-256/size verification, signed `.agemma` import, app-private atomic activation/rollback, LiteRT-LM 0.14.0 GPU/CPU runtime, one bounded plan plus one repair, and deterministic fallback.
- Sensitive OCR classification with biometric/device-credential gating.
- Ask, Library, Index Manager, coverage/exactness, execution status, and “Why this answer?” interfaces.
- SigLIP2 Base quantized retrieval is embedded in the APK, verified by pinned archive and per-artifact SHA-256 values, and expanded atomically into app-private storage on first launch. SFace remains a separate modular, opt-in identity model.
- A checksum-pinned, openly licensed multi-domain core gallery plus deterministic 5k/20k stress profiles for repeatable offline tests.

## Deliberate capability reporting

The app reports installed capabilities rather than pretending interfaces are models. ML Kit image labels are not presented as SigLIP embeddings, and face detection counts are not presented as person recognition. Identity search requires the separately verified Apache-2.0 SFace pack, explicit people-index consent, and a user-reviewed person cluster; without all three it fails closed.

See [the requirements audit](../docs/ANDROID_REQUIREMENTS_AUDIT.md) for the exact implementation matrix.

## Build and test

Place the pinned `siglip2-base-p16-224-q8-core05.agretrieval` archive at `../build/` relative to this Android directory. Gradle embeds that local, ignored model artifact into every APK; model weights are not committed to Git.

```powershell
.\gradlew.bat :app:testOfflineDemoDebugUnitTest :app:assembleOfflineDemoDebug
.\gradlew.bat :app:testConsumerDebugUnitTest :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest
```

APKs are emitted under `app/build/outputs/apk/offlineDemo/debug/` and `app/build/outputs/apk/consumer/debug/`.
