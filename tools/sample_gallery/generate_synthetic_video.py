from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np


def generate(destination: Path) -> dict[str, object]:
    """Create a deterministic CC0 MP4 with three visually distinct, text-searchable scenes."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    width, height, fps = 640, 360, 10
    writer = cv2.VideoWriter(
        str(destination),
        cv2.VideoWriter_fourcc(*"mp4v"),
        float(fps),
        (width, height),
    )
    if not writer.isOpened():
        raise RuntimeError("OpenCV could not create the synthetic MP4 fixture")
    scenes = [
        ((160, 70, 20), "MARINA BAY", "SKYLINE"),
        ((20, 180, 230), "YELLOW BICYCLE", "PARK"),
        ((40, 135, 45), "GARDEN", "SUNSET"),
    ]
    try:
        for background, headline, subtitle in scenes:
            for frame_index in range(60):
                frame = np.full((height, width, 3), background, dtype=np.uint8)
                cv2.rectangle(frame, (28, 70), (612, 290), (10, 10, 10), thickness=-1)
                cv2.putText(frame, headline, (48, 165), cv2.FONT_HERSHEY_SIMPLEX, 1.45, (255, 255, 255), 4, cv2.LINE_AA)
                cv2.putText(frame, subtitle, (48, 235), cv2.FONT_HERSHEY_SIMPLEX, 1.2, (240, 240, 240), 3, cv2.LINE_AA)
                cv2.circle(frame, (560, 315), 12 + (frame_index % 8), (255, 255, 255), thickness=2)
                writer.write(frame)
    finally:
        writer.release()
    if not destination.is_file() or destination.stat().st_size < 10_000:
        raise RuntimeError("Synthetic MP4 fixture was not written")
    return {
        "id": "synthetic_video_timeline",
        "filename": destination.name,
        "kind": "VIDEO",
        "duration_ms": 18_000,
        "labels": ["marina_bay", "yellow_bicycle", "garden", "timeline"],
        "license": "CC0 1.0",
        "synthetic": True,
    }
