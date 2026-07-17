# AskPhotos

AskPhotos is a privacy-first photo search server. One GPU machine stores the
original files, runs every model locally, and serves a polished authenticated
browser application to computers and phones on the network. It has no OpenAI
API, Google API, or other paid inference dependency.

## What is included

- React, TypeScript, Tailwind, Radix/shadcn-style primitives, Lucide and Motion;
- FastAPI, PostgreSQL, pgvector, PostGIS, Redis and idempotent Celery workers;
- immutable SHA-256 original storage and resumable file/folder uploads;
- Qwen vision-language planning, captioning, verification and grounded answers;
- SigLIP image/text retrieval, PaddleOCR, opt-in YuNet/SFace and Whisper;
- streamed search results before visual verification finishes;
- EXIF dates/GPS, offline place import, nearby search, people and event APIs;
- API-enforced Developer mode, CSRF, rate limits and private service ports;
- separately scoped, checksum-pinned CC0 demo photos;
- migration, verification, backup, restore and rolling re-index scripts.

Runtime model weights are downloaded once by an operator script and mounted
read-only. Request handling is offline-only.

## GPU server requirements

Recommended:

- Ubuntu 24.04 LTS x86-64;
- an NVIDIA GPU with 24 GB VRAM or more; 16 GB can work with a shorter Qwen
  context and conservative concurrency;
- a current NVIDIA driver for CUDA 12.8 containers;
- 80 GB free disk for images, containers, model weights and working space;
- Docker Engine with Compose v2 and NVIDIA Container Toolkit;
- Python 3, `curl` and `openssl`.

The application publishes only one HTTP port. PostgreSQL, Redis, workers and
both model services remain on a private Docker network.

## Fresh GPU installation

Run these commands from the repository root on the GPU server.

1. If Docker or NVIDIA Container Toolkit is not installed, install the host
   runtime. The NVIDIA driver must already be installed and `nvidia-smi` must
   work:

   ```bash
   bash scripts/gpu/install-host-ubuntu.sh
   ```

   Sign out and back in once after this script changes Docker group membership.

2. Verify Docker can see the GPU:

   ```bash
   bash scripts/gpu/check-host.sh
   ```

3. Create the private production configuration:

   ```bash
   bash scripts/gpu/configure.sh \
     --http-port 8080
   ```

   Enter the admin password at the private prompt. This creates `.env` with
   generated application and database secrets. The password may contain
   letters, numbers and `@ % + = : , . _ -`. For unattended provisioning, pass
   it in `ASKPHOTOS_ADMIN_PASSWORD`. Do not commit or share `.env`.

4. Download all open model weights and write `models/manifest.json`:

   ```bash
   bash scripts/gpu/download-models.sh
   ```

   `HF_TOKEN` is optional for public models. To store weights elsewhere, use
   the same absolute path in both commands:

   ```bash
   bash scripts/gpu/configure.sh --force \
     --models-dir /srv/askphotos-models
   bash scripts/gpu/download-models.sh --models-dir /srv/askphotos-models
   ```

5. Build and start the complete application:

   ```bash
   bash scripts/gpu/start.sh
   ```

6. Open the URL printed by the start script from another computer, then sign in
   as `admin`. The demo library is usable immediately. Local enrichment
   continues in the background.

7. Verify the deployment:

   ```bash
   bash scripts/gpu/status.sh
   bash scripts/gpu/verify.sh
   ```

If Ubuntu's firewall is enabled, permit only the configured gateway port from
your trusted LAN. Do not publish ports 5432, 6379, 8000, 8091 or 8092.

## Everyday operations

Stop without deleting photos or databases:

```bash
bash scripts/gpu/stop.sh
```

Queue every photo against the current pipeline version:

```bash
bash scripts/gpu/reindex.sh
```

Import an offline place gazetteer:

```bash
bash scripts/gpu/import-places.sh /path/to/places.csv
```

The CSV header is
`name,country_code,latitude,longitude,population`. GPS from uploaded EXIF data
is matched locally; no reverse-geocoding request leaves the server.

Create a consistent database and media backup:

```bash
bash scripts/gpu/backup.sh /srv/askphotos-backups
```

Restore a chosen backup (this deliberately replaces current data):

```bash
ASKPHOTOS_RESTORE_CONFIRM=RESTORE \
  bash scripts/gpu/restore.sh /srv/askphotos-backups/20260717T120000Z
```

Enable familiar-face grouping only if wanted in Settings. Turning it off stops
new face indexing; “Delete face data” purges stored face signatures and groups.
Developer/model information is absent from normal API responses and the DOM
unless an administrator explicitly enables Developer mode.

## Network and TLS

The generated configuration uses HTTP and `ASKPHOTOS_COOKIE_SECURE=false` for a
trusted LAN. For access outside that LAN, put the gateway behind a TLS reverse
proxy or private VPN, set `ASKPHOTOS_COOKIE_SECURE=true`, and restart. Direct
Internet exposure is not the intended deployment.

## GPU tuning

The defaults run Qwen FP8 with 62% GPU-memory utilization and an 8,192-token
context while the other model service loads photo models lazily. On a 16 GB
card, start with:

```dotenv
ASKPHOTOS_QWEN_MAX_MODEL_LEN=4096
ASKPHOTOS_QWEN_GPU_MEMORY_UTILIZATION=0.60
```

On a multi-GPU server, assign devices with Compose GPU device reservations
before increasing worker concurrency. Keep `ASKPHOTOS_LOCAL_MODELS_ENABLED=true`;
the application never substitutes a hosted API.

## Model inventory

The downloader currently pins revisions for:

- `Qwen/Qwen3-VL-8B-Instruct-FP8`;
- `google/siglip2-so400m-patch14-384`;
- `PaddlePaddle/PP-OCRv5_server_det` and `PP-OCRv5_server_rec`;
- OpenCV Zoo YuNet and SFace;
- `Systran/faster-whisper-large-v3-turbo`.

Repository URLs, revisions, roles, declared licences and OpenCV checksums are
recorded in `models/manifest.json`. Review that inventory before production use.

## Local development and tests

Local development deliberately disables model calls so tests need no GPU:

```powershell
python -m venv .venv
.venv\Scripts\python -m pip install -r backend\requirements-dev.txt
$env:PYTHONPATH = "$PWD\backend"
.venv\Scripts\python -m pytest backend\tests -q
Push-Location backend
..\.venv\Scripts\python -m alembic -c alembic.ini upgrade head
Pop-Location

cd frontend
npm.cmd install
npm.cmd test -- --run
npm.cmd run build
```

For the development UI, run FastAPI on port 8000 and Vite on port 5173. The
development-only account is `admin` / `askphotos`; production refuses that
password.

The complete architecture, phase acceptance criteria and prompt contracts are
in [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md).
