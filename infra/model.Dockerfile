FROM pytorch/pytorch:2.8.0-cuda12.8-cudnn9-runtime

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/backend \
    HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
      ffmpeg libgl1 libglib2.0-0 libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY backend/requirements.txt /app/backend/requirements.txt
COPY backend/requirements-gpu.txt /app/backend/requirements-gpu.txt
RUN python -m pip install --no-cache-dir \
      paddlepaddle-gpu==3.2.2 \
      --index-url https://www.paddlepaddle.org.cn/packages/stable/cu126/ \
    && python -m pip install --no-cache-dir -r /app/backend/requirements-gpu.txt

COPY backend /app/backend
WORKDIR /app/backend

CMD ["uvicorn", "app.model_service:app", "--host", "0.0.0.0", "--port", "8091", "--workers", "1"]
