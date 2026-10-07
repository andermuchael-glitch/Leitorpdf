FROM nvidia/cuda:12.8.1-cudnn-runtime-ubuntu24.04

ENV DEBIAN_FRONTEND=noninteractive
ENV PYTHONUNBUFFERED=1
ENV PIP_NO_CACHE_DIR=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    python3.11 python3.11-venv python3-pip ffmpeg git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN python3.11 -m pip install --break-system-packages --upgrade pip \
    && python3.11 -m pip install --break-system-packages -r requirements.txt

COPY app.py .

RUN mkdir -p /data/voices /data/audio

EXPOSE 8000

CMD ["python3.11", "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
