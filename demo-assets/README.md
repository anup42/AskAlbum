# AskPhotos demo library

This directory is a separately scoped, removable demo library. Every image is
CC0 1.0 and is listed in `manifest.json` with its source page, original download
URL and pinned SHA-256 checksum. Removing this directory removes the demo media;
personal uploads are stored elsewhere under `backend/data/originals`.

Run `python scripts/verify_demo_library.py` from the repository root to verify
checksums, image dimensions and licence metadata.

