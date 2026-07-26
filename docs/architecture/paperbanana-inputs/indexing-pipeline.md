# Agentic Gallery indexing and semantic-memory pipeline

Create a detailed but readable pipeline diagram for the on-device indexing lifecycle in a native Android gallery. Use exact component labels and show resumability and optional branches clearly.

Left-to-right main flow:

1. Media discovery and import
   - MediaStore full or partial access
   - Android Photo Picker
   - Storage Access Framework
   - MediaImporter and MediaReconciler
   - Room discovery rows and per-stage checkpoints

2. Work admission and scheduling
   - IndexScheduler / WorkManager
   - GalleryIndexWorker
   - Constraints: no network required, battery not low, storage not low, thermal admission
   - Interrupted RUNNING stages recover to resumable work

3. Per-media preparation with three branches
   - Image: bounded thumbnail decode
   - Video: VideoKeyframeExtractor creates private keyframes with timestamps
   - PDF: PdfPageRenderer creates private page previews
   - All branches converge into frame/page analysis

4. Deterministic local enrichment
   - VisualFeatureExtractor: perceptual hash, blur, exposure, quality
   - ML Kit image labels
   - OcrLikelihoodGate
   - OCR engine registry: verified PP-OCRv5 Mobile, fallback ML Kit Latin
   - OCR blocks with normalized regions
   - DocumentFactExtractor: amounts, receipt total, date, merchant, order and travel facts
   - GalleryDatabase persists metadata, stages, labels, OCR, facts, and previews
   - EventCompiler rebuilds episodic events

Show three coordinated side pipelines after the base index:

A. Semantic retrieval index
   - EmbeddingIndexScheduler -> EmbeddingIndexWorker
   - LiteRtImageTextEmbeddingEngine using embedded verified SigLIP2 pack
   - Images, first PDF pages, and video keyframes
   - SemanticVectorStore / memory-mapped FP16 vectors

B. People index, explicitly labeled "opt-in"
   - PeopleIndexScheduler -> PeopleIndexWorker
   - ML Kit face boxes when identity pack is absent
   - Verified SFace embeddings when installed
   - FaceVectorStore and conservative clustering
   - User review: name, aliases, merge, split, exclude, representative
   - Original media is never modified

C. Adaptive semantic memory
   - SemanticEnrichmentCoordinator chooses bounded representatives: events, visual groups, ambiguous documents, frequent results, outliers
   - SemanticEnrichmentWorker: charging and idle for background runs; bounded user-started runs
   - Shared GemmaSessionManager
   - Typed allowlisted semantic facts with model and prompt provenance
   - Authentication-protected OCR never enters background Gemma

End state on the right:
- Search-ready Room / FTS data
- Semantic and face vector stores
- Evidence and provenance
- Coverage and exactness reporting

Visual style:
- Publication-quality systems pipeline, 16:9 layout.
- White background with blue main flow, teal successful persistence, violet model inference, amber opt-in/privacy gates.
- Use solid arrows for required flow and dashed arrows for optional/model-dependent branches.
- Include a small legend for required, optional, checkpoint, and privacy gate.
- Do not place a decorative title inside the figure.
- Keep text concise and spell all exact component names correctly.
