# Agentic Gallery implementation status

Last updated: 21 July 2026

This is the evidence-backed implementation ledger for the native Android application under `android/`. A capability is marked complete only after its stated command or device gate has succeeded.

## Phase 0 — Repository audit and baseline

Status: **Passed with documented environment setup; product implementation remains partial.**

### Files changed

- Added this status ledger only.
- No application source, Gradle configuration, device media, or model files were changed during the baseline.

### Architecture decisions

- Preserve the existing native Android project at `android/` and improve it in vertical slices. Do not reorganize it merely to match the suggested multi-module diagram.
- Treat the root `backend/`, `frontend/`, Docker, and GPU scripts as the older proof of concept; they are not dependencies of the Android runtime.
- Keep the current deterministic fixture path runnable while real retrieval and generative engines are added behind interfaces.
- Do not create `local.properties`. The baseline shell lacked `ANDROID_HOME`, so commands derived the SDK root from the active `adb` executable and set `ANDROID_HOME`/`ANDROID_SDK_ROOT` for that process only.
- Preserve all pre-existing uncommitted work. Baseline `git status --short`: `.gitignore` modified; `android/` and `docs/ANDROID_REQUIREMENTS_AUDIT.md` untracked.

### Repository baseline

- Gradle modules: `:app` only.
- Package: `com.askphotos.android`.
- SDKs: compile 36, target 36, minimum 29.
- Build variants: `debug` and `release`; required `offlineDemo` and `consumer` variants do not exist yet.
- UI/runtime: Kotlin and Jetpack Compose.
- Existing persistence is a custom `SQLiteOpenHelper`, not Room.
- Existing background work uses WorkManager.
- Existing tests: one JVM test class with five tests and one connected Compose smoke test.
- Existing Android implementation already includes a deterministic query compiler/repository path, OCR/image-label/face-detection indexing, optional LiteRT-LM planner loading, evidence models, biometric gating, and basic MediaStore/SAF import.
- Major missing or incomplete areas include the licensed 60–100-item corpus and safe seeder, Room schema and migrations, complete resumable stage state, FP16 mmap vectors and SigLIP2 retrieval, face embeddings/clustering, robust events, all-page PDF/video-keyframe indexing, real E2B acceptance, targeted multimodal verification, constrained answer composition, complete citation validation, result-set-aware PlanPatch, product flavors, benchmark module, 5k/20k gates, and final reports.

### Commands run

Repository inspection:

```powershell
git status --short
rg --files -g '!artifacts/**' -g '!**/build/**'
Get-Content android/settings.gradle.kts
rg -n '<targeted Gradle keys>' android/build.gradle.kts android/app/build.gradle.kts
rg --files android/app/src/test android/app/src/androidTest
```

Device preflight:

```powershell
adb devices -l
adb -s <serial> shell getprop ro.product.manufacturer
adb -s <serial> shell getprop ro.product.model
adb -s <serial> shell getprop ro.build.version.release
adb -s <serial> shell getprop ro.build.version.sdk
adb -s <serial> shell getprop ro.product.cpu.abi
adb -s <serial> shell getprop ro.soc.model
adb -s <serial> shell cat /proc/meminfo
adb -s <serial> shell df -h /data
```

Baseline verification, after setting the SDK variables for this process:

```powershell
.\gradlew.bat :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:connectedDebugAndroidTest --console=plain
```

Required build/install workflow:

```powershell
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant Debug
```

Launch and compact diagnostics:

```powershell
adb -s <serial> shell am start -W -n com.askphotos.android/.MainActivity
python C:\Users\anupk\.codex\skills\android-device-diagnostics\scripts\android_diagnostics.py --serial <serial> --package com.askphotos.android --minutes 5 --keywords "AndroidRuntime,FATAL EXCEPTION,ANR,AskPhotos" --max-lines 30 --out artifacts\phase0\diagnostics --screenshot
```

### Unit tests

- `QueryCompilerTest`: 5 tests, 0 failures, 0 errors, 0 skipped.
- Lint: 13 warnings, 0 errors. The warnings are retained for Phase 9 review; lint was not weakened.
- Gradle result: `BUILD SUCCESSFUL in 17s`; 81 actionable tasks (9 executed, 72 up-to-date).

### Connected-device tests

- `AskPhotosSmokeTest`: 1 test, 0 failures, 0 errors, 0 skipped.
- APK installed successfully on one authorized physical device.
- Explicit cold launch: status `ok`, activity `com.askphotos.android/.MainActivity`, total time 510 ms, wait time 512 ms.
- No `FATAL EXCEPTION`, target-package ANR, or target process crash marker was found in the captured diagnostics window.

### Device and backend

- Serial in reports: `R3C…WE4J` (masked).
- Manufacturer/model: Samsung SM-F731U.
- Android: 16, API 36.
- ABI/SoC: arm64-v8a, SM8550.
- RAM at preflight: 7,293,424 kB total; 2,775,300 kB available.
- `/data` at preflight: 223 GB total, 192 GB available.
- Active query backend in the baseline is deterministic unless a separately imported compatible LiteRT-LM model pack is present. Real Gemma E2B was **not demonstrated in Phase 0**.

### Metrics

- Debug APK: 226,977,071 bytes.
- Cold launch: 510 ms on the reference device.
- No Phase 0 vector, indexing-throughput, OCR-accuracy, Gemma, energy, or thermal performance claim is made.

### Failures and limitations

- The first Gradle attempt failed before compilation because the shell had no Android SDK environment variable. It was rerun successfully after deriving the SDK root from `adb`; no repository setting was changed.
- The first required build/install attempt met the same environment-only failure and then passed with the process-local SDK variables.
- The current diagnostics log included unrelated historical/system messages; only explicit target-package crash/ANR markers were evaluated.
- The captured screenshot exists, but automated image inspection could not decode it in this session. It is retained as an artifact and is not claimed as visually approved.
- No gallery seeding, cleanup, process-death recovery, real model, stress, macrobenchmark, or offline-flavor acceptance test was run in Phase 0.

### Artifacts

- `artifacts/phase0/gradle-baseline.txt`
- `artifacts/phase0/build-install.txt`
- `artifacts/phase0/device-preflight.txt`
- `artifacts/phase0/launch.txt`
- `artifacts/phase0/diagnostics/20260721_194946/summary.md`
- `artifacts/phase0/diagnostics/20260721_194946/logcat_filtered.txt`
- `artifacts/phase0/diagnostics/20260721_194946/screenshot.png`
- `android/app/build/reports/lint-results-debug.html`
- `android/app/build/reports/tests/testDebugUnitTest/index.html`
- `android/app/build/reports/androidTests/connected/debug/index.html`

### Next phase

Implement Phase 1 as a narrow deterministic vertical slice:

1. Complete and version the core planning, model, verification, evidence, vector, executor, and resource-manager contracts.
2. Strengthen plan validation for unsafe fields, contradictions, limit bounds, and stale follow-up references.
3. Introduce build-variant-safe dependency injection and fixture engines without release test hooks.
4. Add explicit Compose destinations for Onboarding, Gallery, Ask, Results, Index Manager, and Privacy while preserving the working app.
5. Run focused JVM tests, one connected UI query, build/install, launch, and screenshot; record the gate here before starting the sample-corpus phase.

## Ordered repository-specific implementation sequence

1. Phase 1: stabilize contracts, validator, DI boundary, navigation, and deterministic evidence-backed UI slice.
2. Phase 2: create the licensed core corpus, expected queries, debug MediaStore seeder, URI manifest, and safe cleanup.
3. Phase 3: migrate structured memory to Room/FTS and make every indexing stage idempotent and resumable.
4. Phase 4: add reference vectors, then mmap FP16 parity, pinned image/text encoder integration, fusion, duplicate collapse, and stress harness.
5. Phase 5: harden OCR gating, geometry, FTS, deterministic entities/totals, PDFs, and evidence highlighting.
6. Phase 6: integrate and benchmark real Gemma 4 E2B through pinned LiteRT-LM; keep E4B optional and capability-gated.
7. Phase 7: add bounded visual verification, deterministic aggregations, structured answers, and strict evidence validation.
8. Phase 8: add opt-in people embeddings/clusters, event memory, PlanPatch follow-ups, and video keyframes.
9. Phase 9: add offline/consumer variants, privacy/resource hardening, accessibility, macrobenchmarks, and release checks.
10. Phase 10: run the complete core/5k/20k device matrix and publish architecture, evaluation, device, license, and demo documentation with honest skips.

## Phase 1 — Contracts, validation, dependency boundary, and deterministic UI slice

Status: **Passed for the deterministic slice on the connected device. Real model integration is intentionally deferred.**

### Files changed

- `android/app/src/main/java/com/askphotos/android/GalleryModels.kt`
- `android/app/src/main/java/com/askphotos/android/OnDeviceEngineContracts.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryQueryPlanValidator.kt`
- `android/app/src/main/java/com/askphotos/android/QueryCompiler.kt`
- `android/app/src/main/java/com/askphotos/android/LiteRtLmQueryPlanner.kt`
- `android/app/src/main/java/com/askphotos/android/SerializedInferenceResourceManager.kt`
- `android/app/src/main/java/com/askphotos/android/AskPhotosApplication.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryViewModel.kt`
- `android/app/src/main/java/com/askphotos/android/MainActivity.kt`
- `android/app/src/debug/java/com/askphotos/android/FixtureEngines.kt`
- `android/app/src/test/java/com/askphotos/android/GalleryQueryPlanValidatorTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/AskPhotosSmokeTest.kt`

### Architecture decisions

- Expanded the plan vocabulary to all ten required intents and added typed media scope, filters, semantic/person/OCR clauses, grouping, aggregation, sorting, verification, and answer modes.
- Retained `terms`, `place`, and `baseResultIds` temporarily as compatibility fields for the existing deterministic repository. They are bounded and validated; later channel executors will consume the richer clauses.
- Kept media IDs as stable strings in engine contracts because this repository uses namespaced IDs for demo assets, MediaStore items, Photo Picker URIs, and SAF documents. Coercing these to `Long` would discard source identity.
- Added validation before execution for schema version, limits, unsafe SQL/path/URI-like text, clause counts, filter depth/ranges, contradictory hard constraints, aggregation consistency, and stale follow-up result references.
- Unknown model intent enums and unsupported JSON fields now invalidate the model response and trigger the deterministic safe fallback. A generative repair attempt remains Phase 6 work.
- Added a single serialized inference lease as the stable resource-management boundary. Thermal, priority, unload, and backend policies remain later work.
- Added deterministic engines and the reference vector implementation only to the `debug` source set, so release builds do not expose mutable test injection hooks.
- Added an immutable production `AppServices` dependency graph; there is no global test setter in release code.
- Added explicit Compose destinations for Onboarding, Gallery, Ask, Results, Index Manager, and Privacy. Query completion navigates from Ask to a separate Results surface. Routing remains a small state-based navigator in this one-module app; a Navigation Compose dependency was not introduced without a demonstrated need.

### Commands run

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:lintDebug :app:assembleDebug :app:connectedDebugAndroidTest --console=plain
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant Debug
adb -s <serial> logcat -c
adb -s <serial> shell am force-stop com.askphotos.android
adb -s <serial> shell am start -W -n com.askphotos.android/.MainActivity
python C:\Users\anupk\.codex\skills\android-device-diagnostics\scripts\android_diagnostics.py --serial <serial> --package com.askphotos.android --minutes 1 --keywords "AndroidRuntime,FATAL EXCEPTION,ANR,AskPhotos" --max-lines 25 --out artifacts\phase1\diagnostics --screenshot
```

The SDK environment variables were again set only for the Gradle/build process by deriving the SDK root from `adb`.

### Unit tests

- `QueryCompilerTest`: 5 tests, 0 failures, 0 errors.
- `GalleryQueryPlanValidatorTest`: 6 tests, 0 failures, 0 errors.
- Covered required intent set, bounds/unsafe content, contradictory constraints, stale follow-ups, missing follow-up context, and reference-vector upsert/filter/delete behavior.
- Focused JVM result: `BUILD SUCCESSFUL in 13s`.
- Lint and APK assembly passed in the connected gate; lint has no errors.

### Connected-device tests

- `AskPhotosSmokeTest`: 2 tests, 0 failures, 0 errors.
- The real UI executed the deterministic `Amsterdam` query, navigated to Results, and rendered `Found 4 matches`.
- The real UI navigated Privacy → Onboarding → Ask and verified each destination.
- Connected gate: `BUILD SUCCESSFUL in 29s`.
- Final APK build/install: successful on one device in 9 seconds.
- Fresh cold launch: status `ok`, total time 512 ms, wait time 514 ms.
- No target-package fatal exception or ANR was observed in the fresh captured window.

### Device and backend

- Device: Samsung SM-F731U, Android 16/API 36, arm64-v8a; serial remains masked as `R3C…WE4J`.
- Backend exercised: deterministic debug/local repository path. No network or external model service was used.
- Real Gemma E2B: **NOT RUN — no verified compatible model pack has been installed.**

### Metrics

- Cold launch after Phase 1: 512 ms.
- JVM tests: 11 passed.
- Connected UI tests: 2 passed.
- APK install gate: passed.

### Failures and limitations

- No compile or test repair cycle was needed; the batched Phase 1 change passed its first focused compilation.
- Automated screenshot decoding failed again even though the PNG is present and 206,817 bytes. UI correctness is supported by Compose semantics assertions, not a claimed visual QA pass.
- The debug fake generative engine is a contract fixture; it is not Gemma and is never presented as one.
- Phase 1 does not prove real semantic embeddings, OCR extraction accuracy, face identity, event quality, process-death indexing, or real-model grounding.

### Artifacts

- `artifacts/phase1/unit-tests.txt`
- `artifacts/phase1/connected-gate.txt`
- `artifacts/phase1/build-install.txt`
- `artifacts/phase1/launch.txt`
- `artifacts/phase1/diagnostics/20260721_195912/summary.md`
- `artifacts/phase1/diagnostics/20260721_195912/logcat_filtered.txt`
- `artifacts/phase1/diagnostics/20260721_195912/screenshot.png`
- `android/app/build/reports/tests/testDebugUnitTest/index.html`
- `android/app/build/reports/androidTests/connected/debug/index.html`
- `android/app/build/reports/lint-results-debug.html`

### Next phase

Phase 2 should add the reproducible corpus and safe device harness before further model work: pinned license manifest, deterministic synthetic documents, expected queries, core/stress profiles, a debug MediaStore seeder, URI run manifest, and cleanup that deletes only seeded URIs.

## Phase 2 — Licensed corpus and safe device harness

Status: **Partial. Corpus gate passed; connected seeding gate blocked after two transport repair attempts.**

### Files changed

- `tools/sample_gallery/manifest.yaml`
- `tools/sample_gallery/expected_queries.yaml`
- `tools/sample_gallery/build_sample_gallery.py`
- `tools/sample_gallery/verify_licenses.py`
- `tools/sample_gallery/generate_synthetic_documents.py`
- `tools/sample_gallery/generate_stress_gallery.py`
- `tools/device/common.py`
- `tools/device/preflight.py`
- `tools/device/seed_gallery.py`
- `tools/device/cleanup_gallery.py`
- `tools/device/collect_artifacts.py`
- `android/app/src/debug/AndroidManifest.xml`
- `android/app/src/debug/java/com/askphotos/android/TestGallerySeederReceiver.kt`
- `android/app/src/androidTest/java/com/askphotos/android/SeededGalleryTest.kt`
- `.gitignore`

### Architecture decisions

- Kept corpus creation and device orchestration as development-only Python tooling; no Python runtime was added to the Android app.
- Reused the repository's 14 independently pinned CC0 images and added two exact Commons sources: a pinned CC0 Singapore/Marina Bay derivative and a pinned public-domain Goa image.
- Made `manifest.yaml` JSON-compatible YAML so license verification has no PyYAML dependency.
- Generated exact synthetic CC0 OCR fixtures for receipts, Wi-Fi, boarding pass, hotel, menus, calendar, and a two-page PDF, plus a deliberately simple person/clothing relation image.
- Generated four deterministic visual variants per licensed raster source. The core profile contains 74 items, within the required 60–100 range.
- Stress generators are deterministic and preserve source/event mapping, but the 5k/20k profiles were not generated or device-tested in this phase.
- The debug-only receiver accepts only a validated run ID, reads only app-private staged files, writes only to `Pictures/AgenticGalleryTest/<run-id>/`, records every returned MediaStore URI, and cleanup accepts only those recorded `content://media` URIs.
- No debug seeder receiver or fixture engine is included in release source sets.

### Commands run

```powershell
python tools/sample_gallery/build_sample_gallery.py --profile core --output build/sample-gallery/core
python tools/sample_gallery/verify_licenses.py --gallery build/sample-gallery/core
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant Debug
python tools/device/preflight.py --serial <serial> --output artifacts/device-runs/phase2_20260721/preflight.json
python tools/device/seed_gallery.py --serial <serial> --package com.askphotos.android --gallery build/sample-gallery/core --run-id phase2_20260721 --artifacts artifacts/device-runs
```

Exact app-private cleanup after the failed staging probes:

```powershell
adb -s <serial> shell run-as com.askphotos.android pwd
adb -s <serial> shell run-as com.askphotos.android ls -la files/test-seed
adb -s <serial> shell run-as com.askphotos.android rm -rf -- files/test-seed/probe_123 files/test-seed/phase2_20260721
```

The resolved root was first verified as `/data/user/0/com.askphotos.android`. No shared-storage path or gallery URI was targeted by this cleanup.

### Unit and corpus tests

- First corpus build stopped on a pinned-dimension mismatch because the Commons API's requested width and returned derivative URL differed. The already pinned checksum matched a 1920×1079 derivative; only the manifest dimensions were corrected.
- Second corpus build: passed, 74 items generated; 63 image fixtures dated 2024.
- License/checksum verifier: passed, 74 media files and 17 license/source records.
- Seeder/debug Android compilation initially found one AndroidX API usage error (`instrumentation.arguments`); it was corrected to `InstrumentationRegistry.getArguments()`.
- Second Android compilation: `BUILD SUCCESSFUL in 2s`.
- Updated debug APK build/install: passed in 11 seconds.

### Connected-device tests

- Device preflight passed on the same Samsung SM-F731U; serial was masked in the JSON artifact.
- Core gallery seed: **FAILED BEFORE MEDIASTORE INSERTION**.
- Transport attempt 1 failed while writing the app-private manifest because nested `adb shell` redirection executed outside the `run-as` context.
- Transport attempt 2 used direct `exec-out … run-as … tee`, but `tee` did not observe EOF and the process timed out. The receiver never ran: no `status.json`, `seed-result.json`, or created URI list existed.
- Inspection confirmed the two staging directories contained no media files. Both exact app-private probe directories were removed after verifying the package-private root.
- MediaStore items created: 0. Personal gallery items read, modified, or deleted by the seeder: 0.
- Per the two-repair-cycle rule, no third transport approach was attempted in this phase.

### Device and backend

- Samsung SM-F731U, Android 16/API 36, arm64-v8a, SM8550.
- Preflight RAM: 7,293,424 kB total; 2,891,440 kB available.
- `/data`: 193 GB free at preflight.
- Backend exercised: corpus generator/license verifier and debug receiver compilation only. No real model was involved.

### Metrics

- Core corpus: 74 items.
- Exact 2024 photo ground truth: 63.
- License records: 17.
- Verified generated checksums: 74.
- Device seed/index/query metrics: not available because staging did not complete.

### Failures and limitations

- The Windows-to-`run-as` binary staging method remains unresolved. A future repair should use a bounded base64 stream, an app-owned `ParcelFileDescriptor`, or a debug content provider, then rerun the same safety gate.
- `tools/device/run_connected_acceptance.py` and `tools/device/install_model_pack.py` are not implemented yet.
- Safe MediaStore seed, discovery, connected instrumentation with `galleryRunId`, and URI cleanup are not demonstrated.
- No stress gallery was generated, installed, or benchmarked.
- Phase 3 must not start until the Phase 2 seed/verify/cleanup gate passes.

### Artifacts

- `build/sample-gallery/core/gallery-manifest.json`
- `build/sample-gallery/core/ground-truth-summary.json`
- `build/sample-gallery/ATTRIBUTION.md`
- `build/sample-gallery/LICENSES.json`
- `build/sample-gallery/CHECKSUMS.sha256`
- `artifacts/phase2-corpus-build.txt`
- `artifacts/phase2-seeder-compile.txt`
- `artifacts/phase2-build-install.txt`
- `artifacts/device-runs/phase2_20260721/preflight.json`

### Next phase

Resume Phase 2 only. Replace the host-to-app staging transport with one method that has explicit EOF semantics, seed a fresh run-specific album, execute `SeededGalleryTest` with expected count 74, trigger application discovery, collect the URI manifest, and clean exactly those 74 URIs. Stop again if two bounded repair attempts fail.

### Phase 2 continuation — transport audit

Status: **Still blocked at staging; two new bounded approaches were tested without any MediaStore insertion.**

Changes:

- Added and tested app-specific external staging, then removed it after Android 16 denied access to the app process.
- Added a debug-only write-only `TestSeedContentProvider` with a strictly validated run/archive URI.
- Changed the host harness to create one deterministic ZIP containing the manifest and 74 media files.
- Added guarded app-private ZIP extraction: canonical-path enforcement, entry allowlist, maximum 256 entries, 512 MB archive cap, 1 GB expanded cap, and cleanup on error.
- Removed the known-broken `run_as_write` transport helper.

Commands and results:

```powershell
adb -s <serial> push tools/sample_gallery/manifest.yaml /sdcard/Android/data/com.askphotos.android/files/test-seed/probe_ext_20260721/manifest.yaml
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant Debug
python tools/device/seed_gallery.py --serial <serial> --package com.askphotos.android --gallery build/sample-gallery/core --run-id phase2r2_20260721 --artifacts artifacts/device-runs
python tools/device/seed_gallery.py --serial <serial> --package com.askphotos.android --gallery build/sample-gallery/core --run-id phase2p_20260721 --artifacts artifacts/device-runs
```

- External staging probe: shell create/read/remove passed.
- External staging receiver: failed before manifest parsing with `EACCES`; Android's app process could not read the shell-owned file. The host removed the exact run-specific external staging directory.
- Provider/extractor compilation: passed in 4 seconds.
- Provider APK build/install: passed in 10 seconds.
- App-private provider seed: failed during extraction with `unexpected EOF`; the receiver removed its staging/input and had not begun MediaStore insertion.
- Provider-only diagnostic proved the cause: host archive was 12,188,898 bytes with SHA-256 `7afb2e8e…ad50e8`, while `adb shell content write` delivered only 36,363 bytes with SHA-256 `3c329d08…aed58c`, despite returning exit code 0.
- The exact diagnostic provider input was deleted and verified absent.

Safety result:

- Both continuation failures occurred before the MediaStore insertion loop.
- Created MediaStore URIs: 0.
- Deleted personal/shared gallery URIs: 0.
- Only exact test staging inputs were removed.

Next bounded repair should implement chunked writes with explicit offset/length/hash validation through the provider, or copy a pushed archive from `/data/local/tmp` under a verified `run-as` context. It must compare the complete device SHA-256 with the host hash before broadcasting the seed action.

### Phase 2 continuation — chunked provider transport

Status: **Protocol implemented and compiled; connected transfer exceeded the five-minute host bound before final hash verification.**

Implementation:

- `TestSeedContentProvider` now supports `init`, chunk writes, `finalize`, and `abort` for one validated run ID.
- Each chunk is stored app-privately under a canonical run root.
- Initialization validates total bytes, chunk size/count consistency, and a 64-character SHA-256.
- Finalization requires every chunk to have its exact expected length, reconstructs the archive in order, and verifies total bytes plus complete SHA-256 before producing `gallery.zip`.
- The host uses 32 KiB chunks, which remain below the 36,363-byte truncation observed for a single large `content write`.
- Seed broadcast remains after finalization, so incomplete or corrupt transfers cannot enter the MediaStore insertion loop.

Commands and results:

```powershell
python -m py_compile tools/device/common.py tools/device/seed_gallery.py tools/device/cleanup_gallery.py tools/device/preflight.py tools/device/collect_artifacts.py
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant Debug
python tools/device/seed_gallery.py --serial <serial> --package com.askphotos.android --gallery build/sample-gallery/core --run-id phase2chunk_20260721 --artifacts artifacts/device-runs
```

- Initial host run stopped locally on a Python syntax error before any device operation; `py_compile` passed after the one-line correction.
- Provider/debug Android compilation: passed in 2 seconds.
- APK build/install: passed in 9 seconds.
- The 12,188,898-byte archive requires 372 chunks. The five-minute wrapper expired before all chunks were written; inspection initially found 319 chunks and no seed status, proving the seed broadcast had not run.
- The timed-out wrapper left task-specific Python/adb children alive. Exact command-line matching found and terminated only two repository seed processes and three adb children associated with this package's `tee`/testseed URIs; the adb server and unrelated processes were preserved.
- The exact provider run was aborted again and `files/test-seed-input/phase2chunk_20260721` was verified absent.

Safety result:

- Final transfer hash verification was never reached.
- Seed broadcast was never issued.
- Created or deleted MediaStore URIs: 0.
- Personal gallery operations: 0.

Next repair should add resumable chunk discovery or allow a ten-minute host bound while retaining 30-second progress reporting. The host harness also needs signal handling that calls provider `abort` when interrupted, preventing child processes from outliving the orchestrator.

### Phase 2 continuation — resumable parallel chunk audit

Status: **Resumability and process bounds implemented; adb stdin transport proven unreliable at both 32 KiB and 16 KiB.**

Implementation:

- Provider `init` is idempotent for matching total size, chunk size/count, and SHA-256.
- Provider returns a validated present-chunk bitmap and supports an already-finalized archive.
- Host uploads only missing chunks and checkpoints progress every 50 chunks.
- Four independent writers reduced the 32 KiB run to 102 seconds for all 372 calls.
- Every adb subprocess now has an explicit timeout, preventing the prior orphan-writer condition.
- Provider finalization errors written to stderr are now treated as failures even when Android's `content` command returns exit code 0.

Commands and evidence:

```powershell
python -m py_compile tools/device/common.py tools/device/seed_gallery.py tools/device/cleanup_gallery.py
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant Debug
python tools/device/seed_gallery.py --serial <serial> --package com.askphotos.android --gallery build/sample-gallery/core --run-id phase2resume_20260721 --artifacts artifacts/device-runs
python tools/device/seed_gallery.py --serial <serial> --package com.askphotos.android --gallery build/sample-gallery/core --run-id phase2safe16_20260721 --artifacts artifacts/device-runs
```

Results:

- Android compilation passed in 1 second; Python bytecode validation passed; APK build/install passed in 10 seconds.
- 32 KiB run wrote all 372 chunk paths in 102 seconds, but device validation rejected chunk 0: 31,495 bytes received versus 32,768 expected.
- 16 KiB run used 744 chunks and stopped on one adb exit 255 after progress reached 400. The worker pool completed/closed without orphaned task processes.
- The provider bitmap reported 0 valid chunks. Direct file inspection showed variable stdin truncation: early examples ranged from 15,756 bytes to 40 bytes and zero bytes, despite 16,384-byte inputs.
- Both exact provider runs were aborted and their app-private input directories were verified absent.
- No seed status existed for either run; finalization and seed broadcast never succeeded.

Safety result:

- MediaStore insertions/deletions: 0.
- Personal gallery reads/writes/deletions: 0.
- Only exact app-private transfer roots were removed.

Conclusion: binary stdin through Android's `content write` command is not a reliable transport on this host/device combination, even below the earlier truncation size. The next bounded approach should send base64-encoded chunks through `ContentProvider.call` extras, with per-chunk decoded length/hash validation and the existing complete-archive SHA gate. A 16 KiB raw chunk encodes below the Windows command-line limit and avoids adb stdin entirely.

### Phase 2 continuation — base64 provider transport

Status: **Transfer gate passed; seed rolled back on the Android PDF directory rule.**

Implementation:

- Disabled binary `openFile`/stdin transport in the debug provider.
- Added `write_chunk` using URL-safe base64 through `ContentProvider.call` extras.
- Each chunk is decoded in the app, checked for its exact expected length and SHA-256, written to an `.importing` file, and atomically renamed.
- Host uses 12 KiB raw chunks and eight bounded workers, remaining below the Windows command-line limit without stdin.
- Added `TestSeedContentProviderTest`, which exercises init → write → finalize → abort with a 12 KiB payload on the physical device.
- Initialization and finalization now inspect provider stderr because Android's `content` command may return exit code 0 for provider errors.

Commands and results:

```powershell
python -m py_compile tools/device/common.py tools/device/seed_gallery.py
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant Debug
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.askphotos.android.TestSeedContentProviderTest --console=plain
python tools/device/seed_gallery.py --serial <serial> --package com.askphotos.android --gallery build/sample-gallery/core --run-id phase2base64_20260721 --artifacts artifacts/device-runs
```

- Python validation and Android compilation passed.
- APK installed successfully.
- Connected provider round trip: 1 test, 0 failures; `BUILD SUCCESSFUL in 23s`.
- The connected-test lifecycle uninstalled the target APK afterward. The first corpus initialization therefore failed with an explicit provider-not-found error before transfer; reinstall passed in 12 seconds.
- Full corpus retry transferred all 992 chunks in 149 seconds. Every call returned the expected decoded chunk SHA, and complete archive finalization succeeded for 12,188,898 bytes with SHA-256 `7afb2e8eaaec205cc65c0a2c209f60951acd991d21baf9ad4f85b56220ad50e8`.
- The receiver then inserted image items until reaching the PDF. Android rejected `application/pdf` in `Pictures/...` through `MediaStore.Files`: allowed primary directories were `Download` and `Documents`.
- The receiver rollback deleted all URIs it had created and removed its app-private transfer/staging input.
- Targeted queries against both `Pictures/AgenticGalleryTest/phase2base64_20260721/` and `Documents/AgenticGalleryTest/phase2base64_20260721/` returned `No result found`.

Safety result:

- Transfer integrity: passed at per-chunk and full-archive levels.
- Seed completion: failed and rolled back.
- Remaining run-specific MediaStore rows: 0.
- Personal gallery modifications/deletions: 0.
- Provider input directory: verified absent.

Next bounded repair: route images/videos to `Pictures/AgenticGalleryTest/<run-id>/` and PDFs to `Documents/AgenticGalleryTest/<run-id>/`, store both relative paths in the seed result, sum both paths in the connected visibility assertion, and retain URI-only cleanup across the combined set.

### Phase 2 continuation — safe seed and cleanup gate

Status: **Safe MediaStore seed/visibility/cleanup passed on the physical device. Production repository display remains a Phase 3 integration gate.**

Implementation:

- Images/videos now seed into `Pictures/AgenticGalleryTest/<run-id>/`.
- PDFs seed into Android-permitted `Documents/AgenticGalleryTest/<run-id>/`.
- One seed result records both relative paths and every created URI.
- Cleanup validates both exact path strings, deletes only recorded `content://media` URIs, and sums remaining rows across both directories.
- `SeededGalleryTest` requires positive counts in both Pictures and Documents and an exact combined expected count.
- Added `tools/device/run_connected_acceptance.py` to build/verify the corpus, build/install APKs, seed, instrument, collect artifacts, and clean in `finally`.
- Added `tools/device/install_model_pack.py` for a debug-only checksum-verified host → `/data/local/tmp` → `run-as` import, with exact temporary-file cleanup. It was bytecode-checked but not executed because no verified Gemma pack is present.

Commands and results:

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant Debug
python tools/device/seed_gallery.py --serial <serial> --package com.askphotos.android --gallery build/sample-gallery/core --run-id phase2final_20260721 --artifacts artifacts/device-runs
.\gradlew.bat :app:assembleDebugAndroidTest --console=plain
adb -s <serial> install -r -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w -r -e class com.askphotos.android.SeededGalleryTest -e galleryRunId phase2final_20260721 -e galleryExpectedCount 74 com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
python tools/device/cleanup_gallery.py --serial <serial> --package com.askphotos.android --run-id phase2final_20260721 --artifacts artifacts/device-runs
python -m py_compile tools/device/*.py tools/sample_gallery/*.py
```

- Android routing/test compilation: passed in 8 seconds.
- First APK install sync was aborted by the host adb connection. Device authorization and process state were healthy; one bounded installer rerun passed in 11 seconds.
- Full base64 transfer and seed passed in 150.5 seconds.
- Seed result: state `COMPLETE`, created count 74, two exact relative paths, staging removed `true`.
- Test APK assembled and installed without invoking Gradle's uninstalling connected-test lifecycle.
- Direct physical-device instrumentation: `OK (1 test)` in 0.023 seconds; exact expected count 74 passed and both media roots were non-empty.
- URI-only cleanup: requested 74, deleted 74, remaining 0.
- All device/sample Python tools bytecode-checked successfully.

Safety result:

- Seeded media were confined to the two run-specific directories.
- Cleanup targets came only from the receiver-created URI manifest.
- No broad shared-storage delete command was used.
- Both run directories were empty after cleanup.
- Serial is masked in host JSON artifacts.

Artifacts:

- `artifacts/device-runs/phase2final_20260721/seed-result.json`
- `artifacts/device-runs/phase2final_20260721/transfer-progress.json`
- `artifacts/device-runs/phase2final_20260721/seeded-gallery-instrumentation.txt`
- `artifacts/device-runs/phase2final_20260721/cleanup-result.json`
- `artifacts/phase2-media-routing-compile.txt`
- `artifacts/phase2-media-routing-build-install.txt`

Remaining gate item:

- The test proves MediaStore visibility through the intended scoped roots, but it does not yet prove production repository ingestion and Compose Gallery display of all 74 items. Phase 3 must add URI tombstone cleanup and an app-level import/display assertion so test-derived rows can also be removed without clearing unrelated app data.

## Phase 3 — production import/display/removal vertical slice

Status: **First Phase 3 gate passed on the physical device. The broader Room and process-death indexing gate is not yet complete.**

Files changed:

- `android/app/src/main/java/com/askphotos/android/GalleryDatabase.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryModels.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryRepository.kt`
- `android/app/src/main/java/com/askphotos/android/IndexScheduler.kt`
- `android/app/src/main/java/com/askphotos/android/MainActivity.kt`
- `android/app/src/debug/AndroidManifest.xml`
- `android/app/src/debug/java/com/askphotos/android/TestGallerySeederReceiver.kt`
- `android/app/src/androidTest/java/com/askphotos/android/SeededGalleryDisplayTest.kt`
- `tools/device/sync_seeded_gallery.py`
- `tools/device/run_connected_acceptance.py`

Architecture decisions:

- Production import is exercised through `GalleryRepository.importUris`; the debug harness does not insert application database rows directly.
- Run cleanup first cancels and waits for tagged indexing work, then deletes only non-demo rows whose exact `content://media` URIs occur in the seed manifest.
- Deletion, FTS removal, foreign-key cascades, and tombstone creation occur in one database transaction. Generated preview deletion is restricted to the canonical app-private `files/previews` root.
- The Gallery composable exposes a semantic count of non-demo imported items, allowing the test to verify production state rather than relying on the bundled demo count.
- The acceptance harness removes application rows before MediaStore URIs and retains MediaStore cleanup in a nested `finally` block.

Commands run:

```powershell
python -m py_compile tools/device/common.py tools/device/seed_gallery.py tools/device/cleanup_gallery.py tools/device/sync_seeded_gallery.py tools/device/run_connected_acceptance.py
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --console=plain
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant Debug
.\gradlew.bat :app:assembleDebugAndroidTest --console=plain
adb -s <serial> install -r -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python tools/device/run_connected_acceptance.py --skip-build --run-id phase3slice_20260721
```

Unit tests:

- `testDebugUnitTest`: 11 tests, 0 failures, 0 errors.
- Debug and Android-test Kotlin compilation: passed.
- Python harness bytecode validation: passed.

Connected-device tests:

- Device: Samsung SM-F731U, Android 16/API 36, arm64-v8a, SM8550, 7,293,424 kB reported RAM; serial masked in artifacts.
- App build/install: passed. The first helper invocation lacked inherited SDK environment and made no device change; the single retry with process-local SDK variables passed in 14 seconds.
- `SeededGalleryTest`: `OK (1 test)` in 0.024 seconds.
- `SeededGalleryDisplayTest`: `OK (1 test)` in 1.323 seconds.
- Production repository import: requested 74, changed 74, imported 74.
- Database cleanup: requested/matched/deleted 74, tombstones written 74, seeded rows remaining 0.
- MediaStore cleanup: requested/deleted 74, run-specific rows remaining 0.
- End-to-end acceptance elapsed 162 seconds, dominated by the integrity-checked corpus transfer.

Failures/limitations:

- Phase 3 is not complete: persistence still uses the existing versioned `SQLiteOpenHelper`, not Room.
- The current per-item index state is resumable, but the required per-stage state model, foreground initial import, tombstone reconciliation during scans, and a demonstrated process-death/restart gate remain outstanding.
- This slice verifies production metadata import and Gallery display; it does not claim real embedding, OCR, event, Gemma, or stress-profile acceptance.

Artifacts:

- `artifacts/phase3-focused-build.log`
- `artifacts/phase3-build-install.log`
- `artifacts/phase3-androidtest-build.log`
- `artifacts/phase3-connected-acceptance.log`
- `artifacts/device-runs/phase3slice_20260721/preflight.json`
- `artifacts/device-runs/phase3slice_20260721/database-import-result.json`
- `artifacts/device-runs/phase3slice_20260721/seeded-gallery-instrumentation.txt`
- `artifacts/device-runs/phase3slice_20260721/seeded-gallery-display-instrumentation.txt`
- `artifacts/device-runs/phase3slice_20260721/database-remove-result.json`
- `artifacts/device-runs/phase3slice_20260721/cleanup-result.json`

Next phase:

Continue Phase 3 narrowly: define Room entities/migrations for structured gallery memory and explicit per-stage checkpoints, adapt the current repository without changing UI behavior, then add an index interruption/restart test that proves no duplicate rows and exact completion counts on the same run-scoped corpus.

### Phase 3 continuation — Room ownership and stage checkpoints

Status: **Room migration and checkpoint implementation compiled; v3→v4 migration passed on-device. The fresh corpus recovery acceptance is NOT RUN because two bounded adb transfer attempts failed before seeding.**

Files changed:

- `android/gradle/libs.versions.toml`
- `android/build.gradle.kts`
- `android/app/build.gradle.kts`
- `android/app/schemas/com.askphotos.android.GalleryRoomDatabase/4.json`
- `android/app/src/main/java/com/askphotos/android/GalleryRoomDatabase.kt`
- `android/app/src/main/java/com/askphotos/android/GallerySqlDatabase.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryDatabase.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryModels.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryRepository.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryIndexWorker.kt`
- `android/app/src/debug/AndroidManifest.xml`
- `android/app/src/debug/java/com/askphotos/android/TestGallerySeederReceiver.kt`
- `android/app/src/androidTest/java/com/askphotos/android/IndexRecoveryTest.kt`
- `tools/device/test_index_recovery.py`
- `tools/device/run_connected_acceptance.py`

Architecture decisions:

- Room 2.8.4 is pinned through the version catalog and now owns `gallery-memory.db` at schema version 4.
- Explicit 1→2, 2→3, and 3→4 migrations preserve existing application data; no destructive fallback is configured.
- Room entities cover media, FTS4, OCR blocks, events, event membership, tombstones, query turns, and the new per-media stage checkpoint table.
- A narrow `SupportSQLiteDatabase` adapter preserves the current repository behavior while typed Room DAOs are introduced incrementally; UI and query APIs were not rewritten during the migration.
- Each media item receives eight versioned checkpoints: discovery, metadata, thumbnail, embedding, OCR, faces, events, and enrichment.
- A repeat import with unchanged URI/modified-time/size is now a true no-op rather than replacing the parent row and cascading derived data.
- RUNNING media/stages recover to PENDING with a local interruption marker.
- The indexing worker no longer executes face detection. Faces remain `SKIPPED` until an explicit people-search opt-in path is implemented.

Commands and results:

```powershell
.\gradlew.bat :app:compileDebugKotlin --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --console=plain
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant Debug
adb -s <serial> logcat -c
adb -s <serial> shell am force-stop com.askphotos.android
adb -s <serial> shell am start -W -n com.askphotos.android/.MainActivity
python C:\Users\anupk\.codex\skills\android-device-diagnostics\scripts\android_diagnostics.py --serial <serial> --package com.askphotos.android --minutes 2 --keywords "Room,cannot verify data integrity,Migration,FATAL EXCEPTION,SQLite" --max-lines 80 --out artifacts/device-runs/phase3-room-migration
python tools/device/run_connected_acceptance.py --skip-build --run-id phase3recovery_20260721
python tools/device/run_connected_acceptance.py --skip-build --run-id phase3recovery_20260721
```

- Room/Kotlin compilation passed; final focused build completed in 57 seconds.
- JVM suite remained 11 tests with 0 failures/errors; Android test sources compiled.
- Installed v3 database upgraded to v4 during a 537 ms cold activity launch. Diagnostics recorded `DB version upgrading from 3 to 4`, the app stayed alive, and no Room integrity, migration, fatal-exception, or SQLite error was emitted for the app process.
- First recovery acceptance attempt stopped on adb exit 255 while transferring validated chunk 481/992. No archive finalization or seed broadcast occurred.
- The one allowed resumable retry also stopped on an adb transport exit before finalization. Per the two-cycle rule, no further acceptance attempt was made.
- The exact provider transfer was aborted (`state=ABORTED, deleted=true`), its app-private input root was absent, and exact MediaStore queries returned `No result found` for both run-specific Pictures and Documents paths.

Connected recovery gate:

- `IndexRecoveryTest` and `test_index_recovery.py` are implemented and compile.
- They persist RUNNING checkpoints, assert repeat import changes zero rows, force-stop the package, invoke production recovery in a new process, and require unique rows, eight stage rows per media item, no RUNNING stage, no INDEXING media, and faces SKIPPED.
- Result: **NOT RUN** because the run-scoped corpus was never seeded in either bounded attempt. No pass is claimed.

Artifacts:

- `artifacts/phase3-room-compile.log`
- `artifacts/phase3-room-build-install.log`
- `artifacts/phase3-room-launch.txt`
- `artifacts/phase3-room-diagnostics-summary.txt`
- `artifacts/device-runs/phase3-room-migration/20260721_211206/`
- `artifacts/phase3-stages-build.log`
- `artifacts/phase3-recovery-build.log`
- `artifacts/phase3-recovery-build-install.log`
- `artifacts/phase3-recovery-acceptance.log`
- `artifacts/phase3-recovery-acceptance-retry.log`
- `artifacts/device-runs/phase3recovery_20260721/transfer-failure.json`
- `artifacts/phase3-room-final-build.log`

Next phase:

Do not broaden into embeddings yet. First rerun only the recovery gate after adb transport is stable, using the preserved compiled APK/tests but a fresh run ID. Once it passes, add scan reconciliation for inaccessible/deleted MediaStore rows and a foreground-service initial-import path; then close Phase 3 before starting vector work.

### Phase 3 closure — recovery, reconciliation, and import lifecycle

Status: **Phase 3 implementation gate passed on the physical device.** This closes storage ownership, resumable stage state, process interruption recovery, scoped deletion handling, and Android import lifecycle. It does not claim Phase 4 vector retrieval.

Files changed in this continuation:

- `tools/device/common.py`
- `tools/device/seed_gallery.py`
- `tools/device/test_common.py`
- `android/app/src/main/java/com/askphotos/android/GalleryRoomDatabase.kt`
- `android/app/schemas/com.askphotos.android.GalleryRoomDatabase/5.json`
- `android/app/src/androidTest/java/com/askphotos/android/GalleryRoomMigrationTest.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryModels.kt`
- `android/app/src/main/java/com/askphotos/android/MediaImporter.kt`
- `android/app/src/main/java/com/askphotos/android/MediaReconciler.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryDatabase.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryRepository.kt`
- `android/app/src/test/java/com/askphotos/android/MediaReconcilerTest.kt`
- `android/app/src/main/java/com/askphotos/android/InitialImportService.kt`
- `android/app/src/main/java/com/askphotos/android/MediaScanWorker.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryViewModel.kt`
- `android/app/src/main/java/com/askphotos/android/MainActivity.kt`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/androidTest/java/com/askphotos/android/InitialImportServiceTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/ProductionDatabaseOpenTest.kt`

Architecture decisions:

- The host transfer retries each exact chunk up to four times with bounded exponential backoff. Device-side decoded length/SHA checks and final archive SHA validation remain mandatory; retrying does not relax integrity.
- The legacy v3→Room migration rebuilds affected tables inside the migration transaction so primary-key nullability, FTS columns, foreign keys, indices, and auto-generated IDs exactly match Room while preserving media, OCR, events, tombstones, and query history.
- Schema v5 records `access_state` and `last_seen_at` per media item.
- A MediaStore item missing from a successfully scanned, fully permission-covered media kind is treated as deleted and tombstoned. A missing item under partial/no permission or a failed collection query is marked inaccessible and is not deleted.
- Only accessible media enter Gallery/query results; selected Photo Picker and SAF items are not removed by MediaStore reconciliation.
- User-started full import uses a private `dataSync` foreground service. App-resume incremental scans use unique WorkManager work. The repository hands pending indexing to the existing resumable indexing WorkManager.
- The merged debug manifest still contains no `INTERNET` or `MANAGE_EXTERNAL_STORAGE` permission.

Commands and results:

```powershell
python -m unittest test_common.py -v
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant Debug
adb -s <serial> install -r -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w -r -e class com.askphotos.android.GalleryRoomMigrationTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
python tools/device/run_connected_acceptance.py --skip-build --run-id phase3recovery3_20260721
adb -s <serial> shell am instrument -w -r -e class com.askphotos.android.GalleryRoomMigrationTest,com.askphotos.android.InitialImportServiceTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> shell am instrument -w -r -e class com.askphotos.android.ProductionDatabaseOpenTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
python tools/device/run_connected_acceptance.py --skip-build --run-id phase3final_20260721
.\gradlew.bat :app:lintDebug --console=plain
```

Automated results:

- Host transport regression tests: 2 tests, passed.
- JVM tests: 14 tests, 0 failures, 0 errors. This includes full-vs-partial reconciliation and visible-item retention cases.
- `GalleryRoomMigrationTest`: v3 data-preserving migration passed on-device in 0.068 seconds before v5, then passed as part of the v3→v5 two-test run.
- `InitialImportServiceTest`: private/non-exported `dataSync` service and foreground notification channel passed.
- `ProductionDatabaseOpenTest`: the installed production database opened at v5 and preserved the bundled library; `OK (1 test)` in 0.044 seconds.
- Final lint: `BUILD SUCCESSFUL` in 65 seconds.

Connected acceptance, final v5 run `phase3final_20260721`:

- Device: Samsung SM-F731U, Android 16/API 36, arm64-v8a, SM8550; serial masked in artifacts.
- Transfer: 992/992 integrity-checked chunks. Twenty-four transient calls were retried successfully within their per-call cap.
- Initial production import: requested 74, changed 74, imported 74.
- Repeat production import: changed 0; unique database rows remained 74.
- Forced-stop fixture: 3 RUNNING stages persisted before process stop.
- Recovery: 74 unique rows, 592 stage rows (74 × 8), 0 RUNNING stages, 0 INDEXING media rows.
- `IndexRecoveryTest`: `OK (1 test)`.
- Production Gallery display: `OK (1 test)` with 74 imported items.
- Database cleanup: matched/deleted 74, tombstones written 74, remaining 0; one app-private generated PDF preview deleted.
- MediaStore cleanup: requested/deleted 74 exact recorded URIs, remaining 0 across run-specific Pictures and Documents paths.
- Acceptance elapsed: 163.6 seconds.
- Personal gallery deletion/modification: 0. Cleanup targets came only from the receiver-created URI manifest.

Earlier bounded failure and repair:

- Run `phase3recovery2_20260721` proved transfer completion and safe 74-item cleanup but exposed Room validation of legacy nullable text primary keys. No recovery pass was claimed.
- The migration was repaired by transactional table reconstruction and covered by `GalleryRoomMigrationTest` before the successful recovery/final runs.

Artifacts:

- `artifacts/phase3-migration-repair-build.log`
- `artifacts/phase3-migration-repair-install.log`
- `artifacts/phase3-migration-instrumentation.txt`
- `artifacts/phase3-reconciliation-build.log`
- `artifacts/phase3-service-build.log`
- `artifacts/phase3-service-build-install.log`
- `artifacts/phase3-service-instrumentation.txt`
- `artifacts/phase3-production-db-instrumentation.txt`
- `artifacts/phase3-final-lint.log`
- `artifacts/device-runs/phase3recovery3_20260721/`
- `artifacts/device-runs/phase3final_20260721/`

Remaining limitations and next phase:

- Phase 4 is not implemented: there is no real image/text embedding model, reference vector contract implementation, FP16 memory-mapped index, RRF fusion, duplicate collapse, event diversity, or 5k/20k vector benchmark evidence yet.
- MediaStore reconciliation logic is covered at the decision layer and exercised through the v5 app lifecycle; a future device case should explicitly revoke selected-photo access during a run to validate OEM permission behavior.
- Begin Phase 4 with the deterministic reference vector index and parity tests before integrating or converting the pinned on-device image/text model.

## Phase 4A — exact vector infrastructure and target-scale benchmark

Status: **Vector correctness, persistence, native parity, and the 5k/20k scan gate passed.** This is Phase 4A only: no real image/text encoder or hybrid fusion is claimed yet.

Files changed:

- `android/app/src/main/java/com/askphotos/android/VectorMath.kt`
- `android/app/src/main/java/com/askphotos/android/ReferenceVectorIndex.kt`
- `android/app/src/main/java/com/askphotos/android/Fp16.kt`
- `android/app/src/main/java/com/askphotos/android/MmapFp16VectorIndex.kt`
- `android/app/src/main/java/com/askphotos/android/NativeVectorScanner.kt`
- `android/app/src/main/cpp/CMakeLists.txt`
- `android/app/src/main/cpp/native_vector_scanner.cpp`
- `android/app/src/debug/java/com/askphotos/android/FixtureEngines.kt`
- `android/app/build.gradle.kts`
- `android/app/src/test/java/com/askphotos/android/Fp16Test.kt`
- `android/app/src/test/java/com/askphotos/android/ReferenceVectorIndexTest.kt`
- `android/app/src/test/java/com/askphotos/android/MmapFp16VectorIndexTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/NativeVectorIndexParityTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/VectorIndexBenchmarkTest.kt`

Architecture decisions:

- `ReferenceVectorIndex` is now production code and the correctness oracle. It requires a fixed dimension, finite/nonzero vectors, L2 normalization, bounded `topK`, stable ID tie ordering, and synchronized mutation/search.
- `MmapFp16VectorIndex` stores immutable generation snapshots with a contiguous little-endian FP16 matrix, ID table, format/dimension metadata, and whole-file SHA-256.
- Mutations first enter an fsynced append-only journal with per-record CRC32. Upserts/deletes are idempotent when a crash occurs after pointer replacement but before journal truncation.
- Compaction writes and validates a new immutable snapshot, atomically swaps a small pointer, then truncates the journal. Existing mapped generations are never overwritten.
- A truncated/corrupt journal tail is removed only after the last complete CRC-valid record. A corrupt active snapshot fails closed, is quarantined, persists a rebuild marker, and returns no partial results until gallery reindexing rebuilds it.
- Kotlin FP16 scanning remains the portable fallback. The optimized Android path passes the direct mapped buffer, row offsets, and normalized query to JNI.
- ARM64 uses NEON FP16 conversion/fused multiply-add compiled for ARMv8.2+FP16. Other packaged ABIs use the scalar native fallback. NDK `28.2.13676358` and CMake `3.22.1` are pinned.
- The model dimension in this benchmark is 768, matching the planned SigLIP2-class retrieval pack; this does not imply that a real encoder has been integrated.

Correctness coverage:

- FP16 representative-value round trips.
- Dimension, NaN, and zero-vector rejection.
- Stable ties, update, delete, filter, and `topK` behavior.
- Reference-vs-FP16 ranking and score parity across compaction and multiple reopens.
- Complete-record recovery after a truncated journal tail.
- Fail-closed snapshot corruption, persistent rebuild marker, and successful rebuild.
- On-device native-vs-reference top-50 parity for 1,024 × 768 vectors across three queries, including deliberately unaligned vector-matrix offsets.

Commands and results:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant Debug
adb -s <serial> install -r -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w -r -e class com.askphotos.android.NativeVectorIndexParityTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> shell am instrument -w -r -e class com.askphotos.android.VectorIndexBenchmarkTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --console=plain
adb -s <serial> shell am instrument -w -r -e class com.askphotos.android.NativeVectorIndexParityTest,com.askphotos.android.VectorIndexBenchmarkTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
```

- JVM suite: 20 tests, 0 failures, 0 errors.
- Final build/unit/lint: `BUILD SUCCESSFUL` in 95 seconds.
- Native parity: `OK (1 test)` in 1.468 seconds; identical top-50 IDs and score error within 0.003.
- Final combined native parity + performance run: `OK (2 tests)` in 30.328 seconds.
- Native libraries were produced for arm64-v8a, armeabi-v7a, x86, and x86_64.

Reference-device metrics, Samsung SM-F731U / SM8550:

| Profile | Dimension | Snapshot | Build | Cold scan | Warm median | p95 | Total PSS | Native heap |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 5k | 768 | 7,760,090 B | 5,439 ms | 36 ms | 21 ms | 34 ms | 116,591 KB | 8,631,752 B |
| 20k | 768 | 31,040,090 B | 21,860 ms | 15 ms | 15 ms | 15 ms | 212,594 KB | 8,788,152 B |

The initial Kotlin-only benchmark was retained as failure evidence: 5k p95 232 ms passed, while 20k p95 893 ms failed the provisional 500 ms gate. Native FP16/NEON reduced the final 20k p95 to 15 ms without weakening the threshold. No ANR or OOM occurred.

Artifacts:

- `artifacts/phase4-vector-unit.log`
- `artifacts/phase4-vector-focused-unit.log`
- `artifacts/phase4-vector-build.log`
- `artifacts/phase4-vector-build-install.log`
- `artifacts/phase4-vector-benchmark-instrumentation.txt`
- `artifacts/phase4-vector-benchmark-results.json`
- `artifacts/phase4-native-build.log`
- `artifacts/phase4-native-build-install.log`
- `artifacts/phase4-native-parity-instrumentation.txt`
- `artifacts/phase4-native-benchmark-instrumentation.txt`
- `artifacts/phase4-native-benchmark-results.json`
- `artifacts/phase4-final-build-lint.log`
- `artifacts/phase4-final-device-instrumentation.txt`
- `artifacts/phase4-final-vector-results.json`

Remaining Phase 4 work:

- Integrate a pinned, licensed, Android-compatible image/text encoder behind `ImageTextEmbeddingEngine`; record preprocessing, tokenizer, dimension, checksum, and model version.
- Persist `media_embedding` metadata and bind vector generations to model versions.
- Run semantic Recall@K against the licensed core gallery.
- Add metadata/vector/OCR reciprocal-rank fusion, perceptual-hash duplicate collapse, and event-aware diversity.
- Run full stress-gallery indexing; the current 5k/20k evidence covers exact vector persistence/search only, not image decoding or encoder inference.

## Phase 4B — signed SigLIP2/LiteRT retrieval boundary and hybrid execution

Status: **Runtime, pack security, resumable embedding path, and RRF integration are implemented and tested. Real SigLIP2 inference/Recall@K is NOT RUN because no converted `.agretrieval` pack exists in the workspace or installed app.**

Files changed:

- `android/gradle/libs.versions.toml`
- `android/app/build.gradle.kts`
- `android/app/src/main/java/com/askphotos/android/RetrievalModelPack.kt`
- `android/app/src/main/java/com/askphotos/android/LiteRtImageTextEmbeddingEngine.kt`
- `android/app/src/main/java/com/askphotos/android/SemanticVectorStore.kt`
- `android/app/src/main/java/com/askphotos/android/EmbeddingIndexWorker.kt`
- `android/app/src/main/java/com/askphotos/android/MmapFp16VectorIndex.kt`
- `android/app/src/main/java/com/askphotos/android/AskPhotosApplication.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryDatabase.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryRepository.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryViewModel.kt`
- `android/app/src/main/java/com/askphotos/android/MainActivity.kt`
- `android/app/src/test/java/com/askphotos/android/RetrievalPackValidationTest.kt`
- `android/app/src/test/java/com/askphotos/android/HybridRankFusionTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/RetrievalPackSecurityTest.kt`
- `tools/model-conversion/convert_siglip2.py`
- `tools/model-conversion/build_retrieval_pack.py`
- `tools/model-conversion/PackManifestSigner.java`
- `tools/model-conversion/requirements.txt`
- `tools/model-conversion/README.md`

Architecture decisions:

- Generic Android inference is pinned to official LiteRT `2.1.0`; LiteRT-LM remains separately pinned for Gemma.
- A retrieval pack is a bounded ZIP containing an exact manifest, detached signature, image/text `.tflite` encoders, compact exported tokenizer vocabulary, and license. Model binaries stay out of Git.
- The exact manifest bytes are signed by the APK signing key. Import verifies the active APK certificate public key, signature algorithm, pinned 40-character source revision, Apache-2.0 source license, safe filenames, exact entry set, declared sizes, SHA-256 for every artifact, free space, and atomic generation activation.
- Conversion uses pinned `litert-torch==0.8.0`, separate normalized image/text wrappers, deterministic parity inputs, a maximum absolute-error gate, and independent tensor inspection before packaging. A pack cannot be built without an explicit similarity threshold calibrated on the labeled core corpus.
- Android preprocessing is manifest-bound: RGB, half-pixel Keys bicubic resize, explicit NCHW/NHWC layout, mean/std, fixed text length/type, exported Gemma/SentencePiece vocabulary, EOS/padding, and forced L2 output normalization.
- Image inference is batched under one serialized model lease (four items) so the LiteRT model is loaded once per WorkManager batch. Text inference is interactive and serialized through the same resource manager.
- Vector directories are isolated by pack ID, version, pinned source revision, and dimension. Stage producer versions force selective reindex after pack replacement; deleted/inaccessible IDs are reconciled from the active FP16 index.
- Query execution retains deterministic metadata/OCR/tag retrieval, adds thresholded text-to-image vector search, and fuses ranked channels using weighted reciprocal-rank fusion. Vector evidence records include the exact producer version.
- A count involving semantic retrieval is explicitly `ESTIMATED_FROM_RETRIEVAL`; it is no longer described as exact or a complete predicate scan.

Commands and results:

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:compileDebugKotlin
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant Debug
.\gradlew.bat --no-daemon :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.askphotos.android.RetrievalPackSecurityTest,com.askphotos.android.NativeVectorIndexParityTest'
adb -s <serial> shell am start -W -n com.askphotos.android/.MainActivity
python C:\Users\anupk\.codex\skills\android-device-diagnostics\scripts\android_diagnostics.py --serial <serial> --package com.askphotos.android --minutes 10 --screenshot --keywords "AndroidRuntime,FATAL EXCEPTION,ANR,LiteRT,retrieval,EmbeddingIndexWorker" --out artifacts\phase4b-device-diagnostics
```

Automated results:

- JVM suite: 27 tests, 0 failures, 0 errors, 0 skipped.
- Focused connected suite: 2 tests, 0 failures/errors/skips in 2.705 seconds.
- `RetrievalPackSecurityTest`: a structurally valid foreign-signed pack was rejected without changing the active generation (0.05 seconds).
- `NativeVectorIndexParityTest`: native FP16 ranking parity remained green (1.402 seconds).
- Required build/install workflow: Debug APK assembled and installed successfully on one Samsung SM-F731U Android 16 device.
- Cold activity launch: status `ok`, 545 ms reported by `am start -W`.
- Target-process diagnostics found no `FATAL EXCEPTION`, target ANR, or `Process: com.askphotos.android` crash marker in the captured launch window.
- App-private `files/models/retrieval` was absent and no `.agretrieval` file was found in the workspace. Therefore real encoder load, image/text parity on Android, semantic Recall@K, and encoder throughput are honestly NOT RUN.

Artifacts:

- `artifacts/phase4b-litert-dependency.txt`
- `artifacts/phase4b-boundary-test.log`
- `artifacts/phase4b-index-fusion-test.log`
- `artifacts/phase4b-connected-boundary.log`
- `artifacts/phase4b-device-diagnostics/20260721_224938/device.txt`
- `artifacts/phase4b-device-diagnostics/20260721_224938/logcat_filtered.txt`
- `artifacts/phase4b-device-diagnostics/20260721_224938/screenshot.png`
- `android/app/build/test-results/testDebugUnitTest/`
- `android/app/build/outputs/androidTest-results/connected/debug/`

Failures and limitations:

- The first required install invocation lacked process-local `ANDROID_HOME`; it failed before build configuration. The corrected invocation derived the SDK from `adb`, did not modify `local.properties`, and passed.
- `connectedDebugAndroidTest` uninstalled the target app during cleanup. The required install workflow was rerun before launch diagnostics.
- The diagnostics helper captured broad Samsung system logs; targeted inspection found only shell `AndroidRuntime` lifecycle lines and no target crash/ANR markers.
- Real conversion may expose unsupported `torch.export` operators in SigLIP2. The conversion tool fails rather than weakening parity or emitting a pack; this path has not been executed in the current environment.
- Duplicate collapse and event-aware diversity are still pending Phase 4C. Full 5k/20k encoder indexing is also pending an actual signed pack.

Next phase:

- Produce a pinned converted pack outside Git, calibrate the threshold on the licensed core corpus, import it through the app, run Android image/text golden parity and Recall@K, then add perceptual-hash duplicate collapse and event-aware diversity.

## Phase 4C — deterministic visual quality, duplicate collapse, and event diversity

Status: **Implemented and verified with JVM tests plus a focused Room migration gate on the connected physical device. This phase does not change the honest real-model boundary: SigLIP2 inference and Recall@K remain NOT RUN without a signed retrieval pack.**

Files changed:

- `android/app/src/main/java/com/askphotos/android/GalleryModels.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryRoomDatabase.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryDatabase.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryRepository.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryIndexWorker.kt`
- `android/app/src/main/java/com/askphotos/android/VisualFeatureExtractor.kt`
- `android/app/src/main/java/com/askphotos/android/ResultPresentationRanker.kt`
- `android/app/src/main/java/com/askphotos/android/MainActivity.kt`
- `android/app/src/test/java/com/askphotos/android/VisualFeatureExtractorTest.kt`
- `android/app/src/test/java/com/askphotos/android/ResultPresentationRankerTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/GalleryRoomMigrationTest.kt`
- `android/app/schemas/com.askphotos.android.GalleryRoomDatabase/6.json`

Architecture decisions:

- Indexing now computes a deterministic 64-bit low-frequency DCT perceptual hash, Laplacian-detail score, exposure/clipping score, and bounded composite quality score from the already-decoded thumbnail. No model or additional media decode is required.
- Room schema version 6 stores the perceptual hash as unsigned hexadecimal text so every `Long` bit pattern round-trips through SQLite, plus nullable visual-quality fields. Migration `5 -> 6` is additive and the legacy `3 -> 6` chain is device-tested.
- Duplicate collapse is conservative: exact hashes collapse regardless of time, while near hashes require Hamming distance at most 6 and a 15-second burst window. Media kinds must match. The best representative is selected by quality, resolution, and stable rank; hidden members remain available as `duplicateIds` and the UI shows a `+N similar` badge.
- Exact aggregate counts continue to operate on the complete uncollapsed result set. Collapse is presentation-only and therefore cannot silently change deterministic counts.
- Broad photo searches receive a stable event round-robin in the first result window after RRF and duplicate collapse. It improves event coverage without dropping candidates or changing narrow-query semantics.

Commands and results:

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebugAndroidTest
.\gradlew.bat --no-daemon :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.askphotos.android.GalleryRoomMigrationTest,com.askphotos.android.ProductionDatabaseOpenTest'
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant Debug
adb -s <serial> shell am force-stop com.askphotos.android
adb -s <serial> shell am start -W -n com.askphotos.android/.MainActivity
```

Automated and device results:

- JVM suite: 32 tests, 0 failures, 0 errors after one fixture-only repair cycle.
- Android-test APK assembled successfully.
- Focused connected suite: 2 tests, 0 failures/errors on Samsung SM-F731U, Android 16 / API 36.
- The connected migration test created the legacy v3 database and opened it through the full migration chain to v6, then asserted the new visual-feature columns.
- Required debug build/install workflow succeeded on one connected device.
- Cold launch completed with status `ok` in 516 ms; the activity was top-resumed and the target process remained alive.
- Compact post-launch inspection found no target `FATAL EXCEPTION` or ANR marker in the last 300 filtered log lines.

Artifacts:

- `artifacts/phase4c-focused-test.log` (initial build reached Android-test compilation before the command timeout)
- `artifacts/phase4c-focused-test-rerun.log` (two fixture failures)
- `artifacts/phase4c-repair1-test.log` (final green JVM/build gate)
- `artifacts/phase4c-connected-migration.log` (physical-device gate)
- `android/app/build/test-results/testDebugUnitTest/`
- `android/app/build/outputs/androidTest-results/connected/debug/`

Failures and limitations:

- The first focused command exceeded its two-minute host timeout after production/JVM compilation and while Android-test compilation was finishing; the incremental continuation exposed two test-fixture failures.
- The original synthetic checks used a one-pixel checkerboard that correctly aliases during 32×32 downsampling and a brightness transform that clipped pixels. The fixtures were changed to resolvable blocks and non-clipping luminance; production thresholds were not weakened.
- Duplicate grouping currently uses a bounded quadratic pass over the returned candidate set, not over the gallery. It is appropriate for the plan limit but should remain bounded if limits change.
- Event diversity uses existing deterministic event membership. Rich event clustering with GPS, people overlap, prototypes, and user corrections remains Phase 8 work.

Next phase:

- Proceed to Phase 5 as a separate slice: OCR likelihood gating, structured OCR geometry/entities, deterministic document extraction, receipt-total evidence scoring, and the exact synthetic Swiggy acceptance query. Real SigLIP2 pack conversion/import remains an independent external-artifact gate.

## Phase 5 — OCR gating, structured document facts, and exact receipt evidence

Status: **Deterministic document extraction and the synthetic Swiggy receipt acceptance are implemented and physical-device verified. The bundled OCR recognizer remains ML Kit Latin; replacement/augmentation with a benchmarked open-source multilingual mobile OCR pack is still required for the full architecture target.**

Files changed:

- `android/app/src/main/java/com/askphotos/android/GalleryModels.kt`
- `android/app/src/main/java/com/askphotos/android/OcrLikelihoodGate.kt`
- `android/app/src/main/java/com/askphotos/android/DocumentFactExtractor.kt`
- `android/app/src/main/java/com/askphotos/android/DocumentAnswerSelector.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryRoomDatabase.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryDatabase.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryRepository.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryIndexWorker.kt`
- `android/app/src/main/java/com/askphotos/android/QueryCompiler.kt`
- `android/app/src/test/java/com/askphotos/android/DocumentFactExtractorTest.kt`
- `android/app/src/test/java/com/askphotos/android/DocumentAnswerSelectorTest.kt`
- `android/app/src/test/java/com/askphotos/android/OcrLikelihoodGateTest.kt`
- `android/app/src/test/java/com/askphotos/android/QueryCompilerTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/GalleryRoomMigrationTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/ReceiptDocumentAcceptanceTest.kt`
- `android/app/schemas/com.askphotos.android.GalleryRoomDatabase/7.json`

Architecture decisions:

- A cheap OCR likelihood gate runs after thumbnail labels and before text recognition. PDFs are always retained; document/screenshot filenames, document-like labels, screen geometry, and sampled edge density contribute bounded deterministic signals. Ordinary low-detail photos are marked OCR `SKIPPED`, not falsely `COMPLETE`.
- Room v7 adds normalized OCR text, language, page index, optional media timestamp, and a typed `ocr_entity` table with normalized values, labels, confidence, normalized regions, and producer versions. Existing accessible non-demo items are selectively returned to pending OCR work during migration.
- Deterministic extraction covers amounts, scored receipt totals, dates, phone numbers, emails, URLs, order IDs, flight numbers, merchant candidates, and password-like fields. Receipt scoring rewards `Grand Total`, `Amount Paid`, and lower-page currency evidence while penalizing subtotal, tax, discount, savings, and tip lines.
- The rendered receipt number is read from a stored `RECEIPT_TOTAL` entity. Evidence uses the entity producer version and exact OCR region; the answer layer does not parse or generate a replacement number.
- `latest` compiles to `CAPTURE_TIME_DESC`. Only the first plan-sorted matching document may answer the fact: a newer failed or ambiguous receipt cannot be silently skipped in favor of an older total.
- Document scope includes PDFs, indexed OCR, and conservative document-like metadata so a failed receipt remains visible to coverage/safe-failure logic.

Commands and results:

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebugAndroidTest
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant Debug
python tools\sample_gallery\verify_licenses.py --gallery build\sample-gallery\core
python tools\device\seed_gallery.py --serial <serial> --package com.askphotos.android --gallery build\sample-gallery\core --run-id <run-id> --artifacts artifacts\device-runs
python tools\device\sync_seeded_gallery.py --serial <serial> --package com.askphotos.android --run-id <run-id> --action import --artifacts artifacts\device-runs
adb -s <serial> shell am instrument -w -r -e class com.askphotos.android.ReceiptDocumentAcceptanceTest -e galleryRunId <run-id> com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
python tools\device\sync_seeded_gallery.py --serial <serial> --package com.askphotos.android --run-id <run-id> --action remove --artifacts artifacts\device-runs
python tools\device\cleanup_gallery.py --serial <serial> --package com.askphotos.android --run-id <run-id> --artifacts artifacts\device-runs
adb -s <serial> shell am instrument -w -r -e class com.askphotos.android.GalleryRoomMigrationTest,com.askphotos.android.ProductionDatabaseOpenTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
```

Automated and device results:

- JVM suite: 37 tests in 13 suites, 0 failures/errors/skips.
- Android application and test APKs assembled successfully; the required debug APK install workflow succeeded.
- Corpus/license gate: 74 items, 17 license records, and all generated checksums verified.
- Final receipt acceptance on Samsung SM-F731U / Android 16: `OK (1 test)` in 39.459 seconds. This test waits on structured index completion and asserts the receipt media, exact text `INR 1,248`, `document-facts-v2` provenance, a four-value normalized OCR region, evidence-ID equality, `CAPTURE_TIME_DESC`, and `EXACT` result labeling.
- Room device gate: legacy v3-to-v7 migration plus production database open, `OK (2 tests)` in 0.28 seconds.
- Final run safety: 74 run-scoped MediaStore items were created and imported; database cleanup removed 74 rows and wrote 74 tombstones; MediaStore cleanup deleted exactly the 74 recorded URIs; both remaining counts were zero.
- Final installed-app cold launch completed with status `ok` in 513 ms; the target process remained alive and compact inspection found no target crash or ANR marker.

Artifacts:

- `artifacts/phase5-focused-test.log`
- `artifacts/phase5-acceptance-compile.log`
- `artifacts/phase5-repair1-test.log`
- `artifacts/phase5-room-v7-instrumentation.txt`
- `artifacts/device-runs/phase5_receipt_20260721a/` (interrupted-transfer resume and first exactness failure, followed by complete cleanup)
- `artifacts/device-runs/phase5_receipt_20260721b/receipt-acceptance-instrumentation.txt`
- `artifacts/device-runs/phase5_receipt_20260721b/seed-result.json`
- `artifacts/device-runs/phase5_receipt_20260721b/database-import-result.json`
- `artifacts/device-runs/phase5_receipt_20260721b/database-remove-result.json`
- `artifacts/device-runs/phase5_receipt_20260721b/cleanup-result.json`
- `android/app/build/test-results/testDebugUnitTest/`

Failures and limitations:

- The phone disconnected during the first seed transfer at chunk 700/992. The transfer bitmap was preserved app-privately, the same run resumed at the missing chunks, and no MediaStore URI had been created before finalization.
- The first on-device OCR query returned the correct stored total, producer, region, and ordering, but labeled the answer `PARTIAL_INDEX` because an unrelated failed PDF affected gallery-wide document coverage. The fix makes the first sorted matching document authoritative and adds a regression proving an older total cannot hide a newer ambiguous receipt. The second full run passed as `EXACT`.
- OCR recognition is currently the bundled on-device Latin ML Kit model. Additional scripts, PaddleOCR-class open-source engine integration, benchmarked language coverage, and license/model-pack reporting remain incomplete.
- PDF OCR still covers the first rendered page only. Per-page child media and page-specific evidence for every PDF page remain pending.
- The 39.459-second acceptance time includes waiting for the run-scoped core gallery indexing on the reference device; it is not an ordinary warm query latency measurement.

Next phase:

- Continue with Phase 6 as a separate model-runtime slice: signed Gemma 4 E2B pack acceptance, constrained JSON plan-quality tests in English/Hindi/Hinglish, bounded repair, resource load/unload evidence, and an honest E4B capability skip or comparison. The actual `.litertlm` pack is still an external artifact gate.

## Phase 6 — Gemma 4 planning, E2B/E4B selection, and verified download (22 July 2026)

Status: **The constrained Gemma planning boundary and verified E2B/E4B model-management path are implemented. The Settings state defect is fixed. A pinned E2B pack was downloaded, independently SHA-256 verified, atomically activated, and used by LiteRT-LM on the connected device to compile valid English, Hindi, and Hinglish typed plans without deterministic fallback. Engine load/generation/close timing, peak memory, and thermal status are measured. E4B, visual verification, and answer composition remain NOT RUN.**

Files changed:

- `android/app/build.gradle.kts`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/offlineDemo/AndroidManifest.xml`
- `android/app/src/consumer/AndroidManifest.xml`
- `android/app/src/main/java/com/askphotos/android/ModelPackManager.kt`
- `android/app/src/main/java/com/askphotos/android/GemmaModelDownloader.kt`
- `android/app/src/main/java/com/askphotos/android/GemmaPlanCodec.kt`
- `android/app/src/main/java/com/askphotos/android/LiteRtLmQueryPlanner.kt`
- `android/app/src/main/java/com/askphotos/android/DeterministicPlanOverlay.kt`
- `android/app/src/main/java/com/askphotos/android/QueryCompiler.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryViewModel.kt`
- `android/app/src/main/java/com/askphotos/android/MainActivity.kt`
- Gemma catalog/codec/pack unit and instrumented security/UI tests
- `android/app/src/androidTest/java/com/askphotos/android/RealGemmaPlannerAcceptanceTest.kt`
- `tools/device/profile_instrumentation.py` and parser tests

Architecture decisions:

- Google AI Edge Gallery `model_allowlists/1_0_15.json` is the catalog reference. E2B uses `litert-community/gemma-4-E2B-it-litert-lm` revision `7fa1d78473894f7e736a21d920c3aa80f950c0db`; E4B uses `litert-community/gemma-4-E4B-it-litert-lm` revision `9695417f248178c63a9f318c6e0c56cb917cb837`.
- The Hugging Face LFS metadata was queried without downloading weights. E2B is pinned to 2,583,085,056 bytes and SHA-256 `ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42`; E4B is pinned to 3,654,467,584 bytes and SHA-256 `f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc`.
- `consumer` alone declares `INTERNET`; `offlineDemo` explicitly removes it. Neither variant has a cloud-inference API. The consumer downloader accepts no user/model-generated URL, uses WorkManager with connected-network/storage constraints and resumable ranges, stores partial/final data under app-private storage, verifies exact size and SHA-256, then atomically activates a generation.
- Settings persists E2B/E4B selection. E2B is the default; E4B requires the stronger runtime assessment. Signed `.agemma` SAF import remains available in both variants.
- Gemma emits only the typed JSON plan. The codec rejects unknown fields, paths/URIs/SQL/result IDs, enforces bounds, and permits one repair call. Runtime work is serialized, uses GPU then CPU, and rolls back after a load failure.
- The planner uses the model's declared 4,096-token context, disables thinking for this bounded compiler, and applies deterministic sampling. The same initialized engine is reused for the initial generation and the single repair, then closed.
- Gemma supplies semantic intent and clauses; Kotlin overlays hard deterministic facts such as explicit/previous calendar years, known media scope, known place aliases, document fields, and requested ordering. The merged plan is validated again before execution. The LLM does not calculate exact dates.
- Each planner trace records model load, bounded generation, close, total latency, backend, repair use, and whether the deterministic overlay changed the plan. The host profiler polls only process memory and thermal services, masks the device serial, and never captures prompts or media.
- Settings publishes a selected-tier state before its potentially blocking WorkManager lookup and maintains one monitor job. This removes the stale E4B button state that the prior connected Compose test exposed.

Commands and results:

```powershell
.\gradlew.bat :app:testOfflineDemoDebugUnitTest :app:testConsumerDebugUnitTest :app:assembleOfflineDemoDebug :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant ConsumerDebug
adb -s <serial> install -r -t android\app\build\outputs\apk\androidTest\consumer\debug\app-consumer-debug-androidTest.apk
adb -s <serial> shell am instrument -w -r -e class com.askphotos.android.GemmaSettingsUiTest,com.askphotos.android.GemmaPackSecurityTest,com.askphotos.android.AskPhotosSmokeTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> shell sha256sum <app-private-active-e2b-model>
adb -s <serial> shell am instrument -w -r -e class com.askphotos.android.RealGemmaPlannerAcceptanceTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
python tools/device/profile_instrumentation.py --serial <serial> --package com.askphotos.android --test-class com.askphotos.android.RealGemmaPlannerAcceptanceTest --output artifacts/device-runs/phase6_multilingual_profile_exact
```

Results and device:

- 49 JVM suites / 152 flavored tests: 0 failures and 0 errors. Five Python device-harness tests also passed.
- Offline and consumer lint both passed when run sequentially. A combined parallel lint invocation hit an Android lint worker bug in which one flavor referenced another flavor's already-removed kapt stub; no source diagnostic was reported, and the two isolated lint gates were green.
- Offline and consumer debug APKs plus consumer Android-test APK assembled. Merged manifests show `INTERNET=false` for `offlineDemoDebug` and `INTERNET=true` for `consumerDebug`.
- Final consumer APK build/install: success on the sole connected Samsung SM-F966B, Android 16. The serial is intentionally omitted.
- Connected pack-security test, privacy/onboarding smoke, and grounded local Amsterdam search passed. The focused four-test instrumentation run completed in 4.252 seconds.
- The Settings test now passes and verifies tagged E2B/E4B selectors, immediate selection state, and the capability-derived E4B download-button policy.
- The active E2B artifact is 2,583,085,056 bytes. Device `sha256sum` returned `ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42`, exactly matching the immutable catalog.
- The final real-model suite passed on the sole connected Samsung SM-F966B, Android 16. English, Hindi, and Hinglish each reported `used=true`, `backend=GPU`, `calls=2`, `repaired=true`, `overlay=true`, intent `FIND_MEDIA`, scope `IMAGES`, valid schema, and fallback `null`. Exact 2024/previous-year ranges came from Kotlin and were asserted.
- Final per-query wall time: English 13,558 ms; Hindi 13,579 ms; Hinglish 22,463 ms. Model load was 2,750–3,773 ms, close was 344–419 ms, and generation was 9,907–18,268 ms.
- The exact-code profiled run sampled process memory 71 times: peak PSS 2,297,343 kB and peak RSS 2,428,572 kB. In-process PSS immediately after each explicit engine close was 284,474–288,418 kB; after instrumentation Android terminated the test process, so post-run process memory is correctly recorded as `null` rather than mislabelled as zero.
- Thermal status was `0` before and after the 51.391-second profiled run. Diagnostics found no target `FATAL EXCEPTION`, ANR, `OutOfMemoryError`, or failed assertion. This is a bounded acceptance measurement, not a sustained thermal benchmark.
- The first real-model attempt failed honestly because the 1,536-token engine context exhausted the repair response and produced malformed JSON. One repair cycle increased the engine to the model's 4,096-token context, disabled thinking, made sampling deterministic, and restated the bounded schema; the identical no-fallback acceptance assertion then passed.

Artifacts:

- `artifacts/phase6-model-download-build.log`
- `artifacts/phase6-consumer-device-tests.txt`
- `artifacts/phase6-consumer-settings-rerun.txt`
- `artifacts/phase6-consumer-settings-final.txt`
- `artifacts/phase6-settings-state-fix-build.log`
- `artifacts/phase6-settings-state-device-tests.txt`
- `artifacts/phase6-real-gemma-planner.txt` (retained failed first attempt)
- `artifacts/phase6-real-gemma-repair1-build.log`
- `artifacts/phase6-real-gemma-planner-final-code.txt`
- `artifacts/phase6-real-gemma-thermal-final.txt`
- `artifacts/phase6-multilingual-final-build-green.log`
- `artifacts/phase6-multilingual-lint-offline.log`
- `artifacts/phase6-multilingual-lint-consumer.log`
- `artifacts/device-runs/phase6_multilingual_profile_exact/`
- `artifacts/phase6-multilingual-final-diagnostics/20260722_011748/`

Failures and limitations:

- Real E2B planning acceptance covers three representative text-only queries, not the required 200–300-query multilingual evaluation set. All three needed the single allowed repair, so first-pass JSON reliability still needs improvement and measurement at scale.
- One-image multimodal verification and evidence-only answer composition remain NOT RUN even though the E2B pack itself is multimodal.
- The 51-second thermal result does not prove long-session or repeated-query thermal stability.
- E4B was not downloaded or loaded. Its UI option is capability-gated, but quality/latency comparison remains an explicit honest skip.
- The connected device changed during the work from the earlier SM-F731U to the sole authorized SM-F966B; the final install and tests above apply to SM-F966B.

Next phase:

- Begin Phase 7 as a separate narrow slice: one-image structured visual verification with bounded candidate inputs, followed by evidence-only answer composition and citation validation. Keep E4B optional and test it only after a dedicated safe-load benchmark.

## Phase 7A — bounded Gemma visual verification (22 July 2026)

Status: **Implemented and verified on the connected physical device. The live query repository now invokes Gemma only for `REQUIRED` plans or hard relational/negative/fine-grained `AUTO` clauses, checks at most eight ranked image candidates one image at a time, and accepts only candidates whose Kotlin-owned hard conditions are all satisfied. The real E2B one-image relationship test passed on GPU with three evidence records. Evidence-only generative answer composition, progressive candidate UI, face/body overlays, and the full no-answer acceptance flow remain pending.**

Files changed:

- `android/app/src/main/java/com/askphotos/android/GemmaVerificationCodec.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryImageLoader.kt`
- `android/app/src/main/java/com/askphotos/android/LiteRtGemmaVisualVerifier.kt`
- `android/app/src/main/java/com/askphotos/android/OnDeviceEngineContracts.kt`
- `android/app/src/main/java/com/askphotos/android/AskPhotosApplication.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryRepository.kt`
- `android/app/src/test/java/com/askphotos/android/GemmaVerificationCodecTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/RealGemmaVisualVerifierAcceptanceTest.kt`

Architecture decisions:

- Kotlin assigns condition IDs and the candidate media ID. Gemma never emits a media ID, path, URI, tool name, or arbitrary field.
- The strict response schema is `conditions[{id,satisfied,confidence}]` plus `overallMatch`. The codec rejects missing, duplicate, or unknown condition IDs; missing or additional fields; non-finite/out-of-range confidence; and an `overallMatch` that disagrees with Kotlin's conjunction of all hard conditions.
- Each image gets one deterministic multimodal generation and at most one repair over the same image. The engine is initialized once per bounded candidate batch, uses one image per conversation, and is explicitly closed.
- The verifier tries GPU for both language and vision, then CPU. The central generative-model lease prevents simultaneous planner/verifier or background high-memory inference.
- The candidate bound is eight. `NEVER` and ordinary soft semantic `AUTO` queries bypass Gemma. `REQUIRED`, hard, negative, person-relation, and fine-grained visual clauses activate it.
- Image bytes are loaded only from validated app assets, app-private preview files, or Kotlin-owned `content://` records. Asset traversal, non-content URIs, and previews outside app-private roots are rejected. Inputs are downsampled to a 1,600-pixel edge and re-encoded below an 8 MiB bound.
- Verification is fail-closed: a missing model, inaccessible image, schema failure after one repair, or inference failure accepts no affected candidate. Returned warnings are sanitized and do not contain URIs or filesystem paths.
- Satisfied conditions create `visual_verification` evidence records whose producer includes the active Gemma tier and pack revision. Rejected-candidate evidence is not attached to displayed hits.
- Any bounded visual-verification result is reported as retrieval-estimated rather than a complete gallery scan.

Commands and results:

```powershell
.\gradlew.bat :app:testConsumerDebugUnitTest --tests com.askphotos.android.GemmaVerificationCodecTest
.\gradlew.bat :app:compileConsumerDebugAndroidTestKotlin
$env:ANDROID_HOME=<detected-sdk>; $env:ANDROID_SDK_ROOT=<detected-sdk>; powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant ConsumerDebug -Serial <masked>
.\gradlew.bat :app:connectedConsumerDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.askphotos.android.RealGemmaVisualVerifierAcceptanceTest"
.\gradlew.bat :app:testOfflineDemoDebugUnitTest :app:testConsumerDebugUnitTest :app:lintOfflineDemoDebug :app:lintConsumerDebug
python C:\Users\anupk\.codex\skills\android-device-diagnostics\scripts\android_diagnostics.py --serial <masked> --package com.askphotos.android --minutes 10 --max-lines 120 --out build\device-artifacts\phase7-visual --screenshot --include-global-errors
adb -s <masked> shell dumpsys thermalservice
```

Unit/instrumented/device results:

- Focused verifier codec/policy/path tests passed. The final two-flavor unit gate contains 51 XML suites / 164 flavored tests, with 0 failures, 0 errors, and 0 skipped. Offline and consumer lint both passed.
- Consumer debug build/install succeeded on the sole connected Samsung SM-F966B, Android 16/API 36, arm64-v8a, SM8750. The device serial is omitted.
- The connected `RealGemmaVisualVerifierAcceptanceTest` passed: 1 test, 0 failures/errors/skips. It generated a new CC0 relationship card in app cache, verified it with the installed E2B pack, asserted all three hard conditions, asserted the accepted candidate, asserted three local evidence records and their producer provenance, then deleted exactly that temporary cache file.
- Real trace: `used=true`, backend `GPU`, generation calls `1`, repair `0`, engine load `2,860 ms`, generation `6,801 ms`, close `458 ms`, verifier elapsed `10,139 ms`, wall `10,142 ms`, accepted `1`, evidence `3`, failures `0`.
- In-process PSS was 81,279 kB before the verifier and 308,128 kB after explicit engine close. These two samples are not a peak-memory profile.
- Post-run thermal status was `0`; reported AP temperature samples were below throttling status. A targeted scan found no app `FATAL EXCEPTION`, ANR, `OutOfMemoryError`, or app crash marker.
- The first build/install invocation failed before compilation because the child PowerShell process did not inherit a discoverable Android SDK path. The command was rerun with process-local `ANDROID_HOME`/`ANDROID_SDK_ROOT` and succeeded; no `local.properties` file was added.
- The first connected-test command was rejected by Gradle because PowerShell stripped the unquoted `-P` property prefix. Quoting that property fixed command parsing; the test itself passed on its first execution.

Artifacts:

- `artifacts/phase7-visual-verification/build-phase7-unit.log`
- `artifacts/phase7-visual-verification/build-phase7-androidtest-compile.log`
- `artifacts/phase7-visual-verification/build-phase7-device.log`
- `artifacts/phase7-visual-verification/build-phase7-gate.log`
- `artifacts/phase7-visual-verification/build-phase7-final-focused.log`
- `artifacts/phase7-visual-verification/diagnostics-20260722_013347/`
- `android/app/build/outputs/androidTest-results/connected/debug/flavors/consumer/TEST-SM-F966B - 16-_app-consumer.xml`
- `android/app/build/outputs/androidTest-results/connected/debug/flavors/consumer/SM-F966B - 16/test-result.textproto`

Failures and limitations:

- The 10.14-second wall result is one synthetic image on one high-end phone. It is not an 8-candidate latency percentile or sustained thermal benchmark, and is slightly above the initial 10-second aspirational complex-answer target.
- The verifier currently receives a downsampled full image. Stable face labels, body crops, and condition-specific evidence regions are not yet overlaid or emitted, so real named-person clothing verification must not be presented as identity-proven until the people pipeline supplies those inputs.
- Verification currently completes before the repository returns; progressive initial-candidate then verified-result UI states remain pending.
- The live fail-closed no-match path is implemented, but this slice did not yet run the seeded nonexistent-merchant/no-fabrication UI acceptance query.
- E4B remains an honest skip; no E4B pack was downloaded or loaded.
- Structured generative answer composition and post-generation claim/citation validation are the next Phase 7 slice.

Next phase:

- Implement evidence-packet construction, strict grounded-answer JSON, deterministic number/date and evidence-ID post-validation, and the no-fabrication connected acceptance test without expanding into people/events work.

## Phase 7B — evidence-only Gemma answer composition (22 July 2026)

Status: **Implemented and verified on the connected physical device. Complex verified/event/comparison/timeline answers may now use one bounded text-only E2B wording stage over a compact Kotlin-built evidence packet. The strict decoder rejects invented citations, media references, numbers, calendar dates, paths/URIs, and unsupported descriptive vocabulary; deterministic exactness and coverage fields cannot be changed. Empty/no-answer results bypass Gemma. The final real E2B composition and deterministic no-fabrication tests passed.**

Files changed:

- `android/app/src/main/java/com/askphotos/android/GroundedAnswerCodec.kt`
- `android/app/src/main/java/com/askphotos/android/LiteRtGemmaGroundedAnswerComposer.kt`
- `android/app/src/main/java/com/askphotos/android/OnDeviceEngineContracts.kt`
- `android/app/src/main/java/com/askphotos/android/AskPhotosApplication.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryRepository.kt`
- `android/app/src/main/java/com/askphotos/android/MainActivity.kt`
- `android/app/src/test/java/com/askphotos/android/GroundedAnswerCodecTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/RealGemmaGroundedAnswerAcceptanceTest.kt`

Architecture decisions:

- The evidence packet contains the original query, the authoritative deterministic headline/detail/exactness/coverage, and at most 24 evidence records owned by active result media. It excludes filenames, content URIs, preview paths, raw pixels, and model-generated media references.
- Gemma may return only `headline`, `detail`, and bounded `claims[{text,evidenceIds,confidence}]`. Evidence IDs must be copied from the packet; every claim must cite at least one existing ID.
- Kotlin preserves exactness, indexed/eligible coverage, and warnings from the deterministic answer. The model cannot overwrite those fields.
- The validator rejects unknown/missing fields, empty or oversized text, unknown or empty citation lists, invalid confidence, unsupported numeric values, unsupported English month/weekday names, URI/path patterns, and words that are not grounded in the query/baseline/cited evidence or a small answer-glue allowlist. This intentionally favors deterministic fallback over an imaginative paraphrase.
- The composer performs one deterministic-sampling call and at most one repair. It reuses one initialized engine for the repair, explicitly closes it, and uses the central generative lease with GPU then CPU fallback.
- Empty evidence or no-match answers do not load Gemma. Live repository composition is currently limited to a non-empty verified result or `COMPARE`, `TIMELINE`, and `EVENT_SUMMARY`; ordinary search/count/document answers retain deterministic wording.
- Any schema, citation, literal, vocabulary, model-load, or inference failure retains the deterministic answer and adds a sanitized warning. No generated partial answer is rendered.
- The answer card now renders each grounded claim with its evidence IDs and confidence, plus fail-safe warnings.

Commands and results:

```powershell
.\gradlew.bat :app:testConsumerDebugUnitTest --tests com.askphotos.android.GroundedAnswerCodecTest
.\gradlew.bat :app:compileConsumerDebugAndroidTestKotlin
$env:ANDROID_HOME=<detected-sdk>; $env:ANDROID_SDK_ROOT=<detected-sdk>; powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant ConsumerDebug -Serial <masked>
.\gradlew.bat :app:connectedConsumerDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.askphotos.android.RealGemmaGroundedAnswerAcceptanceTest"
adb -s <masked> shell am start -n com.askphotos.android/.MainActivity
# Visible Index/Settings controls were used to download the pinned E2B pack after the connected-test lifecycle removed prior app data.
adb -s <masked> shell run-as com.askphotos.android sha256sum <app-private-active-e2b-model>
adb -s <masked> install -r -t android/app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk
adb -s <masked> shell am instrument -w -r -e class 'com.askphotos.android.RealGemmaGroundedAnswerAcceptanceTest#installedE2bComposesOnlyClaimsWithExistingEvidence' com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
.\gradlew.bat :app:testOfflineDemoDebugUnitTest :app:testConsumerDebugUnitTest :app:lintOfflineDemoDebug :app:lintConsumerDebug
python C:\Users\anupk\.codex\skills\android-device-diagnostics\scripts\android_diagnostics.py --serial <masked> --package com.askphotos.android --minutes 10 --max-lines 120 --out artifacts\phase7-grounding-diagnostics --screenshot
adb -s <masked> shell dumpsys thermalservice
```

Unit/instrumented/device results:

- Final two-flavor JVM gate: 53 XML suites / 174 flavored tests, 0 failures, 0 errors, 0 skipped. Offline and consumer lint both passed.
- Grounding regression coverage includes exact schema, citation ownership/bounds, one-repair limit, invented IDs, invented numbers, invented month names, content/file path patterns, unrelated claims, and a mixed supported phrase plus invented proper noun.
- The initial connected class run executed the empty-result test successfully but honestly skipped the E2B test because the preceding connected-test lifecycle had removed the app and app-private pack. This was not counted as a real-model pass.
- E2B was restored through the visible Settings download. The active artifact is 2,583,085,056 bytes and device SHA-256 is `ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42`, exactly matching the pinned catalog.
- Final raw-instrumentation E2B result after the strict vocabulary check: 1 test passed, `used=true`, backend `GPU`, generation calls `1`, repaired `false`, evidence `2`, claims `2`, fallback `null`.
- Final timings: load `2,937 ms`, generation `2,298 ms`, close `321 ms`, composer elapsed `5,560 ms`, wall `5,562 ms`. In-process PSS was 74,304 kB before and 284,790 kB after explicit engine close; these are not peak samples.
- The deterministic empty-result test asserted no Gemma call, unchanged `No supported matches found` wording, zero evidence IDs, and zero claims.
- Final thermal status was `0`. Package-scoped diagnostics and a targeted scan found no app `FATAL EXCEPTION`, ANR, `OutOfMemoryError`, or app crash marker.

Artifacts:

- `artifacts/phase7-grounding-focused.log`
- `artifacts/phase7-grounding-androidtest-compile.log`
- `artifacts/phase7-grounding-device.log`
- `artifacts/phase7-grounding-real-rerun.txt`
- `artifacts/phase7-grounding-vocabulary-focused.log`
- `artifacts/phase7-grounding-real-final.txt`
- `artifacts/phase7-grounding-final-gate.log`
- `artifacts/phase7-grounding-diagnostics/20260722_015951/`

Failures and limitations:

- The real suite covers one two-claim evidence packet, not the full 200–300-query faithfulness/evidence evaluation. Citation validity passed for this acceptance case; corpus-wide 100% citation precision is not yet demonstrated.
- Vocabulary validation is deliberately conservative. A valid creative paraphrase can be rejected and replaced with the deterministic answer; this is a safety tradeoff, not a semantic-entailment proof.
- The connected no-answer test exercises the composer boundary with an empty result. The seeded nonexistent-merchant query still needs to run through the full UI/repository acceptance harness after the core gallery is reseeded.
- Visual verification and answer composition currently initialize E2B separately. Sharing a model lease/session or keeping a short-lived warm engine needs measurement before changing memory policy.
- The live query flow waits for verification and composition before returning; progressive initial results remain pending.
- Sensitive OCR authentication/redaction must be enforced before a sensitive evidence packet is built; that full integration remains incomplete.
- E4B remains an honest skip.

Next phase:

- Continue Phase 7 with a seeded end-to-end no-match/full-query acceptance and progressive query UI state, then move to the separate opt-in people/events/follow-up slice.

## Phase 7C — seeded no-fabrication acceptance (22 July 2026)

Status: **Implemented, passed, and safely cleaned on the physical device. The Q10 nonexistent-merchant request now becomes a deterministic `DOCUMENT_QA` constraint even when E2B initially returns generic `FIND_MEDIA`; unrelated receipts cannot pass merely because the word “receipt” matches. The full repository returned a clear no-match with zero hits, claims, or evidence.**

Files changed:

- `android/app/src/main/java/com/askphotos/android/QueryCompiler.kt`
- `android/app/src/main/java/com/askphotos/android/DeterministicPlanOverlay.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryRepository.kt`
- `android/app/src/test/java/com/askphotos/android/QueryCompilerTest.kt`
- `android/app/src/test/java/com/askphotos/android/DeterministicPlanOverlayTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/NoFabricationAcceptanceTest.kt`

Architecture decisions and fixes:

- Kotlin classifies receipt/invoice/document requests as `DOCUMENT_QA` and extracts the phrase following “receipt from” as a required merchant constraint.
- The deterministic overlay now fills individual missing OCR fields instead of discarding a deterministic merchant when Gemma emits a partial OCR clause.
- A non-generic deterministic intent overrides only when Kotlin recognizes a bounded exact/document/event operation; model semantics remain authoritative when deterministic parsing yields generic `FIND_MEDIA`.
- The repository applies a nonblank merchant as a hard prefilter over indexed title, description, OCR text, and tags before lexical/vector fusion. It does not silently relax the merchant when no item matches.

Device gate:

- Seed command created 74 media URIs only under `Pictures/AgenticGalleryTest/phase7_no_fab_20260722/` and `Documents/AgenticGalleryTest/phase7_no_fab_20260722/`; the run manifest masked the serial and reported no retries.
- First acceptance execution failed honestly because the E2B plan intent remained `FIND_MEDIA`. The deterministic intent overlay was repaired and the unchanged assertion was rerun once.
- Final acceptance: 1 test passed. Plan intent was `DOCUMENT_QA`, the hard merchant contained “does not exist,” hits were empty, headline was `No supported matches found`, and claims/evidence IDs were empty.
- Cleanup requested and deleted exactly 74 recorded URIs and verified `remainingCount=0`. No broad shared-storage deletion was used.
- Final JVM gate: 178 flavored tests, 0 failures/errors/skips. Combined parallel lint hit the known Android `GradleDetector` `ConcurrentModificationException` without a source diagnostic; unchanged offline and consumer lint were then run sequentially and both passed without disabling checks.

Artifacts:

- `artifacts/phase7-no-fabrication-seed/`
- `artifacts/phase7-no-fabrication-device.txt` (retained failed first run)
- `artifacts/phase7-no-fabrication-device-final.txt`
- `artifacts/phase7-no-fabrication-cleanup/`
- `artifacts/phase7-no-fabrication-focused.log`
- `artifacts/phase7-no-fabrication-repair.log`
- `artifacts/phase7-no-fabrication-final-gate.log`
- `artifacts/phase7-no-fabrication-lint-offline.log`
- `artifacts/phase7-no-fabrication-lint-consumer.log`

Limitations and next phase:

- This closes the seeded no-fabrication query but does not add progressive initial/verified UI states.
- Merchant matching is a conservative normalized substring constraint. Alias/entity resolution for merchant variants needs a later document-entity evaluation rather than silent fuzzy relaxation.
- Next narrow slice: progressive query execution state and cancellation, followed separately by opt-in people/events/follow-up work.

## Phase 7D — progressive results and cancellable inference (22 July 2026)

Status: **Implemented, installed, and verified on the connected physical device. The Ask screen now consumes the repository's real execution flow, shows local candidates before bounded verification/answer composition finishes, exposes a Cancel action, and never publishes a cancelled partial answer. Cancellation is propagated into the active LiteRT-LM conversation through `Conversation.cancelProcess()` and is rethrown through planner, verifier, and composer boundaries instead of being converted into a deterministic fallback.**

Files changed:

- `android/app/src/main/java/com/askphotos/android/GalleryViewModel.kt`
- `android/app/src/main/java/com/askphotos/android/MainActivity.kt`
- `android/app/src/main/java/com/askphotos/android/LiteRtConversationRunner.kt`
- `android/app/src/main/java/com/askphotos/android/LiteRtLmQueryPlanner.kt`
- `android/app/src/main/java/com/askphotos/android/LiteRtGemmaVisualVerifier.kt`
- `android/app/src/main/java/com/askphotos/android/LiteRtGemmaGroundedAnswerComposer.kt`
- `android/app/src/test/java/com/askphotos/android/QueryProgressUiReducerTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/ProgressiveQueryUiTest.kt`

Architecture decisions:

- `GalleryViewModel` collects the typed `QueryProgress` flow on a background dispatcher and reduces `Understanding`, `PlanReady`, `InitialResults`, `Verifying`, `ComposingAnswer`, and `Completed` into explicit UI state.
- The screen renders up to eight early local candidates with a warning that verification may reorder or remove them. Only `Completed` replaces the saved result set and navigates to the final Results screen.
- A generation token prevents a cancelled/stale flow from overwriting a newer query. Cancelling retains the previous completed result set, clears transient candidates, returns the Ask action to idle, and displays `Query cancelled; no partial answer was saved`.
- The LiteRT bridge registers a coroutine cancellation handler before blocking decoding. The handler calls the native `Conversation.cancelProcess()` API; the conversation is then closed in `finally` and the central model lease is released by structured concurrency.
- `CancellationException` is explicitly rethrown at all three model stages. It cannot be swallowed by the verifier's per-candidate `runCatching`, planner fallback, or grounded-answer fallback.

Commands run:

```powershell
$env:ANDROID_HOME=<detected-sdk>; $env:ANDROID_SDK_ROOT=<detected-sdk>
.\gradlew.bat :app:testConsumerDebugUnitTest --tests com.askphotos.android.QueryProgressUiReducerTest :app:compileConsumerDebugKotlin
.\gradlew.bat :app:testConsumerDebugUnitTest --tests com.askphotos.android.QueryProgressUiReducerTest :app:assembleConsumerDebugAndroidTest
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant ConsumerDebug -Serial <masked>
adb -s <masked> install -r -t android\app\build\outputs\apk\androidTest\consumer\debug\app-consumer-debug-androidTest.apk
adb -s <masked> shell am instrument -w -r -e class com.askphotos.android.ProgressiveQueryUiTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
.\gradlew.bat --no-parallel :app:testOfflineDemoDebugUnitTest :app:testConsumerDebugUnitTest :app:lintOfflineDemoDebug :app:lintConsumerDebug
python C:\Users\anupk\.codex\skills\android-device-diagnostics\scripts\android_diagnostics.py --serial <masked> --package com.askphotos.android --minutes 10 --keywords "AndroidRuntime,FATAL,ANR,OutOfMemory,LiteRtLm,cancel" --max-lines 120 --out build\device-artifacts\phase7-progressive-query\diagnostics --screenshot
```

Unit/instrumented/device results:

- Focused reducer test: 2 tests passed. It verifies early-hit retention through verification and that completion atomically clears transient state and opens the authoritative final result.
- Final two-flavor JVM gate: 20 suites / 68 tests per flavor (136 flavored executions), 0 failures, 0 errors, 0 skipped.
- Offline and consumer lint tasks passed with 0 errors. Each report retains 49 non-blocking warnings, predominantly pinned-dependency update notices; no lint check was disabled.
- Consumer debug build/install succeeded on the sole connected Samsung SM-F966B, Android 16/API 36, arm64-v8a, SM8750. Existing app data and the verified E2B model pack were preserved.
- Final connected `ProgressiveQueryUiTest`: 1 test passed in 1.832 seconds. It entered a hard visual query through the real Compose UI, observed the active Cancel control, cancelled, asserted the explicit no-partial-answer state, and asserted that Ask was enabled for another query.
- The first connected assertion found the Cancel semantics node but failed `assertIsDisplayed` because it was below the lazy-grid viewport. The test was corrected to `performScrollTo()` and the unchanged product behavior passed on the single allowed repair cycle.
- Post-test package-scoped diagnostics found no app process left running and no app `FATAL EXCEPTION`, ANR, or `OutOfMemoryError` marker. The instrumentation lifecycle's normal process teardown is present in the raw log.

Artifacts:

- `build/device-artifacts/phase7-progressive-query/instrumentation.txt` (retained first failed UI assertion)
- `build/device-artifacts/phase7-progressive-query/instrumentation-retry.txt` (passing connected result)
- `build/device-artifacts/phase7-progressive-query/instrumentation-final.txt` (passing final installed runtime)
- `build/device-artifacts/phase7-progressive-query/diagnostics/20260722_022819/`
- `android/app/build/reports/tests/testOfflineDemoDebugUnitTest/`
- `android/app/build/reports/tests/testConsumerDebugUnitTest/`
- `android/app/build/reports/lint-results-offlineDemoDebug.html`
- `android/app/build/reports/lint-results-consumerDebug.html`

Failures and limitations:

- The connected UI assertion proves cancellation state, re-query readiness, and absence of a published partial result. It does not report a native decoder cancellation latency percentile; that needs a dedicated repeated real-E2B benchmark with an inference-start signal.
- Early-candidate rendering is covered by the pure reducer test. A device screenshot during the transient early-results window was not captured because this query was cancelled during understanding; transient visual QA remains for the seeded verified-query acceptance run.
- The diagnostics screenshot was captured after instrumentation teardown and is retained only as a run artifact, not as proof of the transient query UI.
- Opt-in reviewed people clusters, event/entity memory, result-set `PlanPatch` follow-ups, video keyframes, and 5k/20k sustained acceptance remain incomplete.
- E4B remains an honest skip; selection/download support exists, but no compatible E4B pack has been installed or benchmarked on this device.

Next phase:

- Keep the next slice separate: implement user-controlled people-index opt-in and reset enforcement first, then event/result-set follow-ups in a later slice.
