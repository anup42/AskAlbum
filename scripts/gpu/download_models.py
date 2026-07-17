#!/usr/bin/env python3
"""Download the pinned local model inventory before the application starts."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path

import requests
from huggingface_hub import HfApi, snapshot_download


HF_MODELS = {
    "qwen": {
        "repo": "Qwen/Qwen3-VL-8B-Instruct-FP8",
        "target": "qwen",
        "license": "Apache-2.0",
        "role": "query planning, captions and grounded answers",
    },
    "siglip": {
        "repo": "google/siglip2-so400m-patch14-384",
        "target": "siglip",
        "license": "Apache-2.0",
        "role": "image and text retrieval embeddings",
    },
    "paddle_det": {
        "repo": "PaddlePaddle/PP-OCRv5_server_det",
        "target": "paddleocr/det",
        "license": "Apache-2.0",
        "role": "text detection",
    },
    "paddle_rec": {
        "repo": "PaddlePaddle/PP-OCRv5_server_rec",
        "target": "paddleocr/rec",
        "license": "Apache-2.0",
        "role": "text recognition",
    },
    "whisper": {
        "repo": "Systran/faster-whisper-large-v3-turbo",
        "target": "whisper",
        "license": "MIT",
        "role": "local audio and video transcription",
    },
}

OPENCV_MODELS = {
    "yunet": {
        "url": (
            "https://github.com/opencv/opencv_zoo/raw/main/models/"
            "face_detection_yunet/face_detection_yunet_2023mar.onnx"
        ),
        "target": "sface/yunet.onnx",
        "license": "Apache-2.0",
        "role": "opt-in face detection",
    },
    "sface": {
        "url": (
            "https://github.com/opencv/opencv_zoo/raw/main/models/"
            "face_recognition_sface/face_recognition_sface_2021dec.onnx"
        ),
        "target": "sface/sface.onnx",
        "license": "Apache-2.0",
        "role": "opt-in face embeddings",
    },
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_file(url: str, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".part")
    with requests.get(url, stream=True, timeout=(15, 300), allow_redirects=True) as response:
        response.raise_for_status()
        with temporary.open("wb") as destination:
            for chunk in response.iter_content(1024 * 1024):
                if chunk:
                    destination.write(chunk)
    if temporary.stat().st_size < 100 * 1024:
        temporary.unlink(missing_ok=True)
        raise RuntimeError(f"Downloaded model is unexpectedly small: {url}")
    temporary.replace(target)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--models-dir", default="models")
    parser.add_argument("--hf-token", default=os.getenv("HF_TOKEN"))
    parser.add_argument("--qwen-repo", default=HF_MODELS["qwen"]["repo"])
    parser.add_argument("--skip-qwen", action="store_true")
    parser.add_argument("--force-opencv", action="store_true")
    args = parser.parse_args()

    models_dir = Path(args.models_dir).expanduser().resolve()
    models_dir.mkdir(parents=True, exist_ok=True)
    inventory: list[dict[str, object]] = []
    api = HfApi(token=args.hf_token)

    for key, configured in HF_MODELS.items():
        if key == "qwen" and args.skip_qwen:
            continue
        repo = args.qwen_repo if key == "qwen" else str(configured["repo"])
        info = api.model_info(repo, revision="main")
        target = models_dir / str(configured["target"])
        print(f"Downloading {repo} -> {target}", flush=True)
        snapshot_download(
            repo_id=repo,
            revision=info.sha,
            local_dir=target,
            token=args.hf_token,
        )
        inventory.append(
            {
                "name": key,
                "source": f"https://huggingface.co/{repo}",
                "repository": repo,
                "revision": info.sha,
                "license": configured["license"],
                "role": configured["role"],
                "path": str(configured["target"]),
            }
        )

    for key, configured in OPENCV_MODELS.items():
        target = models_dir / str(configured["target"])
        if args.force_opencv or not target.exists():
            print(f"Downloading {key} -> {target}", flush=True)
            download_file(str(configured["url"]), target)
        inventory.append(
            {
                "name": key,
                "source": configured["url"],
                "repository": "opencv/opencv_zoo",
                "revision": "main",
                "license": configured["license"],
                "role": configured["role"],
                "path": str(configured["target"]),
                "sha256": sha256(target),
            }
        )

    manifest = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "runtime_policy": "offline-local-only",
        "models": inventory,
    }
    manifest_path = models_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Model inventory written to {manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
