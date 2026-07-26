# Agentic Gallery overall architecture

Create a clean, publication-quality software architecture diagram for a native Android application. The diagram must communicate that all media analysis, search, and generative inference occur locally on the Android device. Use exact labels and do not invent services.

Organize the diagram in five horizontal or vertical layers inside one clearly marked boundary: "Android device".

1. Entry and presentation layer
   - MediaStore
   - Android Photo Picker
   - Storage Access Framework
   - Jetpack Compose UI
   - UI destinations: Library, Ask, People, Index Manager, Settings, Evidence viewer

2. Application state and orchestration
   - GalleryViewModel
   - GalleryRepository
   - AppServices dependency graph
   - WorkManager schedulers
   - Progressive query state: Understanding, Initial results, Verification, Grounded answer

3. On-device processing engines
   - Gemma 4 via LiteRT-LM: typed query planning, visual verification, grounded answer composition, semantic memory
   - SigLIP2 via LiteRT: image/text embeddings
   - PP-OCRv5 Mobile or ML Kit: OCR
   - SFace or ML Kit: opt-in face processing
   - Kotlin-owned deterministic filters, aggregation, validation, and safety policies
   - SerializedInferenceResourceManager coordinates high-memory model use

4. Background pipelines
   - GalleryIndexWorker
   - EmbeddingIndexWorker
   - PeopleIndexWorker
   - SemanticEnrichmentWorker
   - Each pipeline is resumable, bounded, and constrained by battery, storage, and thermal admission

5. Private storage
   - Room / SQLite / FTS
   - Media metadata, OCR blocks and entities, people and events, evidence, result sets, stage checkpoints
   - Memory-mapped FP16 semantic vector index
   - Face vector store
   - App-private previews and video keyframes
   - Verified model-pack generations

Show the important arrows:
- Media sources flow to import/discovery, then background indexing, then private storage.
- The Compose UI calls GalleryViewModel, which calls GalleryRepository and services.
- Search reads private structured data and vectors, optionally invokes local Gemma, and streams evidence-backed results back to the UI.
- Workers checkpoint results to Room and vector stores.
- Model packs are consumed only by local engines.

At the edge of the device boundary, show one small, narrowly scoped exception: "Consumer variant: user-started verified model downloads". It may cross the boundary only into model-pack verification and atomic activation. Add a lock/shield note: "No application server or cloud inference".

Visual style:
- White or very light background, restrained navy, teal, violet, and warm amber accents.
- Rounded rectangles, thin crisp arrows, ample whitespace, consistent typography.
- Use small icons sparingly for media, database, model, worker, shield, and UI.
- Avoid a decorative title inside the figure; the README supplies the caption.
- Every label must remain legible when displayed at roughly 1100 pixels wide.
