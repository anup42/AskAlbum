from __future__ import annotations

import json
import threading
from pathlib import Path
from typing import Any

import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .config import settings


class PathRequest(BaseModel):
    path: str = Field(min_length=1, max_length=1600)


class TextRequest(BaseModel):
    text: str = Field(min_length=1, max_length=1000)


class ModelRegistry:
    def __init__(self) -> None:
        self.lock = threading.RLock()
        self.siglip: tuple[Any, Any, Any] | None = None
        self.ocr_pipeline: Any | None = None
        self.face_detector: Any | None = None
        self.face_recognizer: Any | None = None
        self.whisper: Any | None = None

    @staticmethod
    def _required_path(value: Path | None, name: str) -> Path:
        if value is None:
            raise HTTPException(status_code=503, detail=f"{name} is not configured")
        path = value.expanduser().resolve()
        if not path.exists():
            raise HTTPException(status_code=503, detail=f"{name} is unavailable")
        return path

    def get_siglip(self) -> tuple[Any, Any, Any]:
        with self.lock:
            if self.siglip is None:
                model_path = self._required_path(settings.siglip_model_dir, "SigLIP")
                try:
                    import torch
                    from transformers import AutoModel, AutoProcessor

                    processor = AutoProcessor.from_pretrained(model_path, local_files_only=True)
                    model = AutoModel.from_pretrained(
                        model_path,
                        local_files_only=True,
                        dtype=torch.float16 if settings.model_device.startswith("cuda") else torch.float32,
                    )
                    device = torch.device(settings.model_device)
                    model.to(device).eval()
                    self.siglip = model, processor, device
                except Exception as exc:
                    raise HTTPException(status_code=503, detail="SigLIP could not be loaded") from exc
            return self.siglip

    def get_ocr(self) -> Any:
        with self.lock:
            if self.ocr_pipeline is None:
                model_root = self._required_path(settings.paddleocr_model_dir, "PaddleOCR")
                detection_dir = model_root / "det"
                recognition_dir = model_root / "rec"
                if not detection_dir.is_dir() or not recognition_dir.is_dir():
                    raise HTTPException(status_code=503, detail="PaddleOCR model directories are incomplete")
                try:
                    from paddleocr import PaddleOCR

                    self.ocr_pipeline = PaddleOCR(
                        text_detection_model_dir=str(detection_dir),
                        text_recognition_model_dir=str(recognition_dir),
                        use_doc_orientation_classify=False,
                        use_doc_unwarping=False,
                        use_textline_orientation=False,
                        device="gpu:0" if settings.model_device.startswith("cuda") else "cpu",
                    )
                except Exception as exc:
                    raise HTTPException(status_code=503, detail="PaddleOCR could not be loaded") from exc
            return self.ocr_pipeline

    def get_faces(self) -> tuple[Any, Any]:
        with self.lock:
            if self.face_detector is None or self.face_recognizer is None:
                yunet = self._required_path(settings.yunet_model_path, "YuNet")
                sface = self._required_path(settings.sface_model_path, "SFace")
                try:
                    import cv2

                    backend = cv2.dnn.DNN_BACKEND_CUDA if settings.model_device.startswith("cuda") else cv2.dnn.DNN_BACKEND_OPENCV
                    target = cv2.dnn.DNN_TARGET_CUDA_FP16 if settings.model_device.startswith("cuda") else cv2.dnn.DNN_TARGET_CPU
                    self.face_detector = cv2.FaceDetectorYN.create(
                        str(yunet), "", (320, 320), 0.75, 0.3, 5000, backend, target
                    )
                    self.face_recognizer = cv2.FaceRecognizerSF.create(str(sface), "", backend, target)
                except Exception as exc:
                    raise HTTPException(status_code=503, detail="SFace could not be loaded") from exc
            return self.face_detector, self.face_recognizer

    def get_whisper(self) -> Any:
        with self.lock:
            if self.whisper is None:
                model_path = self._required_path(settings.whisper_model_dir, "Whisper")
                try:
                    from faster_whisper import WhisperModel

                    self.whisper = WhisperModel(
                        str(model_path),
                        device="cuda" if settings.model_device.startswith("cuda") else "cpu",
                        compute_type="float16" if settings.model_device.startswith("cuda") else "int8",
                        local_files_only=True,
                    )
                except Exception as exc:
                    raise HTTPException(status_code=503, detail="Whisper could not be loaded") from exc
            return self.whisper


registry = ModelRegistry()
app = FastAPI(
    title="AskPhotos private model service",
    version="1.0.0",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)


def allowed_media_path(raw: str) -> Path:
    path = Path(raw).expanduser().resolve()
    roots = [settings.data_dir.resolve(), settings.demo_dir.resolve()]
    if not path.is_file() or not any(path == root or root in path.parents for root in roots):
        raise HTTPException(status_code=404, detail="Media is unavailable")
    return path


def normalized_vector(value: Any) -> list[float]:
    vector = np.asarray(value, dtype=np.float32).reshape(-1)
    norm = float(np.linalg.norm(vector))
    if norm == 0:
        raise HTTPException(status_code=500, detail="Model returned an empty embedding")
    return (vector / norm).astype(np.float32).tolist()


def feature_tensor(value: Any) -> Any:
    if hasattr(value, "pooler_output") and value.pooler_output is not None:
        return value.pooler_output
    if hasattr(value, "last_hidden_state"):
        return value.last_hidden_state.mean(dim=1)
    return value


@app.get("/health")
def health() -> dict[str, Any]:
    configured = {
        "siglip": bool(settings.siglip_model_dir and settings.siglip_model_dir.exists()),
        "paddleocr": bool(
            settings.paddleocr_model_dir
            and (settings.paddleocr_model_dir / "det").is_dir()
            and (settings.paddleocr_model_dir / "rec").is_dir()
        ),
        "sface": bool(
            settings.sface_model_path
            and settings.sface_model_path.is_file()
            and settings.yunet_model_path
            and settings.yunet_model_path.is_file()
        ),
        "whisper": bool(settings.whisper_model_dir and settings.whisper_model_dir.exists()),
    }
    if not all(configured.values()):
        raise HTTPException(status_code=503, detail="One or more configured local model directories are unavailable")
    return {"status": "ok", "device": settings.model_device, "configured": configured}


@app.post("/v1/embed/image")
def embed_image(payload: PathRequest) -> dict[str, Any]:
    path = allowed_media_path(payload.path)
    model, processor, device = registry.get_siglip()
    try:
        import torch
        from PIL import Image

        with Image.open(path) as image:
            inputs = processor(images=image.convert("RGB"), return_tensors="pt")
        inputs = {key: value.to(device) for key, value in inputs.items()}
        with torch.inference_mode():
            features = feature_tensor(model.get_image_features(**inputs))
        return {"embedding": normalized_vector(features.detach().float().cpu().numpy())}
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail="Image embedding failed") from exc


@app.post("/v1/embed/text")
def embed_text(payload: TextRequest) -> dict[str, Any]:
    model, processor, device = registry.get_siglip()
    try:
        import torch

        inputs = processor(text=[payload.text], padding="max_length", return_tensors="pt")
        inputs = {key: value.to(device) for key, value in inputs.items()}
        with torch.inference_mode():
            features = feature_tensor(model.get_text_features(**inputs))
        return {"embedding": normalized_vector(features.detach().float().cpu().numpy())}
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail="Text embedding failed") from exc


def result_as_dict(result: Any) -> dict[str, Any]:
    candidate = getattr(result, "json", None)
    if callable(candidate):
        candidate = candidate()
    if candidate is None:
        candidate = getattr(result, "res", None)
    if isinstance(candidate, str):
        candidate = json.loads(candidate)
    if isinstance(candidate, dict) and isinstance(candidate.get("res"), dict):
        return candidate["res"]
    return candidate if isinstance(candidate, dict) else {}


@app.post("/v1/ocr")
def ocr(payload: PathRequest) -> dict[str, Any]:
    path = allowed_media_path(payload.path)
    pipeline = registry.get_ocr()
    try:
        raw_results = list(pipeline.predict(input=str(path)))
        blocks: list[dict[str, Any]] = []
        for raw in raw_results:
            value = result_as_dict(raw)
            texts = value.get("rec_texts") or value.get("texts") or []
            scores = value.get("rec_scores") or value.get("scores") or []
            polygons = value.get("rec_polys") or value.get("dt_polys") or []
            for index, text in enumerate(texts):
                clean = str(text).strip()
                if not clean:
                    continue
                score = float(scores[index]) if index < len(scores) else 0.0
                polygon = np.asarray(polygons[index]).tolist() if index < len(polygons) else []
                blocks.append({"text": clean[:500], "confidence": score, "polygon": polygon})
        return {"text": "\n".join(block["text"] for block in blocks)[:20_000], "blocks": blocks[:500]}
    except Exception as exc:
        raise HTTPException(status_code=500, detail="OCR failed") from exc


@app.post("/v1/faces")
def faces(payload: PathRequest) -> dict[str, Any]:
    path = allowed_media_path(payload.path)
    detector, recognizer = registry.get_faces()
    try:
        import cv2

        image = cv2.imread(str(path))
        if image is None:
            raise ValueError("image could not be decoded")
        height, width = image.shape[:2]
        detector.setInputSize((width, height))
        detected = detector.detect(image)[1]
        output: list[dict[str, Any]] = []
        if detected is not None:
            for face in detected[:50]:
                aligned = recognizer.alignCrop(image, face)
                embedding = normalized_vector(recognizer.feature(aligned))
                output.append(
                    {
                        "box": [float(value) for value in face[:4]],
                        "confidence": float(face[-1]),
                        "embedding": embedding,
                    }
                )
        return {"faces": output}
    except Exception as exc:
        raise HTTPException(status_code=500, detail="Face indexing failed") from exc


@app.post("/v1/transcribe")
def transcribe(payload: PathRequest) -> dict[str, Any]:
    path = allowed_media_path(payload.path)
    model = registry.get_whisper()
    try:
        segments, info = model.transcribe(str(path), vad_filter=True, beam_size=5)
        items = [
            {"start": float(segment.start), "end": float(segment.end), "text": segment.text.strip()}
            for segment in segments
            if segment.text.strip()
        ]
        return {
            "language": info.language,
            "language_probability": float(info.language_probability),
            "text": " ".join(item["text"] for item in items)[:50_000],
            "segments": items[:5000],
        }
    except Exception as exc:
        raise HTTPException(status_code=500, detail="Transcription failed") from exc
