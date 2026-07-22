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
