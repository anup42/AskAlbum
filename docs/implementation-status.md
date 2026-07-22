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

## Phase 8A — opt-in people-index privacy foundation (22 July 2026)

Status: **Implemented, installed, and verified on the connected physical device. People indexing is disabled by default, enabling it requires explicit consent, reset permanently removes all derived face records/labels without touching gallery media, and person-constrained queries fail closed until reviewed identity embeddings actually exist.**

Files changed:

- `android/app/src/main/java/com/askphotos/android/GalleryRoomDatabase.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryDatabase.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryModels.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryRepository.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryIndexWorker.kt`
- `android/app/src/main/java/com/askphotos/android/MlKitFaceDetectionEngine.kt`
- `android/app/src/main/java/com/askphotos/android/PeopleIndexScheduler.kt`
- `android/app/src/main/java/com/askphotos/android/PeopleIndexWorker.kt`
- `android/app/src/main/java/com/askphotos/android/PeopleQueryGate.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryViewModel.kt`
- `android/app/src/main/java/com/askphotos/android/MainActivity.kt`
- `android/app/schemas/com.askphotos.android.GalleryRoomDatabase/8.json`
- `android/app/src/test/java/com/askphotos/android/PeopleQueryGateTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/GalleryRoomMigrationTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/PeoplePrivacyDatabaseTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/PeoplePrivacyUiTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/RealFaceDetectionAcceptanceTest.kt`

Architecture decisions:

- Room schema v8 adds a singleton consent record, local reviewed person clusters, and local face instances with normalized regions and producer versions.
- The bundled ML Kit detector performs real offline face detection. It stores detection boxes and quality only; it does not pretend that a detector is an identity embedding model.
- Enabling people indexing marks only indexed, accessible, non-demo images pending. A dedicated constrained worker performs bounded batches and rechecks consent transactionally before saving.
- Reset cancels people work, deletes face instances and person clusters (including labels and aliases), marks face stages skipped, and leaves source gallery media unchanged.
- Person clauses are rejected with `PARTIAL_INDEX` unless consent is enabled and reviewed identity-ready embeddings exist. Face boxes alone are never accepted as person identity evidence.
- The Privacy UI explains local processing, explicit opt-in, current derived-record counts, and permanent reset behavior. The connected UI acceptance cancels before opt-in and therefore does not mutate the real app's consent state.

Commands run:

```powershell
$env:ANDROID_HOME=<detected-sdk>; $env:ANDROID_SDK_ROOT=<detected-sdk>
.\gradlew.bat :app:testConsumerDebugUnitTest --tests com.askphotos.android.PeopleQueryGateTest :app:assembleConsumerDebugAndroidTest
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant ConsumerDebug -Serial <masked>
adb -s <masked> install -r -t android\app\build\outputs\apk\androidTest\consumer\debug\app-consumer-debug-androidTest.apk
adb -s <masked> shell am instrument -w -r -e class com.askphotos.android.GalleryRoomMigrationTest,com.askphotos.android.PeoplePrivacyDatabaseTest,com.askphotos.android.PeoplePrivacyUiTest,com.askphotos.android.RealFaceDetectionAcceptanceTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
.\gradlew.bat --no-parallel :app:testOfflineDemoDebugUnitTest :app:testConsumerDebugUnitTest :app:lintOfflineDemoDebug :app:lintConsumerDebug
python C:\Users\anupk\.codex\skills\android-device-diagnostics\scripts\android_diagnostics.py --serial <masked> --package com.askphotos.android --minutes 10 --keywords "AndroidRuntime,FATAL,ANR,OutOfMemory,PeopleIndex,FaceDetector" --max-lines 120 --out build\device-artifacts\phase8-people-privacy\diagnostics --screenshot
```

Unit/instrumented/device results:

- Final two-flavor JVM gate: 21 suites / 71 tests per flavor (142 flavored executions), 0 failures, 0 errors, 0 skipped.
- Offline and consumer lint passed with 0 errors. Each report retains 49 non-blocking warnings, predominantly pinned-dependency update notices; no lint check was disabled.
- Consumer debug built and installed successfully on the sole connected Samsung SM-F966B, Android 16/API 36, arm64-v8a, SM8750. Existing private app data and the E2B pack were preserved.
- Final connected acceptance: 4 tests passed in 1.697 seconds: schema v3-to-v8 migration, isolated consent/reset persistence, non-mutating Compose opt-in confirmation, and real bundled face-detector execution.
- Real detector trace: `elapsedMs=223`, `detections=0`, `bytes=856525`. The local acceptance image intentionally contains no face; the test validates bounded output and runtime integration, not positive identity recognition.
- The manual device screenshot was captured from the foldable's active display and visually inspected. It shows `People indexing off`, the local-only explanation, and the explicit `Enable people indexing` action.
- Package-scoped diagnostics contained no app `FATAL EXCEPTION`, ANR, or `OutOfMemoryError` marker.
- No test gallery was seeded and no shared/personal media was modified or deleted in this slice. The database acceptance used an isolated app-private test database and closed/deleted it afterward.

Artifacts:

- `build/device-artifacts/phase8-people-privacy/instrumentation-final.txt`
- `build/device-artifacts/phase8-people-privacy/privacy-screen.png`
- `build/device-artifacts/phase8-people-privacy/diagnostics/20260722_025004/`
- `android/app/build/reports/tests/testOfflineDemoDebugUnitTest/`
- `android/app/build/reports/tests/testConsumerDebugUnitTest/`
- `android/app/build/reports/lint-results-offlineDemoDebug.html`
- `android/app/build/reports/lint-results-consumerDebug.html`

Failures and limitations:

- This slice implements consent, real face detection, persistence, reset, and fail-closed query behavior. It does **not** yet implement a licensed identity embedding pack, clustering, positive person recognition, or the user review/labeling flow.
- The connected detector fixture has no face, so positive-face recall and box accuracy remain unmeasured. A consented or synthetic face fixture is required before claiming that acceptance.
- A full seeded people-worker run was intentionally not performed because enabling the real app could process unrelated accessible media. It requires a dedicated run-scoped selected-media harness.
- Room storage is app-private but not yet database-encrypted; Android Keystore-backed protection and biometric access for sensitive derived data remain incomplete.
- Event/entity memory, result-set-aware `PlanPatch` follow-ups, video keyframes, and 5k/20k sustained acceptance remain incomplete.
- E4B remains an honest skip; selection/download support exists, but no compatible E4B pack has been installed or benchmarked on this device.

Next phase:

- Implement event/result-set follow-up memory as its own vertical slice. Keep identity embeddings/clustering separate until a redistribution-compatible on-device model and a synthetic/consented positive-face corpus are pinned.

## Phase 8B — persistent result sets and validated follow-up patches (22 July 2026)

Status: **Implemented, installed, and verified on the connected physical device. Completed queries now create bounded app-private result sets, follow-ups execute an app-owned validated `PlanPatch` over the active set, stale/concurrent parents fail closed, and the active refinement scope survives process death. Typed time, media-kind, and album filters are now executed as hard Kotlin constraints rather than only being validated.**

Files changed:

- `android/app/src/main/java/com/askphotos/android/GalleryModels.kt`
- `android/app/src/main/java/com/askphotos/android/ResultSetPlanPatchResolver.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryFilterEvaluator.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryRoomDatabase.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryDatabase.kt`
- `android/app/src/main/java/com/askphotos/android/GallerySqlDatabase.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryRepository.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryViewModel.kt`
- `android/app/src/main/java/com/askphotos/android/QueryCompiler.kt`
- `android/app/src/main/java/com/askphotos/android/GemmaPlanCodec.kt`
- `android/app/src/main/java/com/askphotos/android/DeterministicPlanOverlay.kt`
- `android/app/src/main/java/com/askphotos/android/MediaImporter.kt`
- `android/app/src/main/java/com/askphotos/android/MainActivity.kt`
- `android/app/schemas/com.askphotos.android.GalleryRoomDatabase/9.json`
- `android/app/src/test/java/com/askphotos/android/ResultSetPlanPatchResolverTest.kt`
- `android/app/src/test/java/com/askphotos/android/GalleryFilterEvaluatorTest.kt`
- `android/app/src/test/java/com/askphotos/android/DeterministicPlanOverlayTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/ResultSetPersistenceDatabaseTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/PersistentFollowUpUiTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/GalleryRoomMigrationTest.kt`

Architecture decisions and fixes:

- Room schema v9 adds `query_session`, `result_set`, and ordered `result_set_media` tables. Query turns record session/result/parent IDs and a bounded patch-field summary; raw media IDs never come from model output.
- `PlanPatch` contains an app-supplied result-set ID and a detached replacement plan with no media IDs. `ResultSetPlanPatchResolver` rejects unsupported versions/fields, invented IDs, stale parents, empty scopes, and plans that fail the existing typed validator.
- Result-set persistence and session advancement are atomic. A follow-up may update the session only if its expected parent is still active. At most 20 result sets are retained per session.
- The ViewModel restores `ConversationSearchState` from Room and no longer derives scope only from the current in-memory `SearchOutcome`. The Ask screen visibly shows the saved scope, and the Results screen has a dedicated `Refine these results` action.
- `Which is the best one?` becomes a quality sort over the active result set with no invented “best” semantic retrieval term.
- The repository now enforces typed `TimeRange`, `MediaKindIs`, `AlbumIs`, and nested `And` filters before ranking. Missing capture dates fail a hard time range rather than being silently included.
- MediaStore ingestion persists the leaf album from `RELATIVE_PATH`. Schema migration backfills existing demo albums from their location; existing personal MediaStore rows acquire the current album on the next incremental scan.
- A normal non-follow-up query starts a new branch. Recognized elliptical prefixes (`Only`, `With`, `What about`, `Which`, and supported Hindi/Hinglish forms) refine the active set and never re-search unrelated media.

Commands run:

```powershell
.\gradlew.bat :app:testConsumerDebugUnitTest --tests com.askphotos.android.ResultSetPlanPatchResolverTest --tests com.askphotos.android.GalleryFilterEvaluatorTest --tests com.askphotos.android.DeterministicPlanOverlayTest :app:assembleConsumerDebugAndroidTest
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root C:\Users\anupk\Documents\git\askphotos\android -Module :app -Variant ConsumerDebug -Serial <masked>
adb -s <masked> shell am instrument -w -r -e class com.askphotos.android.GalleryRoomMigrationTest,com.askphotos.android.ResultSetPersistenceDatabaseTest,com.askphotos.android.PersistentFollowUpUiTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
adb -s <masked> shell am force-stop com.askphotos.android
adb -s <masked> shell am start -n com.askphotos.android/.MainActivity
.\gradlew.bat --no-parallel :app:testOfflineDemoDebugUnitTest
.\gradlew.bat --no-parallel :app:lintOfflineDemoDebug
.\gradlew.bat --no-parallel :app:lintConsumerDebug
python C:\Users\anupk\.codex\skills\android-device-diagnostics\scripts\android_diagnostics.py --serial <masked> --package com.askphotos.android --minutes 15 --keywords "AndroidRuntime,FATAL,ANR,OutOfMemory,result_set,Room,SQLite,PlanPatch" --max-lines 120 --out build\device-artifacts\phase8-follow-up\diagnostics
```

Unit/instrumented/device results:

- Final two-flavor JVM gate: 23 suites / 77 tests per flavor (154 flavored executions), 0 failures, 0 errors, 0 skipped.
- Offline and consumer lint each passed sequentially with 0 errors and 49 non-blocking warnings. A combined invocation first hit the already documented Android Lint detector concurrency crash in lint analysis; no product diagnostic was emitted and no check was disabled.
- Consumer debug built and installed successfully on the sole connected Samsung SM-F966B, Android 16/API 36, arm64-v8a, SM8750. App-private data and the installed E2B pack were preserved.
- Connected migration, isolated persistence/reopen, and real Compose two-turn acceptance: 3 tests passed in 34.719 seconds.
- The UI acceptance asked `Show bicycles`, used `Refine these results`, then asked `Which is the best one?`. The second result IDs were contained entirely within the first result set, its persisted parent equaled the first app-created result-set ID, and no unrelated media entered the refinement.
- The migration test proves legacy v3 data reaches v9, all new tables exist, and a legacy demo album is backfilled.
- A forced process stop/start restored `Follow-up scope: 2 saved results` from Room. The post-restart UI hierarchy and screenshot were captured and visually inspected.
- Package-scoped diagnostics found no app `FATAL EXCEPTION`, ANR, `OutOfMemoryError`, or SQLite exception marker.
- The final album-backfill-only repair was rebuilt/reinstalled and its connected migration regression passed again in 0.042 seconds.
- No shared gallery was seeded or changed. Tests used bundled CC0 demo assets and isolated/app-private query databases only.

Artifacts:

- `build/device-artifacts/phase8-follow-up/instrumentation.txt`
- `build/device-artifacts/phase8-follow-up/migration-final.txt`
- `build/device-artifacts/phase8-follow-up/ui-after-process-restart.xml`
- `build/device-artifacts/phase8-follow-up/after-process-restart.png`
- `build/device-artifacts/phase8-follow-up/full-gate.log` (retained combined-lint detector crash)
- `build/device-artifacts/phase8-follow-up/offline-unit.log`
- `build/device-artifacts/phase8-follow-up/offline-lint.log`
- `build/device-artifacts/phase8-follow-up/consumer-lint.log`
- `build/device-artifacts/phase8-follow-up/diagnostics/20260722_031628/`
- `android/app/build/reports/tests/testOfflineDemoDebugUnitTest/`
- `android/app/build/reports/tests/testConsumerDebugUnitTest/`
- `android/app/build/reports/lint-results-offlineDemoDebug.html`
- `android/app/build/reports/lint-results-consumerDebug.html`

Failures and limitations:

- This proves persisted result-set scoping, a quality-sort patch, stale-parent rejection, and process-restart restoration. It does not yet prove the full acceptance chain `Singapore -> Only Marina Bay -> What about last year` on a seeded dated/GPS corpus.
- The connected behavior ran with the installed consumer app and preserved E2B pack, but this test does not expose a planner-backend trace assertion; it proves the product result-set invariant regardless of real-model or safe-fallback planner path.
- `PlanPatch` currently supports bounded app-recognized elliptical refinements. Rich replacement semantics for arbitrary anaphora, explicit filter-chip editing, and stale-session expiry UX remain incomplete.
- Event memory is still day-based. GPS/time-gap/album/people event clustering, event prototypes, merge/split corrections, and place-entity resolution remain incomplete.
- Video keyframes, identity embeddings/clustering, database encryption, Macrobenchmark coverage, and sustained 5k/20k acceptance remain incomplete.

Next phase:

- Implement richer event compilation (time gaps, album continuity, GPS distance, user correction precedence) and then run the seeded Singapore/Marina Bay/timeline follow-up chain as a separate acceptance slice.

## Phase 8C — episodic event compiler and seeded follow-up gate (22 July 2026)

Status: **Implemented and passed on the physical device. The event-memory core, Room migration, deterministic EXIF/GPS corpus, direct EXIF metadata ingestion, and the full seeded `Singapore -> Marina Bay -> last year` result-set chain are now verified. The passing run used the installed real Gemma 4 E2B pack and was cleaned with zero test items remaining.**

Files changed:

- `android/app/src/main/java/com/askphotos/android/EventCompiler.kt`
- `android/app/src/main/java/com/askphotos/android/RetrievalTerms.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryModels.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryRoomDatabase.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryDatabase.kt`
- `android/app/src/main/java/com/askphotos/android/GalleryRepository.kt`
- `android/app/src/main/java/com/askphotos/android/QueryCompiler.kt`
- `android/app/src/main/java/com/askphotos/android/DeterministicPlanOverlay.kt`
- `android/app/src/main/java/com/askphotos/android/MediaImporter.kt`
- `android/app/src/main/java/com/askphotos/android/ExifDateParser.kt`
- `android/app/src/debug/java/com/askphotos/android/TestGallerySeederReceiver.kt`
- `android/app/schemas/com.askphotos.android.GalleryRoomDatabase/10.json`
- event, temporal-follow-up, retrieval-normalization, migration, and connected acceptance tests under `android/app/src/test` and `android/app/src/androidTest`
- `tools/sample_gallery/fixture_metadata.py`
- `tools/sample_gallery/build_sample_gallery.py`
- `tools/sample_gallery/generate_stress_gallery.py`
- `tools/sample_gallery/verify_licenses.py`
- `tools/sample_gallery/test_fixture_metadata.py`

Architecture decisions and fixes:

- Room v10 replaces day-only events with stable event IDs, start/end times, optional location centroid, event type, confidence, searchable event text, representative media, producer version, and user-correction provenance. A migration-index collision found by the physical device was fixed by dropping the renamed legacy index before creating the v10 replacement.
- `EventCompiler` deterministically segments on bounded time gaps, album transitions, and GPS distance. Stable SHA-256-derived IDs do not depend on input order. Local merge, split, rename, and location corrections override inference and survive rebuilds.
- Event retrieval is a weighted hybrid channel. Every event-derived media hit carries an event evidence ID and producer version; broad result diversity still uses event membership.
- Multiword model terms are tokenized before lexical/event channels, so a valid Gemma term such as `singapore trip` cannot become an unmatchable atomic string.
- `What about last year?` compiles as a deterministic time-only patch over the active result set. Model-invented semantic filler is removed, and the empty/no-match result is exact rather than fabricated.
- MediaStore URI inspection requests `DATE_TAKEN` for MediaStore sources only and treats capture-date/GPS changes as incremental changes. Because Android 16 may leave `datetaken` null even for a valid image, the production importer reads permitted image content with platform `ExifInterface`, prefers `DateTimeOriginal` plus its explicit offset, and persists EXIF GPS. SAF providers are not asked for MediaStore-only columns.
- Core and stress corpus builders embed deterministic EXIF capture time, offset, and GPS in every generated raster derivative. Corpus verification now fails on a timestamp, offset, or GPS mismatch as well as on license/checksum errors.
- Google AI Edge Gallery was reviewed as requested. Its current public design emphasizes Gemma 4, LiteRT, local model management/download, local import, and per-device benchmarking. This repository already implements the corresponding E2B-default/E4B-gated Settings flow, resumable WorkManager download, signed/hash-verified app-private installation, local SAF import, and LiteRT-LM runtime boundary; no parallel downloader was added.

Commands run (serial masked):

```powershell
.\gradlew.bat :app:testOfflineDemoDebugUnitTest --console=plain
.\gradlew.bat :app:compileOfflineDemoDebugAndroidTestKotlin --console=plain
.\gradlew.bat --no-parallel :app:lintOfflineDemoDebug --console=plain
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root <repo>\android -Module :app -Variant ConsumerDebug -Serial <masked>
python tools/device/seed_gallery.py --serial <masked> --gallery build/sample-gallery/core --run-id phase8_events_20260722a
python tools/device/sync_seeded_gallery.py --serial <masked> --run-id phase8_events_20260722a --action import
adb -s <masked> shell am instrument -w -r -e galleryRunId phase8_events_20260722a -e class com.askphotos.android.SeededEventFollowUpAcceptanceTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
python tools/device/sync_seeded_gallery.py --serial <masked> --run-id phase8_events_20260722a --action remove
python tools/device/cleanup_gallery.py --serial <masked> --run-id phase8_events_20260722a
python tools/device/seed_gallery.py --serial <masked> --gallery build/sample-gallery/core --run-id phase8_events_20260722b
python tools/device/sync_seeded_gallery.py --serial <masked> --run-id phase8_events_20260722b --action remove
python tools/device/cleanup_gallery.py --serial <masked> --run-id phase8_events_20260722b
adb -s <masked> shell am instrument -w -r -e class com.askphotos.android.GalleryRoomMigrationTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
python tools/sample_gallery/test_fixture_metadata.py
python tools/sample_gallery/build_sample_gallery.py --profile core --output build/sample-gallery/core
python tools/sample_gallery/verify_licenses.py --gallery build/sample-gallery/core
python tools/device/seed_gallery.py --serial <masked> --gallery build/sample-gallery/core --run-id phase8_events_exif_20260722
python tools/device/sync_seeded_gallery.py --serial <masked> --run-id phase8_events_exif_20260722 --action import
adb -s <masked> shell am instrument -w -r -e galleryRunId phase8_events_exif_20260722 -e class com.askphotos.android.SeededEventFollowUpAcceptanceTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
python tools/device/sync_seeded_gallery.py --serial <masked> --run-id phase8_events_exif_20260722 --action remove
python tools/device/cleanup_gallery.py --serial <masked> --run-id phase8_events_exif_20260722
```

Unit/instrumented/device results:

- Full offline JVM suite passed after the final code changes. Focused event/planner/overlay tests and the new multiword-term regression are included.
- Offline lint passed with zero errors. The report remains at `android/app/build/reports/lint-results-offlineDemoDebug.html`.
- ConsumerDebug built and installed repeatedly without clearing app-private data on the sole authorized Samsung SM-F966B (Android 16/API 36, arm64-v8a, SM8750, approximately 11.4 GB RAM).
- Connected legacy-v3-to-v10 Room migration passed: 1 test, 0 failures, 0.05 seconds.
- First seeded run: 74/74 items seeded and imported. The initial migration failed on a preserved SQLite index name; after the migration fix, import succeeded. Q01 then failed because no seeded Singapore media was returned.
- Read-only aggregate diagnosis found one 74-member event, identical import-time timestamps, and a valid Gemma plan containing a multiword `singapore trip` term. Production retrieval-term tokenization and MediaStore date projection were corrected.
- Second fresh seeded run proved Android 16 MediaProvider still reported `datetaken=NULL` for the generated JPEG fixture after publication. The acceptance test was therefore not rerun/relaxed after the two-cycle limit.
- Cleanup was exact and safe. Run A removed 74 database rows and 74 recorded MediaStore URIs; run B had no imported rows and removed its 74 recorded MediaStore URIs. Both reported `remainingCount=0`. No unrelated gallery item was modified or deleted.
- Package diagnostics and a screenshot are retained under `artifacts/device-runs/phase8_events_20260722a/diagnostics/20260722_034210/`. A temporary diagnostic database copy was deleted from both host and device immediately after aggregate inspection.
- Final corpus verification passed for 74 gallery items, 17 license records, all generated checksums, and every raster item's EXIF timestamp/offset/GPS contract. Python metadata round-trip tests passed for JPEG and PNG.
- Final physical-device acceptance passed: 1 test, 0 failures, 64.903 seconds. It independently proved the seeded Singapore records were dated 2024; Q01 returned seeded Singapore items with event evidence; Q02 stayed inside Q01's persisted result set and retained Marina Bay media; Q03 stayed inside Q02's result set, contained no semantic filler, returned no fabricated 2025 match/evidence, and reported `EXACT`.
- Diagnostics prove the acceptance used the app-private verified `gemma-4-E2B-it.litertlm` pack through LiteRT-LM. No app `FATAL EXCEPTION`, ANR, `OutOfMemoryError`, or SQLite failure appeared in the bounded acceptance window.
- Final passing-run cleanup removed 74 imported rows and exactly 74 recorded MediaStore URIs; both database and shared-media checks reported `remainingCount=0`.
- Final diagnostics: `artifacts/device-runs/phase8_events_exif_20260722/diagnostics/20260722_040847/`.

Failures and limitations:

- Event segmentation currently uses time, album, GPS, and user corrections. People-overlap and learned event prototype vectors remain future work.
- Event corrections have a tested storage/compiler API but no merge/split/rename UI yet.
- E4B remains an honest skip because no E4B pack was installed or benchmarked; the connected E2B pack/settings flow was preserved.

Next phase:

- Add event correction UI as a narrow slice, then implement video keyframe indexing separately. Keep the consumer-network/telemetry audit and offlineDemo end-to-end acceptance as an explicit privacy-hardening gate.

## Phase 9A — distribution network and telemetry hardening (22 July 2026)

Status: **Implemented with a bounded physical-device gate. `offlineDemo` still has no Internet permission. `consumer` retains Internet solely for its user-started Gemma pack downloader, validates every initial/redirect endpoint against an HTTPS Hugging Face allowlist, and no longer registers ML Kit's transitive DataTransport backend, job service, or alarm receiver.**

Files changed:

- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/java/com/askphotos/android/GemmaModelDownloader.kt`
- `android/app/src/test/java/com/askphotos/android/GemmaModelCatalogTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/NetworkPrivacyAcceptanceTest.kt`

Architecture decisions:

- Google documents that bundled ML Kit inference inputs/results stay on-device, but the Android SDK collects app/device diagnostics and usage analytics. This is stronger network behavior than the product promise permits when `consumer` has Internet access.
- ML Kit's local vision implementations directly reference `CCTDestination`; removing the CCT classes caused `NoClassDefFoundError` in OCR/labeling and face detection on the phone. Those runtime classes therefore remain for compatibility, while all upload discovery/scheduling Android components are removed at manifest merge. Local ML Kit calls report `Transport backend 'cct' is not registered` and cannot discover or schedule the CCT uploader.
- The downloader no longer follows redirects implicitly. It accepts HTTPS only, rejects embedded credentials/non-443 ports, caps redirects at five, and validates every hop against `huggingface.co`, its subdomains, or Hugging Face's `*.hf.co` delivery hosts.
- A connected regression asserts that Internet permission exactly matches `BuildConfig.ALLOW_MODEL_DOWNLOAD` and enumerates installed services, receivers, and providers to reject any DataTransport/CCT component.

Commands run (serial masked):

```powershell
.\gradlew.bat :app:testOfflineDemoDebugUnitTest :app:testConsumerDebugUnitTest :app:processOfflineDemoDebugMainManifest :app:processConsumerDebugMainManifest --console=plain
.\gradlew.bat :app:dependencyInsight --configuration consumerDebugRuntimeClasspath --dependency transport-backend-cct --console=plain
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root <repo>\android -Module :app -Variant ConsumerDebug -Serial <masked>
.\gradlew.bat :app:connectedConsumerDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.askphotos.android.NetworkPrivacyAcceptanceTest,com.askphotos.android.LocalMlKitInferenceAcceptanceTest,com.askphotos.android.RealFaceDetectionAcceptanceTest' --console=plain
python C:\Users\anupk\.codex\skills\android-device-diagnostics\scripts\android_diagnostics.py --serial <masked> --package com.askphotos.android --minutes 10 --keywords CctTransportBackend,firebaselogging.googleapis.com,TransportRuntime,AndroidRuntime,FATAL,ANR,OutOfMemory --out artifacts\device-runs\phase9_network_privacy_20260722 --screenshot
```

Verification results:

- Both flavored unit suites passed; both manifests merged. The final merged `offlineDemoDebug` manifest contains no Internet permission and neither merged manifest contains a DataTransport service/receiver.
- ConsumerDebug built, installed, and launched on the sole authorized Samsung SM-F966B (Android 16/API 36, arm64-v8a, SM8750, approximately 11.4 GB RAM).
- The connected network/privacy component regression passed. The existing real bundled face-detector acceptance also passed, proving local ML Kit initialization/inference survives removal of the transport components.
- A diagnostic ML Kit call exercised bundled image labeling and OCR. Both initialized and returned normally, but the selected street-sign asset produced zero OCR text, so that new accuracy assertion failed and was not retained as a regression test. Per the two-cycle rule it was not weakened or retried with another fixture.
- The final three-test Gradle task therefore reported 2 passed / 1 failed. The failure was only `Bundled OCR returned no text`; there was no crash, ANR, OOM, missing-class error, or transport request.
- Device logs from the bounded test window contain 0 `CctTransportBackend` lines and 0 `firebaselogging.googleapis.com` lines. They contain 16 expected `TransportRuntime: Transport backend 'cct' is not registered` warnings, confirming the local SDK attempted to log but no backend was registered.

Artifacts:

- `artifacts/device-runs/phase9_network_privacy_20260722/20260722_042648/`
- `android/app/build/reports/androidTests/connected/debug/flavors/consumer/index.html`
- `android/app/build/reports/tests/testOfflineDemoDebugUnitTest/`
- `android/app/build/reports/tests/testConsumerDebugUnitTest/`

Failures and limitations:

- ML Kit's telemetry API/runtime classes remain in the APK because its local vision clients hard-reference them. The application removes their discoverable backend and schedulers; replacing ML Kit entirely with independently benchmarked PaddleOCR and a selected open-source face/label model remains the cleanest long-term elimination of those dormant classes.
- No multi-gigabyte model download was started during this gate. The E2B pack already installed in app-private storage was preserved. Redirect policy is unit-tested, while full resume/download/checksum acceptance remains pending on a disposable network/device run.
- The consumer network claim is supported by manifest/component inspection and a bounded device-log window, not by a packet capture. Add an external packet-capture gate before release if an independent transport-level proof is required.

Next phase:

- Keep OCR-engine replacement and an external packet-capture acceptance as separate privacy slices. Resume product work with event correction UI or video keyframes only after choosing that ordering.

## Phase 8B - video keyframes (22 July 2026)

Status: **Implemented and passed through a non-uninstalling direct-instrumentation physical-device gate.**

Files changed:

- Room schema/version 11, video-keyframe entity/DAO/migration, index stages, embedding worker, repository retrieval/evidence, and timestamp playback UI.
- `android/app/src/main/java/com/askphotos/android/VideoKeyframeExtractor.kt`
- `android/app/src/test/java/com/askphotos/android/VideoKeyframePolicyTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/SeededVideoKeyframeAcceptanceTest.kt`
- deterministic synthetic-video corpus generator and Q11 expected-query fixture.
- `tools/device/run_connected_acceptance.py` now understands the distribution flavours, supports focused test classes, and verifies that `adb install -r` preserves app-private data before seeding.

Architecture decisions:

- Videos remain parent media records. Bounded keyframes are child records with stable IDs, timestamps, private previews, labels/OCR, pHash/quality metadata, producer version, and embedding version.
- Extraction samples at most 12 center timestamps and collapses adjacent near-identical frames by pHash. Keyframe vector hits resolve back to the parent video and carry timestamped evidence.
- Result evidence can open a video through the system content URI and seek to the verified keyframe timestamp.
- Existing videos are requeued by migration 10-to-11; stage work remains idempotent and separately versioned.

Commands run (serial masked):

```powershell
python tools/sample_gallery/build_sample_gallery.py --profile core --output build/sample-gallery/core
python tools/sample_gallery/verify_licenses.py --gallery build/sample-gallery/core
.\gradlew.bat :app:testOfflineDemoDebugUnitTest --tests com.askphotos.android.VideoKeyframePolicyTest :app:compileOfflineDemoDebugAndroidTestKotlin
powershell -ExecutionPolicy Bypass -File C:\Users\anupk\.codex\skills\android-build-install\scripts\build_install_android.ps1 -Root <repo>\android -Module :app -Variant ConsumerDebug -Serial <masked>
python tools/device/seed_gallery.py --serial <masked> --gallery build/sample-gallery/core --run-id phase8_video_keyframes_20260722
python tools/device/sync_seeded_gallery.py --serial <masked> --run-id phase8_video_keyframes_20260722 --action import
.\gradlew.bat :app:connectedConsumerDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.galleryRunId=phase8_video_keyframes_20260722 -Pandroid.testInstrumentationRunnerArguments.class=GalleryRoomMigrationTest,SeededVideoKeyframeAcceptanceTest
python C:\Users\anupk\.codex\skills\android-device-diagnostics\scripts\android_diagnostics.py --serial <masked> --package com.askphotos.android --minutes 20 --keywords VideoKeyframe,MediaMetadataRetriever,AndroidRuntime,FATAL,ANR,OutOfMemory,SQLite,Room --out artifacts\device-runs\phase8_video_keyframes_20260722\diagnostics --screenshot
python -m unittest test_common.py test_run_connected_acceptance.py -v
python tools/device/run_connected_acceptance.py --serial <masked> --variant consumerDebug --run-id phase8_video_direct2_20260722 --test-class com.askphotos.android.SeededVideoKeyframeAcceptanceTest --skip-index-recovery --instrument-timeout-seconds 720
.\gradlew.bat :app:testOfflineDemoDebugUnitTest :app:testConsumerDebugUnitTest :app:lintOfflineDemoDebug :app:lintConsumerDebug --console=plain
python C:\Users\anupk\.codex\skills\android-device-diagnostics\scripts\android_diagnostics.py --serial <masked> --package com.askphotos.android --minutes 10 --keywords VideoKeyframe,MediaMetadataRetriever,AndroidRuntime,FATAL,ANR,OutOfMemory,SQLite,Room --out artifacts\device-runs\phase8_video_direct2_20260722\diagnostics-final --screenshot
```

Verification results:

- Core corpus generation passed with 75 items. The new CC0 synthetic video is 18 seconds, 180 frames at 10 fps. License/checksum verification passed for all 75 items and 17 license records.
- Focused video-keyframe unit tests and Android-test Kotlin compilation passed.
- ConsumerDebug built and installed successfully on the sole authorized Samsung SM-F966B (Android 16/API 36, arm64-v8a, SM8750).
- Seeding created exactly 75 run-scoped MediaStore URIs. The import command reported 75 requested/changed/imported records.
- The first Gradle-managed connected run reported 1 pass/1 failure: migration passed, while the video test saw no imported video. The cause was the Gradle lifecycle replacing the seeded target package before instrumentation and uninstalling it afterward, not a video importer failure.
- The repaired harness builds from the Android project root, uses current `consumerDebug`/`offlineDemoDebug` APK paths, installs both APKs with `adb install -r -t`, and aborts if an app-private preservation sentinel disappears.
- Final direct physical-device acceptance passed: `SeededVideoKeyframeAcceptanceTest`, 1 test, 0 failures, 32.525 seconds. It proved video import, ready indexing state, bounded/distinct keyframes, private previews, complete stage state, semantic retrieval, timestamp evidence in the grounded answer, and playback-at-match UI.
- Both complete flavour unit suites and both lint gates passed in 95.9 seconds. Six Python harness/unit tests passed.
- A final diff audit found and fixed a vector-grounding edge case: timestamp evidence now comes only from the parent or keyframe vector that actually won ranking. Its parent-wins/frame-wins regression passed in both distributions; both lint gates passed again in 111.2 seconds. The final ConsumerDebug APK then built and installed successfully in 20 seconds.

Cleanup and artifacts:

- The connected Gradle task uninstalled the target package at teardown. This erased app-private state, including the previously installed E2B model pack, and revoked ownership of seeded MediaStore rows. This is a harness defect that must be fixed before another seeded connected run.
- Cleanup still removed only this run's data: Android's exact-URI confirmation deleted 74 image/video items, and the one verified synthetic PDF was removed by its exact run-specific path. Its exact MediaStore row returned no result afterward. Both empty run directories were removed, temporary media grants were revoked, and no unrelated gallery item was touched.
- JUnit XML: `android/app/build/outputs/androidTest-results/connected/debug/flavors/consumer/TEST-SM-F966B - 16-_app-consumer.xml`.
- Diagnostics and screenshot: `artifacts/device-runs/phase8_video_keyframes_20260722/diagnostics/20260722_050921/`.
- Seed manifest: `artifacts/device-runs/phase8_video_keyframes_20260722/seed-result.json`.
- Passing direct-run artifacts: `artifacts/device-runs/phase8_video_direct2_20260722/`.
- Passing-run diagnostics and screenshot: `artifacts/device-runs/phase8_video_direct2_20260722/diagnostics-final/20260722_051905/`.
- Passing-run cleanup removed 75/75 imported rows, wrote 75 tombstones, deleted four private previews, deleted exactly 75 recorded MediaStore URIs, and reported zero remaining items.

Failures and limitations:

- Gradle `connected*AndroidTest` remains unsuitable for stateful seeded/model-pack acceptance because it uninstalls the app. The repaired repository harness uses direct instrumentation and proves app-private preservation before seeding.
- The old failed-run diagnostics include crashes from a temporary debug cleanup recovery activity. That activity was removed and is not part of the implemented slice or passing evidence.
- E2B real-model acceptance was not run in this slice, and the installed pack must now be re-imported. E4B remains not run.

Next phase:

- Re-import the E2B model pack and rerun the real-model planner/verifier/answer suite through the corrected non-uninstalling harness. Keep E4B an explicit skip until a valid pack is installed and benchmarked.

## Phase 6B - model selection state hygiene (22 July 2026)

Status: **Verified on the physical device.**

- `GemmaSettingsUiTest` passed through direct instrumentation and proved the consumer Settings screen offers E2B download while applying the device capability policy to E4B.
- The test previously persisted E4B after finishing. An `@After` teardown now restores E2B through `ModelPackManager`, keeping the required default stable even when the test assertion fails.
- Consumer Android-test assembly passed in 14 seconds. The rebuilt test APK installed with `adb install -r -t`; direct instrumentation passed 1 test in 1.375 seconds; the app-private preference then contained `tier=E2B`.
- No verified `.litertlm` source remains on the host after the earlier Gradle uninstall erased the device copy. Real E2B inference remains pending a new app-managed download or verified external import; this test does not claim inference coverage.

## Phase 6C - app-managed E2B restoration and real inference acceptance (22 July 2026)

Status: **Passed on the physical device through the consumer Settings download flow and direct, state-preserving instrumentation.**

Architecture and model-pack results:

- The Settings screen offers E2B and E4B selection. E2B remains the default; E4B is optional and was not downloaded or executed in this gate.
- The consumer build downloaded the pinned E2B LiteRT-LM artifact into app-private storage through WorkManager. Network access was used only for this explicit model action.
- The active generation is `generation-7fa1d78473894f7e-*`. The installed model is exactly 2,583,085,056 bytes and its independently computed SHA-256 is `ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42`, matching the pinned catalog.
- The generated installed manifest identifies Gemma 4 E2B, multimodal support, source revision `7fa1d78473894f7e736a21d920c3aa80f950c0db`, Gemma Terms, and LiteRT-LM 0.14.0.
- Visual QA confirmed that Settings renders the installed Gemma revision, tier, size, signed SHA prefix, and replacement action. The nearby `Not installed - fixture semantics remain active` message belongs to the separate SigLIP2 retrieval pack, which remains a known gap.

Commands run (serial masked):

```powershell
adb -s <masked> shell input tap <Download-Gemma-4-E2B-button>
adb -s <masked> shell run-as com.askphotos.android cat files/models/gemma/current
adb -s <masked> shell run-as com.askphotos.android stat -c '%s' <active-model-path>
adb -s <masked> shell run-as com.askphotos.android sha256sum <active-model-path>
adb -s <masked> shell am instrument -w -r -e class com.askphotos.android.RealGemmaPlannerAcceptanceTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
adb -s <masked> shell am instrument -w -r -e class com.askphotos.android.RealGemmaVisualVerifierAcceptanceTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
adb -s <masked> shell am instrument -w -r -e class com.askphotos.android.RealGemmaGroundedAnswerAcceptanceTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
adb -s <masked> shell dumpsys thermalservice
adb -s <masked> logcat -d -t 3000
```

Physical-device results:

- Device: Samsung SM-F966B, Android 16/API 36, arm64-v8a, SM8750, approximately 11.4 GB RAM. Full serial is omitted.
- Real planner: 1 JUnit test passed in 59.486 seconds. English, Hindi, and Hinglish cases all used E2B on GPU, returned valid `FIND_MEDIA`/`IMAGES` plans without fixture fallback, and each used the single allowed repair pass. Per-case wall times were 19.679, 15.685, and 24.011 seconds.
- Real visual verifier: 1 JUnit test passed in 11.764 seconds. It used one GPU model call, no repair, accepted one candidate, and produced three verified evidence conditions.
- Real grounded answer: 2 JUnit tests passed in 5.940 seconds. The model-backed answer cited two existing evidence records with no fallback; the no-answer case bypassed Gemma and could not fabricate evidence.
- Model traces reported post-close PSS of approximately 282-315 MB. Current thermal readings after the run were status 0, approximately 37.2 C AP and 37.4 C skin.
- The bounded final log window contained no target-app `FATAL EXCEPTION`, ANR, `OutOfMemoryError`, or native fatal-signal marker.

Artifacts:

- `artifacts/device-runs/phase6_e2b_restore_20260722/installed-pack-proof.txt`
- `artifacts/device-runs/phase6_e2b_restore_20260722/real-gemma-planner.txt`
- `artifacts/device-runs/phase6_e2b_restore_20260722/real-gemma-verifier.txt`
- `artifacts/device-runs/phase6_e2b_restore_20260722/real-gemma-grounded-answer.txt`
- `artifacts/device-runs/phase6_e2b_restore_20260722/gemma-e2b-installed.png`
- `artifacts/device-runs/phase6_e2b_restore_20260722/download-diagnostics/20260722_095559/`

Failures and limitations:

- E4B remains an explicit, honest skip: the device recommends it, but no verified E4B pack was installed or benchmarked.
- The SigLIP2 retrieval pack is not installed, so real image/text embedding acceptance and target-scale semantic retrieval are not demonstrated by this phase.
- All three planner cases required the bounded repair call. The contract is satisfied, but improving first-pass JSON conformance would reduce latency.

Next phase:

- Restore and benchmark the pinned SigLIP2 image/text retrieval pack without disturbing the verified E2B generation, then run core/5k/20k semantic retrieval gates. Keep E4B optional.

## Phase 4C - quantized SigLIP2 pack installation (22 July 2026)

Status: **The signed q8 pack is installed and both towers execute on the physical device, but semantic acceptance failed. This capability is not reported as retrieval-quality complete. Repairs stopped after two device cycles as required.**

Source selection and architecture:

- Repository research found LibrePhotos using separate SigLIP2 ONNX vision/text towers for semantic gallery features. The selected artifacts are the HF-staff `onnx-community/siglip2-base-patch16-224-ONNX` q8 dual towers, not an image-only LiteRT release.
- Artifact commit: `ba1f3b0843f24bc5417d38e19c37b287d719b2f4`; source checkpoint revision: `022b6f71160ffb0169ca4709e2d7e25be659598a`; license: Apache-2.0.
- The app retains the existing LiteRT retrieval-pack path and adds pinned ONNX Runtime Mobile 1.23.2 support. Signed manifests declare and validate the runtime, exact artifact repository/revision, tensor preprocessing, tokenizer contract, file sizes, and SHA-256 values.
- Model weights remain ignored build/device artifacts. No ONNX file or generated `.agretrieval` archive is committed.

Files changed:

- `android/gradle/libs.versions.toml`
- `android/app/build.gradle.kts`
- `android/app/src/main/java/com/askphotos/android/RetrievalModelPack.kt`
- `android/app/src/main/java/com/askphotos/android/LiteRtImageTextEmbeddingEngine.kt`
- `android/app/src/test/java/com/askphotos/android/RetrievalPackValidationTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/RealSiglip2RetrievalAcceptanceTest.kt`
- `tools/model-conversion/build_onnx_retrieval_pack.py`
- `tools/model-conversion/PackManifestSigner.java`

Verification and device results:

- Host inspection confirmed q8 vision input `[N,3,224,224]`/output `[N,768]` and text input `[N,64] INT64`/output `[N,768]`. Source file hashes are recorded in the installed manifest.
- Pack creation validated deterministic finite encoder outputs, exported the 256k SentencePiece vocabulary, signed the manifest with the APK key, and produced a 267,744,226-byte ignored archive with SHA-256 `34cb0f0ade3891f5b683997c426677efa04a36f1a51c406c211b4a26be9b4f2b`.
- Focused JVM manifest/tokenizer tests, Android-test compilation, and ConsumerDebug/test APK assembly passed in 1 minute 24 seconds. ConsumerDebug installed state-preservingly; the E2B generation pointer remained unchanged.
- First physical run failed before inference because the atomic installer called `StatFs` before ensuring its private root existed. The installer now owns creation of its root/generation directories.
- Final allowed run installed and activated `generation-ba1f3b0-q8-*`, loaded both q8 ONNX towers, returned finite normalized 768-dimensional embeddings, and completed four encoder calls in approximately 7.6 seconds without crash, ANR, or OOM.
- The semantic assertion failed: text `This is a photo of a red square.` scored the red image `0.060938284` and blue image `0.06414157`. The assertion was not weakened and no third repair cycle was attempted.
- Settings visual QA shows both the verified E2B pack and `siglip2-base-p16-224-q8 ba1f3b0-q8`, 366.9 MB, 768 dimensions, in app-private storage.

Artifacts:

- `artifacts/device-runs/phase4_siglip2_q8_20260722/real-siglip2-retrieval.txt`
- `artifacts/device-runs/phase4_siglip2_q8_20260722/real-siglip2-retrieval-final.txt`
- `artifacts/device-runs/phase4_siglip2_q8_20260722/installed-retrieval-manifest.json`
- `artifacts/device-runs/phase4_siglip2_q8_20260722/siglip2-installed.png`

Failures and limitations:

- Installation and tensor execution are proven; cross-modal semantic correctness is not. Likely follow-up areas are tokenizer parity against the exported ONNX processor, prompt/preprocessing parity, and a labeled natural-image Recall@K calibration rather than synthetic solid colors.
- The initial `minimumSimilarity=0.1` is not calibrated and must not be presented as an accepted no-match threshold.
- The active q8 pack should be treated as experimental until a later, separately authorized repair slice passes natural-image and multilingual retrieval evaluation.

## Phase 2C - persistent multi-domain acceptance gallery (22 July 2026)

Status: **Passed for corpus construction, license/checksum validation, safe physical-device seeding, and app import. The run-scoped gallery is intentionally retained on the device for later functional testing.**

Files changed:

- `tools/sample_gallery/manifest.yaml`
- `tools/sample_gallery/expected_queries.yaml`
- `docs/implementation-status.md`
- `docs/connected-device-test-report.md`

Architecture decisions:

- The existing 75-item core already covered travel/location, beaches and sunsets, city/street scenes, food, flowers, text signs, duplicate variants, people/clothing relations, screenshots, receipts, boarding passes, Wi-Fi, menus, calendar, PDF, no-answer behavior, and video keyframes.
- Two pinned Wikimedia Commons CC0 sources were added for missing real-image domains: a domestic dog/pet and children playing football outdoors. Four deterministic metadata-preserving variants of each source bring the core corpus to 83 items without exceeding the 60-100 core profile gate.
- The source download batch was stopped after Wikimedia returned HTTP 429 on later candidates. Existing indoor and document fixtures cover those functions, so no redundant download retry was made.
- The retained device dataset is isolated under run ID `persistent_multidomain_20260722` in `Pictures/AgenticGalleryTest/` and `Documents/AgenticGalleryTest/`. Cleanup was deliberately not run. Only the exact URI list in the seed artifact may be removed later.

Commands run:

```powershell
python tools\sample_gallery\build_sample_gallery.py --profile core --output build\sample-gallery\core
python tools\sample_gallery\verify_licenses.py --gallery build\sample-gallery\core
python -m unittest test_fixture_metadata.py
python tools\device\preflight.py --serial <masked> --output artifacts\device-runs\persistent_multidomain_20260722\preflight.json
python tools\device\seed_gallery.py --serial <masked> --package com.askphotos.android --gallery build\sample-gallery\core --run-id persistent_multidomain_20260722 --artifacts artifacts\device-runs\persistent_multidomain_20260722\seed
python tools\device\sync_seeded_gallery.py --serial <masked> --package com.askphotos.android --run-id persistent_multidomain_20260722 --action import --artifacts artifacts\device-runs\persistent_multidomain_20260722\import
```

Verification and device results:

- Corpus: 83 items total: 81 images, one two-page PDF, and one video; 19 license records; 67 images dated 2024 for deterministic aggregation ground truth.
- License and generated-checksum verifier: PASS for all 83 gallery items and all 19 license records.
- Fixture metadata tests: PASS, 2 tests.
- Physical device preflight: Samsung SM-F966B, Android 16/API 36, arm64-v8a, SM8750, approximately 11.4 GB RAM, approximately 157 GB free data storage.
- Safe MediaStore seeder: COMPLETE, 83 created URIs, zero transfer retries, staging removed. No personal-gallery URI was targeted.
- App import: COMPLETE, 83 requested, 83 changed, 83 imported.
- App launch completed in 94 ms in the captured diagnostic window. WorkManager processed the retained video and its embedding worker returned success. The bounded launch window showed no target-app crash, ANR, or OOM.

Failures and limitations:

- Two direct Android 16 `adb shell content query` attempts failed because the shell provider rejected projection/selection syntax. This path was stopped after the two allowed repair attempts. Seeder and importer structured results remain the authoritative count evidence.
- The diagnostics helper produced a non-decodable screenshot file on Windows, so visual QA is not claimed for this slice.
- This slice seeds and imports the representative corpus; it does not claim that the still-experimental SigLIP2 tokenizer issue or every real-model acceptance query is fixed.

Artifacts:

- `artifacts/device-runs/persistent_multidomain_20260722/preflight.json`
- `artifacts/device-runs/persistent_multidomain_20260722/seed/persistent_multidomain_20260722/seed-result.json`
- `artifacts/device-runs/persistent_multidomain_20260722/import/persistent_multidomain_20260722/database-import-result.json`
- `artifacts/device-runs/persistent_multidomain_20260722/diagnostics/20260722_104740/`

Retained dataset cleanup command for a future explicit cleanup request:

```powershell
python tools\device\cleanup_gallery.py --serial <masked> --package com.askphotos.android --run-id persistent_multidomain_20260722 --artifacts artifacts\device-runs\persistent_multidomain_20260722\cleanup
```

Next phase:

- Use Q01-Q13 against this retained corpus while repairing SentencePiece parity for the experimental SigLIP2 q8 pack. Do not reseed or clean this run unless explicitly requested.

## Phase 4D - SigLIP2 SentencePiece BPE parity and semantic acceptance (22 July 2026)

Status: **Passed on the physical device. The installed q8 SigLIP2 image/text pack now has demonstrated tokenizer parity and cross-modal ranking over both synthetic controls and retained CC0 natural images.**

Root cause and architecture correction:

- The pinned `tokenizer.model` reports SentencePiece `model_type=2` (BPE), identity normalization, `add_dummy_prefix=false`, `remove_extra_whitespaces=false`, `escape_whitespaces=true`, and complete UTF-8 byte fallback.
- The earlier Android implementation incorrectly treated vocabulary scores as unigram whole-path scores, added a dummy prefix, collapsed/trimmed whitespace, applied NFKC, and allowed byte fallback to compete with known pieces. This produced `th + is` instead of the checkpoint's `this` token and invalid text embeddings.
- Android now preserves the pinned normalizer contract, creates code-point/byte symbols, and repeatedly merges the highest-scoring adjacent BPE pair with deterministic left-to-right tie handling. Byte pieces are excluded from ordinary merges and are used only for otherwise unknown code points.
- The tokenizer loader now rejects incomplete byte-fallback vocabularies. JVM fixtures cover no dummy prefix, repeated spaces, leading spaces, tab byte fallback, and BPE merge ordering.
- Real-device acceptance asserts the exact red/blue prompt token IDs emitted by the official SentencePiece processor before running ONNX inference.

Files changed:

- `android/app/src/main/java/com/askphotos/android/LiteRtImageTextEmbeddingEngine.kt`
- `android/app/src/test/java/com/askphotos/android/RetrievalPackValidationTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/RealSiglip2RetrievalAcceptanceTest.kt`
- `tools/model-conversion/README.md`
- `docs/implementation-status.md`
- `docs/connected-device-test-report.md`

Verification:

- Host ONNX/SentencePiece oracle: the official q8 towers prefer red for the red prompt and blue for the blue prompt; retained dog and football fixtures also rank correctly.
- Focused JVM pack/tokenizer suite: PASS (`RetrievalPackValidationTest`), 7 tests.
- ConsumerDebug assemble/install: PASS on one physical device, state-preserving.
- ConsumerDebug Android-test assembly and direct test-APK install: PASS.
- Real SigLIP2 instrumentation: PASS, 1 test in 9.99 seconds. The four synthetic encoder calls took 5.031 seconds.
- Synthetic similarities: red `0.13278` vs blue `0.09285` for the red prompt; blue `0.14374` vs red `0.08645` for the blue prompt.
- Retained natural-image similarities: dog `0.07813` vs football `0.01419` for the dog prompt; football `0.11568` vs dog `-0.03187` for the football prompt.
- PSS trace: 626,837 KB before the measured synthetic block and 288,875 KB after it. Thermal status remained 0; post-run skin was approximately 37.9 C. The bounded diagnostic window contained no target-app fatal exception, ANR, or OOM.
- E2B and retrieval generation pointers were unchanged after state-preserving app/test installation. The retained `persistent_multidomain_20260722` gallery was not reseeded or cleaned.

Commands run:

```powershell
.\gradlew.bat :app:testConsumerDebugUnitTest --tests com.askphotos.android.RetrievalPackValidationTest --console=plain
powershell -ExecutionPolicy Bypass -File <android-build-install-skill> -Root <repo>\android -Module :app -Variant ConsumerDebug -Serial <masked>
.\gradlew.bat :app:assembleConsumerDebugAndroidTest --console=plain
adb -s <masked> install -r -t app\build\outputs\apk\androidTest\consumer\debug\app-consumer-debug-androidTest.apk
adb -s <masked> shell am instrument -w -r -e class com.askphotos.android.RealSiglip2RetrievalAcceptanceTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
```

Artifacts:

- `artifacts/device-runs/phase4_siglip2_tokenizer_20260722/real-siglip2-retrieval.txt` (first-cycle failure proving the BPE mismatch)
- `artifacts/device-runs/phase4_siglip2_tokenizer_20260722/real-siglip2-retrieval-final.txt` (passing final gate)
- `artifacts/device-runs/phase4_siglip2_tokenizer_20260722/diagnostics/20260722_110424/`

Remaining limitations:

- This proves correct dual-encoder behavior on two synthetic controls and two retained natural-image domains. Core Recall@K calibration, the no-match threshold, multilingual retrieval, and 5k/20k latency remain separate required gates.
- The simple BPE merge implementation prioritizes correctness for short gallery queries. A priority-queue optimization should be benchmarked only if profiling shows tokenizer time matters.

Next phase:

- Run the Q01-Q13 core retrieval evaluation with the corrected encoder, then benchmark exact vector scan/index recovery on the 5k and 20k profiles without changing the accepted tokenizer contract.

## Phase 2D - Persistent licensed multi-domain corpus on second device (22 July 2026)

Status: **Recovered and passed. The timed-out duplicate run was reconciled using exact recorded/recovered URIs, then reseeded as one verified 83-item set with demonstrated idempotency.**

Scope and safety:

- Reused the pinned, already-downloaded open-license corpus rather than fetching unreviewed media. It covers travel/landmarks, beaches/sunsets, architecture, food, flowers, pets, outdoor sport, street text, people/clothing, receipts, Wi-Fi, boarding pass, hotel, multilingual menus, calendar, PDF, and video.
- License/checksum verification passed for all 83 gallery items and 19 license records.
- Seeded only to run-scoped MediaStore paths `Pictures/AgenticGalleryTest/persistent_multidomain_20260722_f731u/` and `Documents/AgenticGalleryTest/persistent_multidomain_20260722_f731u/`.
- The second seed result initially recorded 83 created content URIs while an exact audit found 166 rows, all in the run-specific paths and owned by `com.askphotos.android`. Recovery removed 83 recorded rows plus 83 reconstructed orphan URIs after writing an app-private audit record; `remainingCount` was zero.
- The clean reseed created 83 URIs (81 images, one PDF, one video). A repeated host invocation returned the existing manifest in one second with `resumedExistingSeed=true`; a second MediaStore audit proved exactly 83 app-owned rows and zero `(1)` duplicate names.
- A direct Android instrumentation check confirmed that both run-scoped paths are visible through MediaStore (`SeededGalleryTest`, PASS, 1 test in 0.032 s).

Device and commands:

- Samsung SM-F731U, Android 16/API 36, arm64-v8a, SM8550, approximately 7.0 GB RAM, approximately 193 GB free data storage.
- The host harness and debug receiver now treat a completed active run as idempotent, distinguish a later cleanup marker by device modification time, reject accidental reuse of a cleaned run ID unless reset is explicit, and recover only orphan rows whose exact test path and owner package prove test ownership.

```powershell
python tools\sample_gallery\verify_licenses.py --gallery build\sample-gallery\core
python tools\device\preflight.py --serial <masked> --output artifacts\device-runs\persistent_multidomain_20260722_f731u\preflight.json
python tools\device\seed_gallery.py --serial <masked> --package com.askphotos.android --gallery build\sample-gallery\core --run-id persistent_multidomain_20260722_f731u --artifacts artifacts\device-runs\persistent_multidomain_20260722_f731u
adb -s <masked> shell am instrument -w -r -e galleryRunId persistent_multidomain_20260722_f731u -e class com.askphotos.android.SeededGalleryTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
```

Artifacts:

- `artifacts/device-runs/persistent_multidomain_20260722_f731u/preflight.json`
- `artifacts/device-runs/persistent_multidomain_20260722_f731u/persistent_multidomain_20260722_f731u/seed-result.json`
- `artifacts/device-runs/persistent_multidomain_20260722_f731u/seeded-gallery-test.txt`
- `artifacts/device-runs/persistent_multidomain_20260722_f731u/run-scoped-mediastore-rows.txt`

Retained dataset cleanup command for a future explicit cleanup request:

```powershell
python tools\device\cleanup_gallery.py --serial <masked> --package com.askphotos.android --run-id persistent_multidomain_20260722_f731u --artifacts artifacts\device-runs\persistent_multidomain_20260722_f731u\cleanup
```

Limitation:

- The original SM-F966B reference device disconnected before the new Q01-Q13 evaluator could run. This second phone is documented only for corpus seeding/visibility; it is not being substituted for the original device's retained E2B/SigLIP acceptance state.
- The first event/follow-up run failed because protected GPS extraction discarded intact 2024 EXIF dates. Pulled bytes matched the host SHA-256. GPS extraction is now isolated from date parsing; focused JVM tests pass, the app compiled/installed, and the physical-device event plus two-follow-up test passed in 33.865 seconds.
- Signed SigLIP installation is now independent of Gemma availability. On SM-F731U the q8 pack installed with no E2B present and passed exact tokenizer plus red/blue/dog/football ranking in 19.117 seconds; measured inference block was 7.163 seconds and PSS moved from 261,192 KB to 538,849 KB.
- First Q01-Q13 core run with fixture planner and real SigLIP/OCR/events: 9 PASS, 2 FAIL, 2 SKIP. Merchant filtering was corrected to use structured merchant/document identity instead of arbitrary OCR body mentions; Q04 then passed. Second run: 10 PASS, 1 FAIL, 2 SKIP.
- The evaluator now resolves all 83 recorded seed URIs to app IDs and supplies an executor-owned initial scope; every standalone Q04-Q13 query is scoped to the same IDs. Unit tests, Android-test assembly, and physical installation passed. This removes personal-gallery contamination without allowing the model to generate IDs.
- Run-scoped Q01-Q13 remained 10 PASS, 1 FAIL, 2 SKIP. Q03 correctly applied the 2025 filter and returned six seeded items: four Goa and two dog fixtures that leaked through Q02's broad semantic/event result set. A second hard lexical-narrowing experiment removed valid Marina Bay results (9 PASS, 2 FAIL, 2 SKIP) and was reverted rather than weakening assertions.
- Remaining work is a calibrated follow-up retrieval policy that keeps true Marina Bay semantic matches while excluding unrelated event expansion. No full core-suite pass is claimed.

## Phase 7C - Corroborated semantic follow-up refinement (22 July 2026)

Status: **Implemented and passed the complete run-scoped Q01-Q13 evaluator on the connected physical device: 11 PASS, 0 FAIL, 2 explicit people-index SKIP.**

Files changed:

- `android/app/src/main/java/com/askphotos/android/GalleryRepository.kt`
- `android/app/src/main/java/com/askphotos/android/FollowUpRefinementPolicy.kt`
- `android/app/src/test/java/com/askphotos/android/FollowUpRefinementPolicyTest.kt`
- `docs/implementation-status.md`
- `docs/connected-device-test-report.md`

Architecture decisions:

- A scoped semantic follow-up no longer retains every weak vector result merely because it clears the pack-wide recall threshold. When lexical or event retrieval independently corroborates semantic candidates, the executor uses `semantic AND (lexical OR event)` as the refinement set.
- If no independent channel corroborates any semantic candidate, the executor preserves semantic-only fallback. This keeps natural visual refinements such as `Only bicycles` usable when no title, OCR, or event text contains the concept.
- Initial searches are unchanged. The policy activates only for app-owned result-set scopes, keeps the parent result-set boundary, and does not put media IDs or retrieval policy into model output.
- Acceptance expectations and the Q03 exact no-result assertion were not weakened.

Commands run:

```powershell
.\gradlew.bat :app:testConsumerDebugUnitTest --tests com.askphotos.android.FollowUpRefinementPolicyTest --tests com.askphotos.android.SearchExecutionScopeTest --tests com.askphotos.android.ResultSetPlanPatchResolverTest --console=plain
powershell -ExecutionPolicy Bypass -File <android-build-install> -Root <repo>\android -Module :app -Variant ConsumerDebug -Serial <masked>
.\gradlew.bat :app:assembleConsumerDebugAndroidTest --console=plain
adb -s <masked> install -r -t app-consumer-debug-androidTest.apk
adb -s <masked> shell am instrument -w -r -e galleryRunId persistent_multidomain_20260722_f731u -e class com.askphotos.android.CoreCorpusEvaluationAcceptanceTest com.askphotos.android.test/androidx.test.runner.AndroidJUnitRunner
```

Unit tests:

- `FollowUpRefinementPolicyTest`, `SearchExecutionScopeTest`, and `ResultSetPlanPatchResolverTest`: PASS.
- ConsumerDebug Android-test assembly: PASS.

Connected-device tests:

- App build/install: PASS on Samsung SM-F731U, Android 16/API 36, arm64-v8a, SM8550.
- `CoreCorpusEvaluationAcceptanceTest`: PASS, one test in 10.833 seconds.
- Structured result: 11 PASS, 0 FAIL, 2 SKIP. Q07/Q08 remain explicit skips because face indexing is disabled and no reviewed identity pack is installed.
- Q02 narrowed from 53 weakly fused results to 5 corroborated Marina Bay results, retained the expected item at rank 1, and retained local image-text evidence.
- Q03 returned zero results in 37 ms, reported `EXACT`, and emitted no unsupported claim or evidence.
- Bounded post-run logcat contained no app fatal exception, ANR, `OutOfMemoryError`, or SQLite exception.

Device and backend:

- The retained app-owned corpus is `persistent_multidomain_20260722_f731u`: 83 seeded items (81 images, one PDF, one video).
- Retrieval used the installed signed SigLIP2 q8 pack `ba1f3b0-q8-core05` at minimum similarity `0.05`, plus local OCR/events and the deterministic fallback planner. E2B is not installed on this device and is not claimed for this run.
- The database contained 314 ready gallery items, but every evaluator query was executor-scoped to the 83 recorded seed IDs; no personal item contributed to query results or metrics.

Artifacts:

- `artifacts/device-runs/persistent_multidomain_20260722_f731u/core-q01-q13-corroborated-followup.txt`
- `artifacts/device-runs/persistent_multidomain_20260722_f731u/core-q01-q13-corroborated-followup.json`
- `artifacts/device-runs/persistent_multidomain_20260722_f731u/core-q01-q13-corroborated-followup-errors.txt`

Failures and limitations:

- Q07/Q08 still require a reviewed open-source face-embedding pack, explicit opt-in, and connected identity acceptance.
- This run does not demonstrate real E2B on SM-F731U, E4B, multilingual SigLIP retrieval, or 5k/20k sustained indexing/vector performance.

Next phase:

- Implement and accept the opt-in people/identity slice, then run the 5k and 20k performance/recovery gates as separate bounded tasks.

## Phase 2E - Seedable 5k/20k stress profiles and transfer-scale audit (22 July 2026)

Status: **Host corpus generation/verification passed. Physical-device stress seeding is NOT PASSED; two bounded transport repair cycles failed before MediaStore insertion, so further transport work stopped.**

Files changed:

- `tools/sample_gallery/generate_stress_gallery.py`
- `tools/sample_gallery/test_generate_stress_gallery.py`
- `tools/device/seed_gallery.py`
- `tools/device/test_seed_gallery.py`
- `android/app/src/debug/java/com/askphotos/android/TestGallerySeederReceiver.kt`
- `docs/implementation-status.md`
- `docs/connected-device-test-report.md`

Architecture decisions:

- Stress profiles now emit the same `gallery-manifest.json` contract required by the safe MediaStore seeder, instead of producing media plus an unusable mapping only.
- Every stress item preserves the licensed source ID, exact core derivative ID, license, synthetic marker, labels, event/album, capture timestamp, and GPS. The profile also retains `stress-mapping.json` for evaluation lineage.
- Stress generation fails closed when the destination media directory is nonempty, preventing stale files from silently diverging from the manifest.
- The shared checksum ledger is updated profile-by-profile without deleting core or other stress-profile entries. Archive hashing in the device harness is streamed in 1 MiB blocks instead of loading a possible 500 MiB archive into host memory.
- The debug receiver's extraction ceiling is now exactly 20,001 entries (20,000 media plus one manifest). Archive-size, extracted-size, canonical-path, filename, MIME, and run-ID checks remain enforced.
- An experimental binary provider stream was tested, failed at real scale, and was removed from final source. The verified small chunk transport remains the only enabled path.

Host verification actually run:

```powershell
python -m unittest discover -p 'test_*.py'  # tools/sample_gallery: 3 PASS
python -m unittest discover -p 'test_*.py'  # tools/device: 12 PASS
python tools/sample_gallery/build_sample_gallery.py --profile stress-5k --output build/sample-gallery/stress-5k
python tools/sample_gallery/build_sample_gallery.py --profile stress-20k --output build/sample-gallery/stress-20k
python tools/sample_gallery/verify_licenses.py --gallery build/sample-gallery/stress-5k
python tools/sample_gallery/verify_licenses.py --gallery build/sample-gallery/stress-20k
```

Generated profiles:

| Profile | Items | Media bytes | Mapping SHA-256 | Verification |
|---|---:|---:|---|---|
| stress-5k | 5,000 | 107,139,872 | `96598e0c4b54844ade1f4a1811f360412f268219d4a1e904171f88c61b2e2234` | manifest/media, 19 license records, checksums, EXIF/GPS PASS |
| stress-20k | 20,000 | 428,313,795 | `d5e6fb280dd639ae25d647ff18f333a78f6f6a3d5a5cb154631ddcc7e96fb04e` | manifest/media, 19 license records, checksums, EXIF/GPS PASS |

Connected-device work:

- Final ConsumerDebug source build/install: PASS on Samsung SM-F731U, Android 16/API 36, arm64-v8a, SM8550. Device free space was approximately 190 GiB, so storage was not the blocker.
- Transfer cycle 1 used the existing SHA-256-validated, resumable chunk provider. It reached 2,200 of 8,980 chunks with 24 successful transient retries, then eight parallel adb calls remained stuck beyond their 30-second command timeout. The single host seed process was terminated; no seed receiver broadcast or MediaStore insertion had begun.
- Transfer cycle 2 tested a binary `content write` provider route after its small on-device hash/size contract passed. At real scale Android delivered only 2,497,180 of the expected 110,337,694 archive bytes. Exact adoption rejected the partial file before extraction or MediaStore insertion.
- Per the two-cycle rule, no third transfer repair was attempted. The experimental route was removed, the provider `abort` call deleted this run's app-private input, and final read-only checks proved `InputDirectoryExists=false` and `SeedStatusExists=false`.
- The retained 83-item corpus and personal media were not modified. No 5k/20k MediaStore/index/recovery result is claimed.

Artifacts:

- `artifacts/device-runs/stress5k_actual_20260722_f731u/seed-command.txt`
- `artifacts/device-runs/stress5k_actual_20260722_f731u/transfer-progress.json`
- `artifacts/device-runs/stress5k_actual_20260722_f731u/seed-stream-command.txt`
- `artifacts/device-runs/stress5k_actual_20260722_f731u/provider-transport-tests.txt`
- `artifacts/device-runs/stress5k_actual_20260722_f731u/provider-transport-tests-final.txt`

Failures and limitations:

- The profiles are reproducible, licensed, checksum-verified, and now seed-contract compatible, but actual 5k/20k MediaStore discovery, database import, process-death recovery, full embedding indexing, ANR/OOM, and end-to-end latency remain unproven.
- The previously accepted 5k/20k native FP16 vector benchmark remains valid but must not be presented as proof of gallery-scale media ingestion.

Next phase:

- As a separate transport task, implement a bounded large-file debug import route that does not depend on thousands of concurrent adb processes or Android `content write` stdin. It must verify the host/device SHA-256 before extraction, retain canonical app-owned staging, and pass a small/5k contract before attempting 20k.

## Phase 2F - Canonical large-file adoption and incomplete-run cleanup (22 July 2026)

Status: **Large-file transfer/adoption passed at the real 5k archive size; complete 5k MediaStore seeding remains NOT PASSED after two bounded lifecycle cycles. Exact incomplete-run cleanup passed with zero remaining rows.**

Files changed:

- `android/app/src/debug/java/com/askphotos/android/TestSeedContentProvider.kt`
- `android/app/src/debug/java/com/askphotos/android/TestGallerySeederReceiver.kt`
- `android/app/src/androidTest/java/com/askphotos/android/TestSeedContentProviderTest.kt`
- `android/app/src/androidTest/java/com/askphotos/android/StressSeededGalleryTest.kt`
- `tools/device/seed_gallery.py`
- `tools/device/test_seed_gallery.py`
- `docs/implementation-status.md`
- `docs/connected-device-test-report.md`

Architecture decisions:

- The explicit `external-file --stage-only` debug transport asks the provider to create one canonical path under `getExternalFilesDir("test-seed-transfer")`. The host accepts only an absolute path ending in the exact package/run-ID suffix, with no traversal component. Final source refuses to launch the unaccepted long seeder from this route.
- `adb push` writes the archive only to that app-owned path. Before private adoption the provider validates the declared size and SHA-256. It then copies to canonical app-private staging while independently recomputing byte count and SHA-256, atomically renames the verified private archive, and deletes the external source.
- Provider abort/delete removes only the exact run's private input and external archive. The legacy resumable chunk transport remains the default; external-file staging must be requested explicitly with `--stage-only` because full 5k seeding is not yet accepted.
- Cleanup now supports a run that never produced `seed-result.json`. It records URIs proven by both exact run path and `owner_package_name`, deletes only those URIs, and checks both permitted paths for zero remaining rows.
- A stress-only instrumentation assertion was added to require the exact expected count under the app-owned image path. It is compiled but honestly NOT RUN because no 5k seed completed.
- An attempted WorkManager resume implementation was removed before final build because device extraction did not complete reliably. No unproven resume behavior is enabled in the committed source.

Host tests:

- `tools/device`: 14 PASS, including canonical provider-path parsing, other-package rejection, traversal rejection, bounded-memory hashing, and prior seed-result rules.
- Final ConsumerDebug build/install and ConsumerDebug Android-test assembly: PASS.

Connected provider gate:

- `TestSeedContentProviderTest`: PASS, 2 tests in 0.085 seconds on Samsung SM-F731U.
- Both the legacy exact chunk round-trip and external-file size/hash/private-copy/finalize/delete contract passed on the final installed source.

Real 5k attempt:

- Host archive: 110,337,694 bytes, 5,001 ZIP entries, SHA-256 `b11b63cd176388c83e5adf9cba3f63ae363b3c8af5535dce43571d9aaeed74d9`; `media/stress_02416.jpg` and the final `media/stress_04999.jpg` were confirmed present.
- External `adb push`, provider size/SHA verification, private copy verification, external deletion, and private adoption succeeded. The receiver observed the complete 110,337,694-byte private archive and began insertion.
- Cycle 1: the long `goAsync` broadcast was terminated near 60 seconds after creating 2,346/5,000 items. No final seed result existed.
- Cycle 2: a WorkManager/resume experiment reconstructed 2,347 exact owned rows but failed because mutable extracted staging was incomplete (`stress_02416.jpg` missing). The host archive itself remained complete and valid. Per the two-cycle rule no third seed repair was attempted, and the unproven worker/resume code was removed.

Cleanup and safety:

- Incomplete-run cleanup recovered 2,347 URIs using exact run paths plus `owner_package_name=com.askphotos.android`, wrote the orphan recovery record, deleted all 2,347, and reported `remainingCount=0`.
- No personal-gallery URI or retained 83-item core-corpus URI was deleted or modified.
- Final source was rebuilt/reinstalled after removal of the experiment; provider contracts passed again on that exact build.

Artifacts:

- `artifacts/device-runs/stress5k_external_20260722_f731u/seed-command.txt`
- `artifacts/device-runs/stress5k_external_20260722_f731u/seed-resume-command.txt`
- `artifacts/device-runs/stress5k_external_20260722_f731u/cleanup-command.txt`
- `artifacts/device-runs/stress5k_external_20260722_f731u/cleanup-result.json`
- `artifacts/device-runs/stress5k_external_20260722_f731u/provider-transport-tests-final-source.txt`

Failures and limitations:

- This proves large-file transport/adoption and failure cleanup, not complete 5k discovery, database indexing, recovery, embeddings, or query performance. `StressSeededGalleryTest` and every 20k device gate remain NOT RUN.
- Debug MediaStore insertion still needs a lifecycle designed for multi-minute work with immutable extraction generations and durable per-file checkpoints. A long broadcast and a worker sharing mutable staging are both rejected designs.

Next phase:

- Implement the stress seeder as a debug foreground service with an immutable verified extraction generation, durable manifest-index checkpoint, and idempotent filename/owner reconciliation. Run a small process-death test, then one clean 5k seed/visibility/cleanup cycle before attempting database import or 20k.

## Phase 2G - Foreground stress seeding, process-death recovery, and 5k MediaStore gate (22 July 2026)

Status: **PASSED for exact 5,000-item MediaStore seeding and forced-process-death recovery on Samsung SM-F731U. Database import/indexing and the 20k device gate remain NOT RUN.**

Files changed:

- `android/app/src/debug/AndroidManifest.xml`
- `android/app/src/debug/java/com/askphotos/android/TestGallerySeedEngine.kt`
- `android/app/src/debug/java/com/askphotos/android/TestGallerySeederService.kt`
- `android/app/src/debug/java/com/askphotos/android/TestGallerySeederReceiver.kt`
- `android/app/src/androidTest/java/com/askphotos/android/TestGallerySeederServiceTest.kt`
- `tools/device/seed_gallery.py`
- `tools/device/cleanup_gallery.py`
- `tools/device/collect_artifacts.py`
- `tools/device/test_seed_gallery.py`
- `tools/device/test_collect_artifacts.py`
- `docs/implementation-status.md`
- `docs/connected-device-test-report.md`

Architecture decisions:

- Long seed and cleanup operations now run in a debug-only `dataSync` foreground service. The release application has no exported test service because the declaration exists only in the debug manifest.
- ZIP extraction is built in `staging.building`, validated against the provider-verified transfer fingerprint, marked as a complete generation, and atomically promoted. A killed process cannot expose half-extracted media as the active staging generation.
- Every 25 manifest items writes an atomic checkpoint. Restart also queries only the two exact run paths with `OWNER_PACKAGE_NAME=com.askphotos.android`, then reconciles by relative path, filename, byte size, and `IS_PENDING`. Invalid or duplicate rows for that exact test filename are replaced; valid published rows are reused.
- Completion requires exactly one published row for every manifest item and no extra app-owned row in the run paths. The result is written before private staging deletion for crash safety, then finalized with `stagingRemoved=true`; the host now waits for that finalized state.
- Console output omits the potentially 5,000-entry URI list while the complete URI manifest remains in the run artifact for exact cleanup.
- Cleanup uses the same foreground-service lifecycle and still deletes the recorded URIs individually. Orphan recovery remains restricted to exact run path plus owner package.
- Artifact collection now restricts logcat to the target package PID. The previously collected unfiltered local log was removed and replaced; unrelated system logcat is no longer described as privacy-safe.

Commands and tests actually run:

- Host device harness tests: 19 PASS after adding privacy, duplicate seed-manifest, and cleanup-operation correlation regressions.
- `:app:compileConsumerDebugKotlin` and `:app:compileConsumerDebugAndroidTestKotlin`: PASS.
- ConsumerDebug build/install: PASS repeatedly through the required bundled build/install workflow; final installed source contains foreground seed and cleanup handling.
- Connected provider/service tests: 4 PASS initially (two provider transports plus two service tests). The final installed source reran `TestGallerySeederServiceTest`: 2 PASS in 0.024 seconds.
- Core foreground seed `fg_core_20260722_f731u`: 83/83 complete, zero reuse/retry, both Pictures/Documents paths, `stagingRemoved=true`. It is intentionally retained for functional testing.
- Fresh cleanup probe `fg_cleanup_probe_20260722`: 83/83 recorded URIs deleted through the foreground service, orphan count 0, remaining count 0. A final idempotent rerun carried a new 32-hex operation ID through service status, proving stale `COMPLETE` files are not accepted for a new cleanup call.

5k recovery evidence:

- Run: `fg_stress5k_recovery_20260722`.
- Archive: 110,337,694 bytes, 5,001 entries, SHA-256 `b11b63cd176388c83e5adf9cba3f63ae363b3c8af5535dce43571d9aaeed74d9`.
- The process was deliberately force-stopped after the atomic checkpoint reported 500/5,000 with `stress_00499.jpg` last.
- Restart found 509 valid published rows (nine commits occurred after the last status checkpoint), reused all 509, and completed exactly 5,000 without duplicate insertion.
- Independent `StressSeededGalleryTest`: PASS in 0.043 seconds for exactly 5,000 app-owned image rows.
- Final seed result: `createdCount=5000`, `reusedCount=509`, `recovered=true`, `stagingRemoved=true`.
- No ANR, Java/native crash, or OOM was observed. The host resume/wait command completed in 192.1 seconds; this is a harness/media-publication measurement, not an indexing or query benchmark.
- Diagnostics after the run showed app PSS 100,572 KB after launch. Thermal status was 3 with skin about 44.0 C, so the device was thermally constrained; no universal performance claim is made.

Cleanup and retained state:

- The first 5k cleanup invocation still used the older receiver build and its host command timed out at 60 seconds, but the on-device exact operation completed: requested 5,000, deleted 5,000, orphan count 0, remaining 0. The lifecycle was then moved into the foreground service and verified with the fresh 83-item probe.
- Independent post-cleanup checks passed: 0 stress-run image rows remain; the retained core run still has all 82 Pictures media rows (81 images and one video) plus its one PDF. Personal media was not queried for evaluation and no unrelated URI was modified.
- The connected Gradle test lifecycle had removed app-private models earlier. The signed SigLIP2 q8 pack was restored from `siglip2-base-p16-224-q8-core05.agretrieval`; host and device SHA-256 both matched `5966d528a7ddf73be52a299251e5c0071d878ba1e0fcc70d39fcf38ec6a8f010`.
- Real SigLIP2 acceptance passed again: exact tokenizer IDs; red 0.1327772 > 0.09285481; blue 0.14373945 > 0.0864488; dog 0.07813349 > 0.014190406; football 0.11567759 > -0.031866416. Encoder block 18,012 ms; PSS 309,568 KB to 462,933 KB; no crash/OOM.

Artifacts:

- `artifacts/device-runs/fg_core_20260722_f731u/seed-result.json`
- `artifacts/device-runs/fg_stress5k_recovery_20260722/staging-result.json`
- `artifacts/device-runs/fg_stress5k_recovery_20260722/seed-result.json`
- `artifacts/device-runs/fg_stress5k_recovery_20260722/diagnostics/`
- `artifacts/device-runs/fg_stress5k_recovery_20260722/siglip2-restored-acceptance.txt`
- `artifacts/device-runs/fg_cleanup_probe_20260722/cleanup-result.json`

Failures and limitations:

- This phase proves host-to-device transfer, exact MediaStore publication, foreground lifecycle recovery, exact visibility, and cleanup at 5k. It does not prove 5k Room import, complete embedding/OCR/event indexing, semantic query latency, or the 20k media gate.
- Thermal status 3 means future 20k/indexing work must exercise pause/backoff behavior rather than treating this device's current throughput as a normal-temperature baseline.
- E2B is not installed on this SM-F731U and is not claimed in this phase. E4B remains an explicit skip.

Next phase:

- Import/index the retained core and a bounded stress subset under the installed SigLIP2 pack, verify foreground indexing recovery and exact vector-row coverage, then run the 5k indexing/vector-query benchmark. Attempt 20k MediaStore seeding only after adding a thermal-aware pause gate and confirming sufficient time/storage.

## Phase 7B - Structured core evaluator and deterministic aggregation correction (22 July 2026)

Status: **Implemented and host-verified; full Q01-Q13 device execution NOT RUN because the reference SM-F966B disconnected before the suite started.**

Implementation:

- Added a structured Q01-Q13 instrumentation evaluator that writes per-query PASS/FAIL/SKIP JSON even when an individual case fails. It checks expected plan fields, follow-up scoping, top-K media, exact receipt/count results, required evidence types, evidence closure, and no-match behavior.
- Q07/Q08 intentionally report SKIP until an approved identity-embedding pack is installed and people indexing is explicitly enabled; the existing real-Gemma visual verifier remains a separate demonstrated gate.
- Corrected deterministic count planning so explicit year and generic media words do not become semantic constraints. The deterministic overlay now removes invented model semantics from metadata-only aggregations, semantic retrieval is bypassed for those plans, and exact deterministic COUNT/SUM/MIN_MAX results report `EXACT`.
- Host calibration over the complete 81-image core set selected a provisional core-only SigLIP minimum similarity of `0.05`; this retains valid Marina Bay/dog candidates while the stronger beach/football domains remain well separated. The signed installed pack version is `ba1f3b0-q8-core05`.

Verification actually run:

- Focused JVM tests for query compilation and deterministic plan overlay: PASS.
- ConsumerDebug Android-test assembly: PASS.
- Recalibrated SigLIP2 pack installation and direct real-device semantic acceptance on SM-F966B: PASS, 1 test in 12.561 s; exact tokenizer IDs and red/blue/dog/football comparisons passed.
- Q01-Q13 full evaluator: NOT RUN. `adb` reported the reference serial absent immediately before instrumentation; the connected SM-F731U was not substituted because it did not carry the reference device's installed E2B/SigLIP generations and indexed gallery state.

Remaining acceptance gaps:

- Reconnect the SM-F966B and run `CoreCorpusEvaluationAcceptanceTest` against `persistent_multidomain_20260722`, or explicitly install/import the same verified model packs and index state on another capable device.
- Install a reviewed face-embedding pack and obtain explicit people-search opt-in before converting Q07/Q08 from structured skips into identity acceptance.
