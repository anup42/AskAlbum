from __future__ import annotations

import base64
from pathlib import Path
from typing import Any

import httpx

from .config import settings


class LocalModelUnavailable(RuntimeError):
    pass


class LocalModelClient:
    def __init__(self) -> None:
        self.timeout = httpx.Timeout(settings.model_request_timeout_seconds, connect=3.0)

    def _model_post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        try:
            response = httpx.post(
                f"{settings.model_service_url.rstrip('/')}{path}",
                json=payload,
                timeout=self.timeout,
            )
            response.raise_for_status()
            return response.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise LocalModelUnavailable(f"local model service failed at {path}") from exc

    def embed_image(self, image_path: Path) -> list[float]:
        payload = self._model_post("/v1/embed/image", {"path": str(image_path)})
        return [float(value) for value in payload["embedding"]]

    def embed_text(self, text: str) -> list[float]:
        payload = self._model_post("/v1/embed/text", {"text": text[:1000]})
        return [float(value) for value in payload["embedding"]]

    def ocr(self, image_path: Path) -> dict[str, Any]:
        return self._model_post("/v1/ocr", {"path": str(image_path)})

    def faces(self, image_path: Path) -> list[dict[str, Any]]:
        return self._model_post("/v1/faces", {"path": str(image_path)}).get("faces", [])

    def transcribe(self, media_path: Path) -> dict[str, Any]:
        return self._model_post("/v1/transcribe", {"path": str(media_path)})

    def qwen_text(self, prompt: str, max_tokens: int = 500, temperature: float = 0.0) -> str:
        payload = {
            "model": settings.qwen_model_name,
            "messages": [{"role": "user", "content": prompt}],
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        try:
            response = httpx.post(
                f"{settings.qwen_base_url.rstrip('/')}/chat/completions",
                json=payload,
                timeout=self.timeout,
            )
            response.raise_for_status()
            return str(response.json()["choices"][0]["message"]["content"])
        except (httpx.HTTPError, KeyError, IndexError, ValueError) as exc:
            raise LocalModelUnavailable("local Qwen service is unavailable") from exc

    def qwen_image(self, image_path: Path, prompt: str, max_tokens: int = 220) -> str:
        media_types = {
            ".png": "image/png",
            ".webp": "image/webp",
            ".gif": "image/gif",
        }
        media_type = media_types.get(image_path.suffix.lower(), "image/jpeg")
        encoded = base64.b64encode(image_path.read_bytes()).decode("ascii")
        payload = {
            "model": settings.qwen_model_name,
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": prompt},
                        {
                            "type": "image_url",
                            "image_url": {"url": f"data:{media_type};base64,{encoded}"},
                        },
                    ],
                }
            ],
            "temperature": 0.0,
            "max_tokens": max_tokens,
        }
        try:
            response = httpx.post(
                f"{settings.qwen_base_url.rstrip('/')}/chat/completions",
                json=payload,
                timeout=self.timeout,
            )
            response.raise_for_status()
            return str(response.json()["choices"][0]["message"]["content"]).strip()
        except (httpx.HTTPError, KeyError, IndexError, ValueError, OSError) as exc:
            raise LocalModelUnavailable("local Qwen vision service is unavailable") from exc
