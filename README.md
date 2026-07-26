# Agentic Gallery

Agentic Gallery is a native Android application for private, fully on-device
photo, video, screenshot, PDF, OCR, people, place, event, and metadata search.
It combines deterministic Kotlin execution with local SigLIP2 retrieval and
optional Gemma inference. There is no application server and no cloud inference
path.

The simplest mental model is:

1. Android grants the app access to selected media.
2. resumable workers build private metadata, OCR, event, people, and vector
   indexes;
3. a typed query plan fans out across local retrieval channels;
4. Kotlin applies hard constraints, arithmetic, safety, and evidence checks;
5. optional local Gemma planning, verification, and answer composition improve
   the experience without owning data access or execution.

## Overall architecture

![Agentic Gallery overall on-device architecture](docs/architecture/agentic-gallery-overall-architecture.png)

*Everything inside the dashed boundary runs on the Android device. The consumer
variant can perform an explicit, user-started model download, but the downloaded
model is verified, activated in app-private storage, and used only for local
inference.*

The UI is built with Jetpack Compose and exposes Library, Ask, People, Index
Manager, Settings, and evidence views. `GalleryViewModel` owns screen and
progress state. `GalleryRepository` orchestrates the database, retrieval
channels, and optional model-backed services supplied by the application-level
`AppServices` dependency graph.

Room/SQLite/FTS stores structured gallery memory and stage checkpoints.
Semantic and face vectors live in separate app-private stores. WorkManager
drives bounded background pipelines, while
`SerializedInferenceResourceManager` and the shared `GemmaSessionManager`
prevent high-memory model workloads from competing with each other.

## How media becomes searchable

![Agentic Gallery indexing and semantic-memory pipeline](docs/architecture/agentic-gallery-indexing-pipeline.png)

*Indexing is checkpointed, resumable, and independent of network access. Model
dependent and privacy-sensitive branches are explicitly optional.*

The indexing path is divided into coordinated stages:

1. **Discovery and import.** `MediaImporter` and `MediaReconciler` ingest granted
   MediaStore rows, Photo Picker selections, and Storage Access Framework
   documents. Discovery records and stage state are written before expensive
   analysis starts.
2. **Base enrichment.** `GalleryIndexWorker` processes bounded batches. Images
   use bounded thumbnail decoding, videos produce timestamped private
   keyframes, and PDFs produce private page previews. Local feature extraction,
   ML Kit labels, gated OCR, normalized OCR regions, and structured document
   facts are persisted in `GalleryDatabase`; `EventCompiler` rebuilds episodic
   events.
3. **Semantic retrieval.** `EmbeddingIndexWorker` uses the verified SigLIP2
   pack through LiteRT to embed images, PDF pages, and video keyframes into the
   memory-mapped FP16 `SemanticVectorStore`.
4. **People indexing.** `PeopleIndexWorker` is disabled until explicit consent.
   A verified SFace pack enables local identity embeddings and conservative
   clustering. Users review names, aliases, merges, splits, exclusions, and
   representatives; original media is never rewritten.
5. **Adaptive semantic memory.** Instead of captioning every item,
   `SemanticEnrichmentCoordinator` selects bounded representatives from events,
   visual groups, documents, frequently retrieved media, and outliers.
   `SemanticEnrichmentWorker` writes typed, allowlisted facts with model and
   prompt provenance. Authentication-protected OCR is excluded.

Battery, storage, thermal, charging, and idle admission rules keep background
work bounded. Interrupted work returns to a resumable state instead of
requiring the gallery to be reset.

## How a question becomes an answer

![Agentic Gallery progressive grounded-query pipeline](docs/architecture/agentic-gallery-query-pipeline.png)

*Initial result cards can appear before optional visual verification and answer
composition finish. Every channel reports whether it succeeded, was partial,
was unavailable, failed, or was not required.*

The Ask flow is deliberately split between model suggestions and app-owned
execution:

1. `LiteRtLmQueryPlanner` uses an active verified Gemma pack for one constrained
   planning call and at most one repair call. If Gemma is unavailable,
   `QueryCompiler` provides a deterministic fallback.
2. `GalleryQueryPlanValidator` accepts only the bounded typed
   `GalleryQueryPlan` contract. Contextual follow-ups use an app-created
   `PlanPatch`; the model never provides result-set IDs or media IDs.
3. Kotlin resolves reviewed people and applies media, time, album, place,
   merchant, OCR, people, follow-up, negative, and hard eligibility rules.
4. Lexical FTS, semantic vectors, compiled events, reviewed identities, and
   structured OCR execute as local retrieval channels. `HybridRankFusion`,
   duplicate collapse, and event diversity produce the initial ranking.
5. Fine-grained, relational, comparative, or negative requests can invoke
   bounded `LiteRtGemmaVisualVerifier` evaluation over candidate images or
   matched private video keyframes. Kotlin still computes final hard-condition
   acceptance.
6. Counts, lists, sums, min/max, comparisons, timelines, and document facts are
   computed deterministically. Optional `LiteRtGemmaGroundedAnswerComposer`
   receives bounded evidence, and `GroundedClaimValidator` rejects unsupported
   claims or unknown evidence IDs.
7. The final `SearchOutcome` records hits, exactness, warnings, evidence,
   channel coverage, timing, and conversation state. The UI exposes the same
   provenance through **Why this answer?**

Model output cannot express SQL, filesystem paths, content URIs, authorization
rules, or arbitrary execution.

## Privacy and verified model lifecycle

![Agentic Gallery privacy and verified model lifecycle](docs/architecture/agentic-gallery-privacy-model-lifecycle.png)

*Internet permission, when present in the consumer variant, supports explicit
model download—not inference. Runtime media analysis remains on device.*

- Embedded or downloaded model packs are checked against immutable catalog
  metadata, expected size, SHA-256, and signatures where applicable.
- Packs are staged privately, activated as atomic generations, and rolled back
  after load failure. Local engines read only active verified generations.
- People indexing is off by default. Identity queries require a verified face
  embedding engine and a user-reviewed cluster; hidden and unreviewed clusters
  never resolve as identities.
- Sensitive OCR evidence is classified and requires biometric or
  device-credential authentication. Protected OCR is not sent to background
  semantic enrichment.
- Cached semantic facts are typed, allowlisted, and provenance-tagged. Secret
  values are rejected.
- Removing a label, hiding a cluster, or purging the people index does not
  modify MediaStore originals.
- Memory-pressure and idle eviction release the shared Gemma engine without
  deleting indexed data or model generations.

## Component map

| Area | Primary source | Responsibility |
|---|---|---|
| Compose shell and destinations | [`MainActivity.kt`](android/app/src/main/java/com/samsung/agenticgallery/MainActivity.kt) | Library, Ask, People, Settings, indexing, result, viewer, and evidence UI |
| UI state and operations | [`GalleryViewModel.kt`](android/app/src/main/java/com/samsung/agenticgallery/GalleryViewModel.kt) | Navigation, imports, indexing controls, people review, model state, and progressive query state |
| Dependency graph | [`AgenticGalleryApplication.kt`](android/app/src/main/java/com/samsung/agenticgallery/AgenticGalleryApplication.kt) | Application-scoped database, model managers, engines, vector stores, and shared sessions |
| Search orchestration | [`GalleryRepository.kt`](android/app/src/main/java/com/samsung/agenticgallery/GalleryRepository.kt) | Scope resolution, parallel retrieval, fusion, verification, answers, evidence, and result-set persistence |
| Contracts and plans | [`GalleryModels.kt`](android/app/src/main/java/com/samsung/agenticgallery/GalleryModels.kt) | Typed query, filter, evidence, channel-report, result, and indexing contracts |
| Structured storage | [`GalleryDatabase.kt`](android/app/src/main/java/com/samsung/agenticgallery/GalleryDatabase.kt) and [`GalleryRoomDatabase.kt`](android/app/src/main/java/com/samsung/agenticgallery/GalleryRoomDatabase.kt) | Room/SQLite/FTS data, migrations, checkpoints, people, events, OCR, semantic facts, and conversations |
| Base indexing | [`GalleryIndexWorker.kt`](android/app/src/main/java/com/samsung/agenticgallery/GalleryIndexWorker.kt) | Resumable media analysis batches and event rebuild scheduling |
| Semantic vectors | [`EmbeddingIndexWorker.kt`](android/app/src/main/java/com/samsung/agenticgallery/EmbeddingIndexWorker.kt) | SigLIP2 media/keyframe embedding and vector-store reconciliation |
| People index | [`PeopleIndexWorker.kt`](android/app/src/main/java/com/samsung/agenticgallery/PeopleIndexWorker.kt) | Opt-in face detection, embeddings, conservative clustering, and checkpoints |
| Semantic memory | [`SemanticEnrichmentWorker.kt`](android/app/src/main/java/com/samsung/agenticgallery/SemanticEnrichmentWorker.kt) | Bounded representative enrichment with protected-OCR exclusion |
| Shared Gemma runtime | [`GemmaSessionManager.kt`](android/app/src/main/java/com/samsung/agenticgallery/GemmaSessionManager.kt) | Serialized LiteRT-LM GPU/CPU initialization, reuse, cancellation, and eviction |
| Model boundaries | [`ModelPackManager.kt`](android/app/src/main/java/com/samsung/agenticgallery/ModelPackManager.kt), [`RetrievalModelPack.kt`](android/app/src/main/java/com/samsung/agenticgallery/RetrievalModelPack.kt), [`OcrModelPack.kt`](android/app/src/main/java/com/samsung/agenticgallery/OcrModelPack.kt), and [`FaceModelPack.kt`](android/app/src/main/java/com/samsung/agenticgallery/FaceModelPack.kt) | Verified download/import, app-private generations, activation, capability reporting, and rollback |

## Repository layout

- `android/`: Kotlin, Jetpack Compose, Room, WorkManager, JNI, unit tests, and
  connected-device tests.
- `demo-assets/`: consented demo media embedded by Android builds.
- `tools/device/`: Android seeding, profiling, model installation, and
  connected-device acceptance tooling.
- `tools/model-conversion/`: signed/checksummed local model-pack preparation.
- `tools/sample_gallery/`: synthetic and stress-gallery generation.
- `scripts/verify_demo_library.py`: demo-media verification.
- `docs/`: implementation evidence, requirements, testing, licensing,
  architecture images, and versioning.
- `s-Gallery/`: Samsung Gallery and Agentic Gallery visual references.

## Android variants

- `ciDebug`: model-independent fixture engines for source and unit-test builds.
- `offlineDemoDebug`: embedded retrieval/face assets and no Internet permission.
- `consumerDebug`: user-started model downloads with all inference remaining
  on device.

## Build and test

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

See [`android/README.md`](android/README.md) for Android-specific operation,
[`docs/ANDROID_REQUIREMENTS_AUDIT.md`](docs/ANDROID_REQUIREMENTS_AUDIT.md) for
the implementation matrix, and
[`docs/implementation-status.md`](docs/implementation-status.md) for
evidence-backed test and device status.
