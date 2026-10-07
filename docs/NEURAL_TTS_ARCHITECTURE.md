# Áudio neural — arquitetura nova

## Objetivo
Substituir completamente a narração Android TextToSpeech por um pipeline neural com voz personalizada, sem alterar o leitor PDF, o modo Livro 3D ou a biblioteca.

## Fluxo
PDF → extração de texto → normalização de referências e segmentação por trechos → API de TTS neural → áudio WAV/MP3 por trecho → cache local → Android Media3 / MediaSession → reprodução em segundo plano.

## Voz personalizada
A gravação do usuário é usada como áudio de referência. O arquivo original não deve ser armazenado no repositório público.
A primeira implementação será baseada em Chatterbox Multilingual V3, que aceita áudio de referência (audio_prompt_path) e possui suporte a português. A API ficará no servidor; o APK nunca armazenará credenciais de servidor/modelo.

## Componentes
### Android
- SpeechRepository: solicita geração de trechos e acompanha o estado.
- NeuralSpeechPlayer: player persistente em segundo plano.
- cache por documento + voz + página + trecho.
- retomada por página/trecho/posição.
- pré-carregamento do próximo trecho.
- controles play/pause/seek/velocidade.
- download da narração.
- seleção de voz.
- tela para cadastrar/testar a voz do usuário.

### Backend
- GET /health
- GET /voices
- POST /voices — recebe a amostra autorizada da voz.
- POST /tts — recebe texto, voz, idioma e parâmetros.
- resposta com áudio e metadados do trecho.
- armazenamento privado das referências de voz.
- autenticação antes de expor geração.

## Segurança
A chave/credencial do motor neural nunca será colocada no APK. A amostra de voz enviada pelo usuário também não será commitada no GitHub.
A clonagem será usada apenas para a voz do próprio usuário ou de uma pessoa que tenha autorizado explicitamente seu uso.

## Fases
1. Preparar e validar a referência de voz.
2. Subir backend neural.
3. Criar endpoint de teste e gerar uma primeira frase.
4. Integrar o player neural ao Android.
5. Integrar geração por páginas e cache.
6. Retomada, segundo plano e download.
7. Sincronização fina do destaque do texto após o áudio estar estável.

## Observação
O modelo neural deve rodar em servidor/GPU. O Android será cliente/player. Isso evita colocar um modelo pesado dentro do APK e é muito mais adequado para voz realista e clonagem.