# Leitor PDF — servidor de voz profissional

Este diretório lógico é servido pela Vercel usando `/api/tts`.

## Variáveis de ambiente

Configure no projeto Vercel:

- `GOOGLE_PROJECT_ID`
- `GOOGLE_CLIENT_EMAIL`
- `GOOGLE_PRIVATE_KEY`
- opcional: `TTS_ACCESS_TOKEN`

A conta de serviço precisa ter permissão para usar a Cloud Text-to-Speech API.

O APK nunca recebe a chave privada do Google Cloud. Ele envia somente o texto e o ID da voz para este endpoint.

## Endpoint

`POST /api/tts`

Body:

```json
{
  "text": "Texto para leitura.",
  "voice": "pt-BR-Chirp3-HD-Rasalgethi",
  "rate": 1.0
}
```

Resposta: áudio MP3.

O app divide o PDF em frases, mantém cache local e pré-carrega os próximos trechos para reduzir pausas entre frases.
