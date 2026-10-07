# LeitorPDF — servidor de voz neural

Este serviço é a primeira etapa do novo sistema de narração do LeitorPDF.

## O que ele faz

- carrega Chatterbox Multilingual V3;
- cadastra uma referência de voz;
- gera português brasileiro a partir de texto;
- devolve WAV para o aplicativo Android;
- mantém a referência de voz fora do APK.

O Chatterbox Multilingual suporta português e geração com áudio de referência para clonagem zero-shot. O projeto oficial mostra o uso de `ChatterboxMultilingualTTS.from_pretrained(..., t3_model="v3")` e `audio_prompt_path`.

## Requisitos

Recomenda-se servidor Linux com GPU NVIDIA. CPU funciona como fallback, mas a geração será muito mais lenta.

Python 3.11 é a base recomendada pelo projeto Chatterbox.

## Rodar

```bash
cd server
python3.11 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

export TTS_API_TOKEN="troque-este-token"
export TTS_DEVICE="cuda"

uvicorn app:app --host 0.0.0.0 --port 8000
```

## Docker

```bash
docker build -t leitorpdf-tts .
docker run --gpus all -p 8000:8000 \
  -e TTS_API_TOKEN="troque-este-token" \
  -e TTS_DEVICE="cuda" \
  -v leitorpdf-voices:/data/voices \
  -v leitorpdf-audio:/data/audio \
  leitorpdf-tts
```

## Primeiro teste

1. Faça `POST /voices` com sua gravação.
2. Pegue o `voice_id`.
3. Faça `POST /tts` com uma frase curta em português.
4. Abra `GET /audio/{audio_id}`.

O texto do endpoint é limitado a 300 caracteres por trecho, seguindo o exemplo do aplicativo multilíngue do Chatterbox. O LeitorPDF vai dividir automaticamente o texto do PDF em trechos antes de enviar ao servidor.

## Segurança

- nunca coloque `TTS_API_TOKEN` no APK;
- use HTTPS;
- mantenha `/data/voices` privado;
- use somente vozes próprias ou autorizadas;
- não coloque gravações pessoais no Git.

A próxima etapa é integrar este servidor ao player Android, substituir o `PdfSpeechService` baseado em Android TTS e manter o restante do leitor intacto.
