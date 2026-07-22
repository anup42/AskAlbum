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
- SigLIP2 q8 retrieval pack: INSTALLED and semantic smoke acceptance PASSED after the SentencePiece BPE repair. Full core Recall@K, no-match threshold calibration, multilingual retrieval, and 5k/20k performance gates remain pending.
- Full release acceptance and universal performance claims: NOT CLAIMED.
- The planner needed its permitted repair call for all three language fixtures; first-pass structured output remains an optimization target.

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

The same verified 83-item core corpus was also seeded to a connected Samsung SM-F731U (Android 16/API 36, SM8550) under run ID `persistent_multidomain_20260722_f731u`.

- License/checksum validation: PASS, 83 items and 19 license records.
- Safe MediaStore insertion: COMPLETE, 83 recorded URIs (81 images, one PDF, one video), staging removed, zero provider retries.
- MediaStore visibility instrumentation: PASS, 1 test in 0.032 s.
- Cleanup: NOT RUN; the corpus is intentionally retained in its run-specific test paths.
- This is a dataset availability gate only. No E2B/SigLIP result from the SM-F966B is attributed to this second device.
