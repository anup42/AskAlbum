FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/backend

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
      libjpeg62-turbo libpng16-16 libwebp7 libmagic1 ffmpeg \
    && rm -rf /var/lib/apt/lists/*

COPY backend/requirements.txt /app/backend/requirements.txt
RUN pip install --no-cache-dir -r /app/backend/requirements.txt

COPY backend /app/backend
COPY scripts /app/scripts
COPY infra/entrypoint.sh /usr/local/bin/askphotos-entrypoint
RUN chmod +x /usr/local/bin/askphotos-entrypoint

WORKDIR /app/backend
ENTRYPOINT ["askphotos-entrypoint"]
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
