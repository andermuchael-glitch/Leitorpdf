import os
import secrets
import shutil
import subprocess
import tempfile
import threading
import uuid
from pathlib import Path
from typing import Optional

import soundfile as sf
import torch
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from chatterbox.mtl_tts import ChatterboxMultilingualTTS


APP_VERSION = "0.2.0"
VOICE_DIR = Path(os.getenv("VOICE_DIR", "/data/voices"))
AUDIO_DIR = Path(os.getenv("AUDIO_DIR", "/data/audio"))
VOICE_DIR.mkdir(parents=True, exist_ok=True)
AUDIO_DIR.mkdir(parents=True, exist_ok=True)

API_TOKEN = os.getenv("TTS_API_TOKEN", "").strip()
MODEL_VERSION = os.getenv("CHATTERBOX_MODEL", "v3").strip() or "v3"

configured_device = os.getenv("TTS_DEVICE", "auto").strip().lower()
if configured_device == "auto":
    DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
else:
    DEVICE = configured_device

app = FastAPI(
    title="LeitorPDF Neural Voice API",
    version=APP_VERSION,
)

_model = None
_model_lock = threading.Lock()
_generation_lock = threading.Lock()


def require_token(authorization: Optional[str] = Header(default=None)) -> None:
    if not API_TOKEN:
        return

    expected = f"Bearer {API_TOKEN}"
    if not authorization or not secrets.compare_digest(authorization, expected):
        raise HTTPException(status_code=401, detail="Token de API inválido.")


def get_model() -> ChatterboxMultilingualTTS:
    global _model

    if _model is None:
        with _model_lock:
            if _model is None:
                _model = ChatterboxMultilingualTTS.from_pretrained(
                    device=DEVICE,
                    t3_model=MODEL_VERSION,
                )
    return _model


class TtsRequest(BaseModel):
    text: str = Field(min_length=1, max_length=300)
    voice_id: str = Field(min_length=1, max_length=128)
    language_id: str = Field(default="pt", min_length=2, max_length=5)
    exaggeration: float = Field(default=0.5, ge=0.25, le=2.0)
    temperature: float = Field(default=0.8, ge=0.05, le=2.0)
    cfg_weight: float = Field(default=0.5, ge=0.2, le=1.0)
    seed: int = Field(default=0, ge=0)


@app.get("/health")
def health():
    return {
        "ok": True,
        "version": APP_VERSION,
        "device": DEVICE,
        "cuda": torch.cuda.is_available(),
        "model_loaded": _model is not None,
        "model": f"chatterbox-multilingual-{MODEL_VERSION}",
    }


@app.get("/voices", dependencies=[Depends(require_token)])
def list_voices():
    voices = []
    for path in sorted(VOICE_DIR.glob("*.wav")):
        voices.append({
            "voice_id": path.stem,
            "name": path.stem,
        })

    return {"voices": voices}


@app.post("/voices", dependencies=[Depends(require_token)])
async def create_voice(
    audio: UploadFile = File(...),
    name: str = Form(default="Minha voz"),
):
    content_type = (audio.content_type or "").lower()
    allowed = {
        "audio/wav",
        "audio/x-wav",
        "audio/wave",
        "audio/mpeg",
        "audio/mp4",
        "audio/x-m4a",
        "audio/aac",
        "audio/ogg",
        "audio/webm",
        "application/octet-stream",
    }

    if content_type not in allowed:
        raise HTTPException(
            status_code=400,
            detail="Envie uma gravação de voz em WAV, M4A, MP3, AAC, OGG ou WebM.",
        )

    voice_id = uuid.uuid4().hex
    destination = VOICE_DIR / f"{voice_id}.wav"
    temporary = VOICE_DIR / f"{voice_id}.upload"

    try:
        with temporary.open("wb") as output:
            while True:
                chunk = await audio.read(1024 * 1024)
                if not chunk:
                    break
                output.write(chunk)

        # Normalize mobile recordings (M4A/MP3/AAC/etc.) to PCM WAV.
        normalized = temporary
        try:
            data, sample_rate = sf.read(temporary, always_2d=False)
        except Exception:
            ffmpeg = shutil.which("ffmpeg")
            if not ffmpeg:
                raise HTTPException(
                    status_code=400,
                    detail="Este servidor precisa do ffmpeg para converter M4A/MP3. Envie WAV PCM ou instale ffmpeg.",
                )

            converted = VOICE_DIR / f"{voice_id}.converted.wav"
            try:
                subprocess.run(
                    [
                        ffmpeg,
                        "-y",
                        "-i",
                        str(temporary),
                        "-ac",
                        "1",
                        "-ar",
                        "24000",
                        "-sample_fmt",
                        "s16",
                        str(converted),
                    ],
                    check=True,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.PIPE,
                    timeout=120,
                )
                normalized = converted
                data, sample_rate = sf.read(normalized, always_2d=False)
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
                converted.unlink(missing_ok=True)
                if isinstance(exc, HTTPException):
                    raise
                raise HTTPException(
                    status_code=400,
                    detail="Não foi possível converter esta gravação para WAV.",
                ) from exc
            finally:
                converted.unlink(missing_ok=True)

        if sample_rate < 16000:
            raise HTTPException(
                status_code=400,
                detail="A amostra precisa ter pelo menos 16 kHz.",
            )

        sf.write(destination, data, sample_rate, subtype="PCM_16")

    finally:
        temporary.unlink(missing_ok=True)
        await audio.close()

    safe_name = " ".join(name.split())[:80] or "Minha voz"

    return {
        "voice_id": voice_id,
        "name": safe_name,
        "sample": str(destination),
    }


@app.post("/tts", dependencies=[Depends(require_token)])
def synthesize(request: TtsRequest):
    voice_path = VOICE_DIR / f"{request.voice_id}.wav"
    if not voice_path.is_file():
        raise HTTPException(status_code=404, detail="Voz não encontrada.")

    model = get_model()

    if request.seed:
        torch.manual_seed(request.seed)
        if torch.cuda.is_available():
            torch.cuda.manual_seed_all(request.seed)

    output_id = uuid.uuid4().hex
    output_path = AUDIO_DIR / f"{output_id}.wav"

    # The model is memory-intensive; serialize inference to avoid GPU OOM.
    with _generation_lock:
        try:
            wav = model.generate(
                request.text,
                language_id=request.language_id,
                audio_prompt_path=str(voice_path),
                exaggeration=request.exaggeration,
                temperature=request.temperature,
                cfg_weight=request.cfg_weight,
            )
            sf.write(
                output_path,
                wav.squeeze(0).detach().cpu().numpy(),
                model.sr,
                subtype="PCM_16",
            )
        except Exception as exc:
            output_path.unlink(missing_ok=True)
            raise HTTPException(
                status_code=500,
                detail=f"Falha ao gerar a narração: {exc}",
            ) from exc

    return {
        "audio_id": output_id,
        "audio_url": f"/audio/{output_id}",
        "sample_rate": model.sr,
        "format": "wav",
        "voice_id": request.voice_id,
        "language_id": request.language_id,
    }


@app.get("/audio/{audio_id}", dependencies=[Depends(require_token)])
def get_audio(audio_id: str):
    path = AUDIO_DIR / f"{audio_id}.wav"
    if not path.is_file():
        raise HTTPException(status_code=404, detail="Áudio não encontrado.")

    return FileResponse(
        path,
        media_type="audio/wav",
        filename=f"leitorpdf-{audio_id}.wav",
    )
