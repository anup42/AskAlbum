# AskAlbum implementation plan

## Product boundary

AskAlbum is one private GPU server with an authenticated browser client.
Originals, derivatives, metadata, embeddings, prompts and search traces remain
on that server. All runtime inference uses configured local model directories;
no hosted inference fallback exists.

```text
Browser
  |
  v
Caddy (only published port)
  |-- React application
  `-- FastAPI
       |-- PostgreSQL + pgvector + PostGIS
       |-- Redis -> Celery workers
       |-- private SigLIP/OCR/SFace/Whisper service
       |-- private Qwen vLLM service
       `-- content-addressed originals + versioned derivatives
```

## Implemented phases

### Phase 0 — private library foundation

Delivered authentication, the responsive gallery and viewer, the separately
scoped CC0 demo library, immutable original storage, resumable file/folder
uploads, generated frontend contracts and API-authorized Developer mode.

Acceptance evidence:

- unauthenticated private endpoints return 401;
- all 14 demo assets pass licence, checksum and dimension checks;
- nested uploads resume after an offset boundary and reject traversal;
- normal photo/search schemas contain no job, model, queue or score details;
- desktop and mobile gallery states are covered by browser verification.

### Phase 1 — deterministic local enrichment

Delivered private local-only adapters for SigLIP, PaddleOCR, YuNet/SFace,
Whisper and Qwen. Artifacts use the unique key
`(photo_id, artifact_kind, pipeline_version)`. Worker retries and uniqueness
constraints make jobs idempotent; each failing adapter marks only that photo
partial.

Model paths are mounted read-only. Transformers and faster-whisper use
local/offline loading, and runtime requests never download weights.

### Phase 2 — hybrid retrieval and streaming answers

Delivered:

1. validated Qwen search plans;
2. metadata, date, place, person, OCR and transcript candidates;
3. native pgvector cosine retrieval with a SQLite test fallback;
4. reciprocal-rank fusion and account filtering;
5. immediate SSE cards;
6. bounded concurrent Qwen visual verification;
7. concise answers validated against explicit photo-ID citations.

Planner, captioner, verifier and answer-writer prompts each have a version
constant, typed input/output, JSON Schema, Identity/Instructions/Examples/
Context sections and injection-shaped evaluation fixtures. Model output cannot
express SQL, authorization rules or filesystem paths.

### Phase 3 — people, places and temporal memory

Delivered opt-in local face clustering and naming APIs, independent face-data
purge, EXIF date/GPS extraction, offline gazetteer import, PostGIS nearby
queries, place collections, captured timestamps, event grouping and
short per-account conversational references through grounded search traces.
An unresolved reference produces a clarification rather than invented context.

### Phase 4 — operations, hardening and scale

Delivered Alembic migrations, CSRF double-submit protection, Redis rate limits,
strict same-site cookies, Caddy security headers, private container networking,
pipeline re-indexing, model inventories, backup/restore scripts, production
secret validation and operator-only diagnostics.

## Data rules

- Original: `originals/<sha256[0:2]>/<sha256>.<validated-extension>`.
- Upload names/folders are display metadata and never storage paths.
- Demo rows have `scope=demo`, no owner and required attribution.
- Personal rows always have an owner.
- OCR, filenames, EXIF, image text, captions and tags are untrusted prompt data.
- Face indexing is off by default and can be purged without deleting photos.
- A model outage cannot block authentication, gallery browsing or originals.

## Prompt versions

| Task | Version | Output |
|---|---|---|
| Search planning | `search-plan-v1` | `SearchPlan` JSON |
| Image caption | `caption-v1` | `CaptionOutput` JSON |
| Visual verification | `visual-verify-v1` | `VisualVerification` JSON |
| Grounded answer | `grounded-answer-v1` | `GroundedAnswer` JSON |

Every decoder rejects unknown fields. Grounded-answer validation rejects
unknown photo IDs and developer/model vocabulary before the text reaches the
normal API.

## Deployment profiles

- **16 GB GPU:** Qwen FP8 with 4K context; photo models load lazily; Whisper is
  serialized and only loads for supported media.
- **24 GB GPU (recommended):** Qwen FP8 at the default 62% reservation and 8K
  context, with bounded three-way visual verification.
- **Multiple GPUs:** pin Qwen and the private model service to separate device
  IDs before increasing worker concurrency.

## Verification commands

```powershell
$env:PYTHONPATH = "$PWD\backend"
.venv\Scripts\python.exe -m pytest backend\tests -q
.venv\Scripts\python.exe scripts\verify_demo_library.py

cd frontend
npm.cmd test -- --run
npm.cmd run build
```

On the GPU host:

```bash
bash scripts/gpu/check-host.sh
bash scripts/gpu/start.sh
bash scripts/gpu/status.sh
bash scripts/gpu/verify.sh
```

The current development workstation has no Docker or NVIDIA GPU. CPU-safe
integration tests, migrations, generated contracts, production frontend builds
and browser states are verified here; CUDA image/model startup must be verified
by `status.sh` and `verify.sh` on the target GPU server.
