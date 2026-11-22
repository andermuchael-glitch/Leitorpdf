# Leitorpdf

Aplicativo Android nativo para leitura de documentos PDF com análise por IA, anotações, gravação de áudio e visualização avançada.

## Stack

- Kotlin 2.4+
- Jetpack Compose (BOM 2026.11+)
- Material 3
- AndroidX
- MVVM
- PDFBox Android (extração de texto)
- Servidor FastAPI opcional para recursos de IA

## Funcionalidades atuais

- Seleção e abertura de PDF via Storage Access Framework e intent VIEW
- Extração de texto por página (PDFBox)
- Biblioteca local com detecção de título e geração de capa
- Anotações, destaques coloridos e marcadores de página
- Três modos de leitura: PDF nativo, reflow de texto e visualização 3D de livro
- Análise de trechos com IA (explicar, resumir, perguntas de estudo, conceitos, etc.)
- Geração de imagens a partir de trechos selecionados (via servidor)
- Gravação de áudio de leitura com foreground service
- Tema claro/escuro persistente
- Salvamento automático do progresso de leitura

## Próximas etapas

1. Text-to-Speech em Português do Brasil com destaque visual sincronizado do texto
2. Controles de velocidade, pausa e reprodução em segundo plano
3. Suporte a PDFs escaneados via OCR
4. Pesquisa na biblioteca local

Os documentos permanecem no aparelho. O servidor de IA é completamente opcional; a chave da OpenAI nunca entra no APK.
