# Agentic Gallery

Agentic Gallery is a native Android application for private, fully on-device
photo, video, screenshot, PDF, OCR, people, place, event, and metadata search.
It uses local retrieval and Gemma inference without an application server or
cloud inference service.

## Repository layout

- `android/`: Kotlin, Jetpack Compose, Room, WorkManager, JNI, unit tests, and
  connected-device tests.
- `demo-assets/`: consented demo media embedded by Android builds.
- `tools/device/`: Android seeding, profiling, model installation, and
  connected-device acceptance tooling.
- `tools/model-conversion/`: signed/checksummed local model-pack preparation.
- `tools/sample_gallery/`: synthetic and stress-gallery generation.
- `scripts/verify_demo_library.py`: demo-media verification.
- `docs/`: implementation status, requirements, testing, licensing, and
  versioning.
- `s-Gallery/`: Samsung Gallery and Agentic Gallery visual references.

## Android variants

- `ciDebug`: model-independent fixture engines for source and unit-test builds.
- `offlineDemoDebug`: embedded retrieval/face assets and no Internet permission.
- `consumerDebug`: user-started model downloads with all inference remaining
  on device.

Production model activation remains checksum and signature verified. Face
indexing is explicit opt-in, and sensitive OCR evidence remains
authentication-protected.

## Build

Configure an Android SDK, then run from `android/`:

```powershell
.\gradlew.bat :app:assembleCiDebug
.\gradlew.bat :app:testCiDebugUnitTest
```

Model-bearing builds use ignored local artifacts:

- `build/siglip2-base-p16-224-q8-core05.agretrieval`
- `build/models/face/face_recognition_sface_2021dec.onnx`

Build a device variant with:

```powershell
.\gradlew.bat :app:assembleConsumerDebug
```

See [android/README.md](android/README.md) for Android-specific operation and
[docs/implementation-status.md](docs/implementation-status.md) for current
capability status and verified test evidence.
