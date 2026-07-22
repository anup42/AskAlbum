# Connected physical-device test report

Report date: 22 July 2026

This report records tests that were actually executed on the connected physical device. It does not treat unrun model packs or performance profiles as passing.

## Reference device

- Manufacturer/model: Samsung SM-F966B
- Android: 16, API 36
- ABI/SoC: arm64-v8a, SM8750
- RAM class: approximately 11.4 GB
- Device serial: masked
- Distribution tested: `consumerDebug`
- Inference backend observed: LiteRT-LM 0.14.0, GPU

## Latest real-model acceptance

The app-managed Settings workflow downloaded and activated Gemma 4 E2B in app-private storage. The active artifact was independently checked with `run-as`:

- Size: 2,583,085,056 bytes
- SHA-256: `ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42`
- Source revision: `7fa1d78473894f7e736a21d920c3aa80f950c0db`
- Multimodal: yes

Direct instrumentation was used instead of Gradle's uninstalling connected-test lifecycle, preserving the app-private model pack.

| Suite | Result | Time | Evidence |
| --- | --- | ---: | --- |
| Real E2B planner, English/Hindi/Hinglish | PASS, 1 test | 59.486 s | Valid typed plans, GPU, no fixture fallback; one bounded repair per case |
| Real E2B visual verifier | PASS, 1 test | 11.764 s | One accepted candidate, three evidence conditions, no repair |
| Real E2B grounded answer/no-answer | PASS, 2 tests | 5.940 s | Two valid evidence references; no-answer bypassed Gemma |

After the run, thermal status remained 0 and the bounded log window contained no target-app fatal exception, ANR, OOM, or native fatal signal.

## Real SigLIP2 retrieval acceptance

The installed signed `siglip2-base-p16-224-q8` ONNX pack now passes tokenizer and cross-modal retrieval acceptance after correcting the Android SentencePiece implementation to the checkpoint's BPE contract.

| Gate | Result | Evidence |
| --- | --- | --- |
| Exact tokenizer IDs | PASS | Red and blue prompts match official SentencePiece IDs exactly |
| Synthetic color controls | PASS | Red 0.13278 > 0.09285; blue 0.14374 > 0.08645 |
| Retained CC0 dog query | PASS | Dog 0.07813 > football 0.01419 |
| Retained CC0 football query | PASS | Football 0.11568 > dog -0.03187 |
| Stability | PASS | One test in 9.99 s; no fatal exception, ANR, or OOM; thermal status 0 |

The E2B and SigLIP2 generation pointers remained unchanged after state-preserving installation. The persistent 83-item gallery also remains on the device.

## Other demonstrated connected gates

- ConsumerDebug build/install and deterministic Compose UI flow.
- Safe, run-specific MediaStore seed/import/cleanup with zero remaining test items.
- Resumable Room indexing and legacy schema migration.
- Event query plus two result-set-aware follow-ups.
- On-device video keyframe extraction, timestamp evidence, and playback-at-match.
- Consumer network component audit and offlineDemo manifest without Internet permission.

Detailed commands, run IDs, failures, cleanup counts, and artifact paths are recorded chronologically in `docs/implementation-status.md`.

## Current limitations and honest skips

- E4B: NOT RUN. It is optional; no verified E4B pack is installed.
- SigLIP2 q8 retrieval pack: INSTALLED and semantic smoke acceptance PASSED after the SentencePiece BPE repair. The run-scoped core Q01-Q13 evaluator now passes every implemented case on SM-F731U; multilingual retrieval and 5k/20k performance gates remain pending.
- Full release acceptance and universal performance claims: NOT CLAIMED.
- The planner needed its permitted repair call for all three language fixtures; first-pass structured output remains an optimization target.
- Stress-gallery state: exact 5k MediaStore seeding and forced-process recovery now PASS on SM-F731U. Full 5k Room import/indexing/query performance and every 20k connected gate remain NOT RUN; existing native FP16 measurements prove vector scanning only.

## Persistent multi-domain test gallery

Run `persistent_multidomain_20260722` is intentionally retained on the reference device for continued acceptance work.

- Core corpus: 83 items (81 images, one PDF, one video).
- Verified licensing: 19 records; public domain, CC0/CC0 1.0, or approved CC BY-SA 4.0 sources plus locally generated CC0 fixtures.
- Added real-image domains: domestic dog/pet and children playing football outdoors.
- Existing domains retained: Singapore/Goa travel, beaches/sunsets, architecture, street text, food, flowers, duplicates, synthetic people/clothing, receipts/OCR, Wi-Fi, boarding pass, hotel, multilingual menus, calendar, PDF, and video timeline.
- Safe seed result: 83 exact created URIs, zero transfer retries, staging removed.
- App import result: 83 requested and 83 imported.
- Device paths: `Pictures/AgenticGalleryTest/persistent_multidomain_20260722/` and `Documents/AgenticGalleryTest/persistent_multidomain_20260722/`.
- Cleanup was NOT RUN so the dataset remains available. Future cleanup must use the run-scoped cleanup harness and URI manifest; no broad shared-storage deletion is permitted.

Two direct Android 16 shell MediaStore queries failed on projection/selection parsing and were not retried further. The structured seeder/importer results are the count evidence for this run. The Windows diagnostics helper also emitted a non-decodable screenshot, so this slice does not claim screenshot-based visual QA.

## Artifacts

- `artifacts/device-runs/phase6_e2b_restore_20260722/`
- `artifacts/device-runs/phase4_siglip2_q8_20260722/`
- `artifacts/device-runs/phase8_video_direct2_20260722/`
- `artifacts/device-runs/phase8_events_exif_20260722/`
- `artifacts/device-runs/phase9_network_privacy_20260722/`
- `artifacts/device-runs/persistent_multidomain_20260722/`
- `artifacts/device-runs/phase4_siglip2_tokenizer_20260722/`
- `artifacts/device-runs/persistent_multidomain_20260722_f731u/`

Generated artifacts may be excluded from Git because they include bulky logs, screenshots, and device-run outputs. The repository-tracked status report preserves their paths and summarized results.

## Secondary-device persistent corpus

The same verified 83-item core corpus is retained on a connected Samsung SM-F731U (Android 16/API 36, SM8550) under run ID `persistent_multidomain_20260722_f731u`.

- License/checksum validation: PASS, 83 items and 19 license records.
- A timeout/retry initially produced 166 app-owned rows. Run-scoped recovery deleted 83 recorded URIs plus 83 reconstructed orphan URIs only after recording proof from the exact test path and owner package; zero remained. The clean reseed then created exactly 83 items, and an immediate repeat returned the existing manifest without transfer or insertion. Final audit: 83 app-owned rows and zero duplicate-suffixed names.
- MediaStore visibility instrumentation: PASS, 1 test in 0.032 s.
- EXIF/event/follow-up instrumentation after repair: PASS, one test in 33.865 seconds. A protected GPS read can no longer discard a valid EXIF date.
- Real signed q8 SigLIP2 without E2B preinstallation: PASS, one test in 19.117 seconds. Retrieval installation left Gemma selection/installation state unchanged.
- Core Q01-Q13 with real SigLIP/OCR/events and deterministic fallback planner: PASS, 11 implemented cases passed, 0 failed, and Q07/Q08 were explicit people-index skips. The executor now corroborates scoped semantic refinements with lexical/event channels when available: Q02 narrowed from 53 hits to 5 Marina Bay results, while Q03 returned an exact empty result with no fabricated evidence. One instrumentation test completed in 10.833 seconds; bounded post-run logcat contained no fatal exception, ANR, OOM, or SQLite exception. The evaluator remained constrained to all 83 recorded run IDs, so personal media did not contribute to metrics.
- Cleanup/reseed touched only run-scoped app-owned test URIs. The final 83-item corpus is intentionally retained; no personal-gallery row was modified.
- This is a dataset availability gate only. No E2B/SigLIP result from the SM-F966B is attributed to this second device.

Latest artifacts:

- `artifacts/device-runs/persistent_multidomain_20260722_f731u/core-q01-q13-corroborated-followup.txt`
- `artifacts/device-runs/persistent_multidomain_20260722_f731u/core-q01-q13-corroborated-followup.json`
- `artifacts/device-runs/persistent_multidomain_20260722_f731u/core-q01-q13-corroborated-followup-errors.txt`

## Stress-profile generation and device-transfer audit

- Host `stress-5k`: 5,000 verified items, 107,139,872 media bytes, mapping SHA-256 `96598e0c4b54844ade1f4a1811f360412f268219d4a1e904171f88c61b2e2234`.
- Host `stress-20k`: 20,000 verified items, 428,313,795 media bytes, mapping SHA-256 `d5e6fb280dd639ae25d647ff18f333a78f6f6a3d5a5cb154631ddcc7e96fb04e`.
- Both profiles passed manifest/media equality, the 19-record license whitelist, SHA-256 checksums, EXIF dates/offsets, and declared GPS checks.
- Device cycle 1 stalled at 2,200/8,980 validated chunks; device cycle 2 was rejected at exact adoption because Android supplied 2,497,180/110,337,694 bytes. Neither cycle reached extraction, the seed broadcast, MediaStore insertion, or app database import.
- The incomplete app-private input was aborted and verified absent. The connected app was rebuilt/reinstalled from the final source after the experimental transport was removed. The retained 83-item core corpus and personal gallery were not modified.
- Stress transfer artifacts: `artifacts/device-runs/stress5k_actual_20260722_f731u/`.

## Large-file transport follow-up

- The 110,337,694-byte 5k archive successfully moved through an app-owned external-files path, exact provider size/SHA-256 validation, an independently hashed private copy, atomic private adoption, and external-source deletion.
- Final provider contract: PASS, two tests in 0.085 seconds for chunked and external-file routes.
- At this intermediate checkpoint, complete 5k MediaStore seeding had not passed: the long broadcast stopped after 2,346 items and the discarded WorkManager experiment encountered incomplete mutable extraction. The later foreground-service section below supersedes this checkpoint.
- Incomplete cleanup: PASS. Exactly 2,347 URIs proven by run path plus owner package were recovered and deleted; `remainingCount=0`.
- The connected app was rebuilt/reinstalled from the final source and provider tests passed again. The retained 83-item core gallery and personal media were unchanged.
- Artifacts: `artifacts/device-runs/stress5k_external_20260722_f731u/`.

## Foreground 5k recovery acceptance

- Device: Samsung SM-F731U, Android 16/API 36, arm64-v8a, SM8550 (serial masked in artifacts).
- ConsumerDebug built and installed from the final source. The test service exists only in the debug manifest and runs as a `dataSync` foreground service.
- Retained functional corpus: `fg_core_20260722_f731u`, 83 items (81 images, one video, and one PDF), exact seed result, staging removed. Cleanup was intentionally not run.
- Stress run: `fg_stress5k_recovery_20260722`, 110,337,694-byte verified ZIP, 5,000 images.
- Forced process death occurred at the durable 500-item checkpoint. Restart reconciled 509 already-published rows and completed exactly 5,000 with no duplicates; `stagingRemoved=true`.
- Independent visibility instrumentation: PASS, exactly 5,000 run-scoped app-owned images in 0.043 seconds.
- Stress cleanup: 5,000 recorded URIs deleted, 0 orphans, 0 remaining. Independent post-cleanup instrumentation reported 0 stress images while all 82 retained core Pictures media remained.
- Foreground cleanup regression: a fresh 83-item probe deleted 83/83 recorded URIs and left 0 rows. A second operation-correlated call returned its new operation ID and still reported 0 remaining, rather than accepting stale status.
- No target ANR, crash, native fatal signal, or OOM was observed. Resume/completion took 192.1 seconds. After launch, app PSS was 100,572 KB. Thermal status was 3 (skin approximately 44.0 C), so this is not a normal-temperature performance baseline.
- The signed SigLIP2 q8 pack was restored after the earlier uninstalling test lifecycle. Host/device SHA-256 matched `5966d528a7ddf73be52a299251e5c0071d878ba1e0fcc70d39fcf38ec6a8f010`; tokenizer and red/blue/dog/football semantic checks passed with an 18,012 ms encoder block and no OOM.
- Package-PID-scoped diagnostics replaced an initially unfiltered local log artifact. Generated logs are not committed.
- Remaining gap: 5k Room import/full indexing/query performance and every 20k connected-device gate are NOT RUN.
- Artifacts: `artifacts/device-runs/fg_stress5k_recovery_20260722/`, `artifacts/device-runs/fg_cleanup_probe_20260722/`, and `artifacts/device-runs/fg_core_20260722_f731u/`.

## Retained samples and 5k Room/recovery acceptance

- Two test datasets are intentionally retained on Samsung SM-F731U: the 83-item `fg_core_20260722_f731u` multi-domain gallery and the 5,000-image `fg_index5k_20260722` stress gallery. They live only under their run-specific `Pictures/AgenticGalleryTest/...` and `Documents/AgenticGalleryTest/...` paths.
- The core gallery contains openly licensed sources and locally generated CC0 fixtures spanning travel, places, beaches/sunsets, food, pets, sport, architecture, duplicate bursts, people/clothing, receipts, Wi-Fi, boarding pass, hotel, multilingual menus, PDF, and video. Its attribution/checksum artifacts remain available on the host.
- The 5k MediaStore seed completed exactly 5,000/5,000 with private staging removed. Foreground Room import then completed 5,000/5,000 in 609.5 seconds.
- SQLite coverage reported 5,000 unique media rows and all 45,000 expected stage rows. Discovery/metadata are complete; faces and video keyframes are skipped by policy/media type; heavy thumbnail, embedding, OCR, events, and enrichment stages remain pending.
- Forced process recovery passed: three persisted RUNNING stages were prepared, the app was force-stopped, and recovery returned zero RUNNING stages and zero INDEXING media rows while preserving all 5,000 rows and 45,000 stages.
- Thermal status was 3 (skin approximately 43.8 C). New background admission correctly deferred heavy indexing and the report recorded `thermalAllowed=false`. Final app PSS was 75,376 KB; no target ANR/OOM/crash was observed.
- The installed signed q8 SigLIP2 pack remained app-private and recognized. No vector is claimed for this 5k run because background inference was thermally deferred. E2B is not installed on this device.
- Cleanup was intentionally NOT RUN at the user's request. Future cleanup must use the stored URI manifests and exact run-scoped harness; no broad gallery deletion is permitted.
- Artifacts: `artifacts/device-runs/fg_core_20260722_f731u/` and `artifacts/device-runs/fg_index5k_20260722/`.

## E2B production download and installation on SM-F731U

- The device has approximately 7.3 GB RAM and selected E2B as its supported/recommended tier. E4B reported unsupported because it requires more physical RAM; no E4B download was attempted.
- The app's production Settings-equivalent downloader fetched the pinned public LiteRT Community artifact from revision `7fa1d78473894f7e736a21d920c3aa80f950c0db`.
- Download completed 2,583,085,056/2,583,085,056 bytes in 420.6 seconds. Installation reached `INSTALLED` only after the pinned SHA-256 `ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42` passed and an app-private generation was activated.
- The final ConsumerDebug build and Android-test APK were installed without clearing app data. A repeated E2B download request returned `INSTALLED` immediately and did not transfer the model again.
- `GemmaDownloadHarnessTest`: PASS twice, most recently 1 test in 0.059 seconds. Host device tests: 23 PASS. ConsumerDebug app/test assembly: PASS.
- The initial debug automation attempt correctly exposed Android 16's ban on starting a foreground WorkManager service from a background receiver before any bytes transferred. The final debug harness uses a five-second visible activity trampoline; production Settings already starts from a visible activity. These debug components do not exist in release builds.
- The device cooled to thermal status 1 and real E2B planner acceptance was run. English and Hindi used GPU E2B with valid plans and no fallback after two generation calls each (24.270 s and 35.910 s wall on the final run). Hinglish still failed the no-fallback assertion after 76.797 s because Gemma emitted unsupported `SemanticSubject.FAMILY`; the strict validator safely selected deterministic fallback.
- Two repair cycles were applied and tested: `verification` is now explicitly constrained to one scalar enum, and deterministic exact date/media/album slots replace conflicting model guesses instead of duplicating filters. Focused JVM tests pass. No unknown enum was coerced and the acceptance assertion was not weakened; the multilingual E2B planner suite remains FAILED.
- Thermal returned to MODERATE (2), skin approximately 42.0 C, after the final planner attempt. Grounded-answer/visual-verifier tests and retained 5k heavy indexing were not run. The installed E2B pack, SigLIP2 q8 pack, 83-item core corpus, and 5k stress corpus remain available for continuation.
- Artifacts: `artifacts/device-runs/model-packs/gemma-e2b-download.json`, `gemma-e2b-status.json`, and `gemma-e4b-status.json`.

## Multilingual E2B planner resolution on SM-F731U

- The earlier Hinglish `SemanticSubject.FAMILY` failure is resolved without accepting or coercing unknown enum values. Strict errors now name the field and allowed set; ordinary category/place searches use `terms`/`place` rather than unnecessary semantic-clause objects.
- Final `RealGemmaPlannerAcceptanceTest`: PASS, 1 test in 45.364 seconds on the installed pinned E2B pack.
- English: real GPU E2B, one call, no repair/fallback, valid typed plan, 11.226 seconds wall.
- Hindi: real GPU E2B, one call, no repair/fallback, valid typed plan, 12.434 seconds wall.
- Hinglish: real GPU E2B, one call, no repair/fallback, valid typed plan, 21.598 seconds wall.
- Exact previous-year/2024 time ranges still came from Kotlin and were asserted. Required English/Hindi/Hinglish retrieval concepts were present, and every plan passed the production validator.
- Peak observed post-close PSS across cases was 255,157 KB. Thermal status remained 1; skin rose from approximately 39.6 C to 41.1 C. No ANR, OOM, crash, or fallback was observed.
- Grounded-answer and one-image visual-verifier acceptance remain separate outstanding gates; no claim is made for them here.

## Grounded answer and targeted visual verification on SM-F731U

- `RealGemmaGroundedAnswerAcceptanceTest`: PASS, 2 tests in 10.831 seconds. The evidence-backed case used GPU E2B once, produced two claims citing only the two supplied evidence IDs, preserved deterministic exactness/coverage, and required no repair or fallback. The empty-evidence case bypassed Gemma and emitted no claims or evidence.
- Grounded-answer timing: load 5.693 s, generation 4.700 s, close 0.372 s, wall 10.774 s. PSS rose from 76,905 KB before inference to 237,793 KB after close.
- `RealGemmaVisualVerifierAcceptanceTest`: PASS, 1 test in 15.638 seconds. One locally generated CC0 synthetic image was checked against three hard relationship/negation conditions in one GPU E2B call; all three passed, the candidate was accepted, exactly three provenance-linked evidence records were created, and failures were empty.
- Visual timing: load 5.672 s, generation 9.288 s, close 0.548 s, wall 15.551 s. PSS rose from 84,748 KB to 269,504 KB after close.
- Thermal status remained 1; post-run skin temperature was approximately 39.2 C. Compact diagnostics at `android-diagnostics/20260722_152435/` contained zero target fatal exceptions, ANRs, OOMs, or crash markers and recorded normal instrumentation exit.
- These gates use bounded synthetic/local evidence and do not claim exhaustive real-gallery visual accuracy. Retained-5k indexing, people identity, and 20k connected acceptance remain outstanding.

## Retained 5k resume admission audit on SM-F731U

- The retained run remained exactly scoped to 5,000 app-seeded images. It advanced to 216 analyzed/ready media and 24 real signed-q8 SigLIP2 vectors; no unrelated media was queried or modified.
- Two bounded WorkManager foreground repair cycles did not sustain the initial index. Final coverage remained 216 ready, 4,784 pending, 24 embedding-complete, and 4,976 embedding-pending, with no failed or running database rows.
- Thermal was safe throughout the final attempt: status 0, AP approximately 35.8-38.0 C, skin approximately 36.1-37.3 C. Idle PSS was 79,416 KB and RSS 155,400 KB.
- Diagnostics proved the immediate failure boundary: WorkManager attempted to start `SystemForegroundService` after the debug resume broadcast had moved to the background, and Android rejected both workers with `ForegroundServiceStartNotAllowedException`. No app fatal exception, ANR, OOM, or process death occurred, but the 5k completion gate is explicitly FAILED/INCOMPLETE.
- The retained scheduling changes are correlated resume results, one unique continuation chain, cancellation of stale tagged work, and device-tier SigLIP batches. The failed foreground-WorkManager experiment was reverted before commit. The next required change is direct foreground-service ownership of reusable indexing processors; another background scheduler retry is not sufficient.
- Local diagnostic bundle: `android-diagnostics/20260722_154257/`. Retained sample media, E2B, and SigLIP2 remain installed for continuation.
