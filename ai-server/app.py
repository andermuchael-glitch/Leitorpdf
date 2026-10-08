import os
from fastapi import FastAPI, Header, HTTPException
from openai import OpenAI
from pydantic import BaseModel, Field

app = FastAPI(title="LeitorPDF AI", version="0.2.0")

MODEL = os.getenv("OPENAI_TEXT_MODEL", "gpt-6-astra")
IMAGE_MODEL = os.getenv("OPENAI_IMAGE_MODEL", "gpt-image-2")
API_TOKEN = os.getenv("AI_API_TOKEN", "").strip()

class ImageRequest(BaseModel):
    prompt: str = Field(min_length=3, max_length=12000)

class AnalyzeRequest(BaseModel):
    action: str = Field(min_length=2, max_length=80)
    text: str = Field(min_length=1, max_length=18000)
    context: str = Field(default="", max_length=24000)

def authorize(authorization: str | None):
    if API_TOKEN and authorization != "Bearer " + API_TOKEN:
        raise HTTPException(status_code=401, detail="Token de IA inválido.")

def client() -> OpenAI:
    api_key = os.getenv("OPENAI_API_KEY", "").strip()
    if not api_key:
        raise HTTPException(status_code=503, detail="OPENAI_API_KEY não configurada no servidor.")
    return OpenAI(api_key=api_key)

@app.get("/health")
def health():
    return {"ok": True, "model": MODEL, "imageModel": IMAGE_MODEL}

@app.post("/analyze")
def analyze(request: AnalyzeRequest, authorization: str | None = Header(default=None)):
    authorize(authorization)
    prompts = {
        "explicar": "Explique o trecho com profundidade, mantendo fidelidade ao texto e apontando contexto e ideias principais.",
        "resumir": "Resuma o trecho de forma objetiva, preservando as informações essenciais.",
        "simples": "Explique o trecho em linguagem simples, como para alguém que está aprendendo o assunto.",
        "perguntas_capitulo": "Crie perguntas e respostas para verificar a compreensão do capítulo.",
        "conceitos": "Identifique os conceitos, temas, termos e ideias mais importantes e explique cada um brevemente.",
        "estudo": "Crie um estudo estruturado com resumo, ideias-chave, conceitos, aplicações e revisão.",
        "perguntas_estudo": "Gere perguntas para estudo em níveis fácil, médio e avançado, com respostas no final."
    }
    instruction = prompts.get(request.action, "Analise o texto de forma útil para um leitor.")
    prompt = instruction + "\n\nTEXTO:\n" + request.text
    if request.context.strip():
        prompt += "\n\nCONTEXTO DO LIVRO:\n" + request.context
    try:
        response = client().responses.create(
            model=MODEL,
            reasoning={"effort": "low"},
            instructions="Você é a IA de leitura do LeitorPDF. Responda em português do Brasil. Não invente fatos que não estejam sustentados pelo texto; quando fizer uma interpretação, deixe claro que é interpretação.",
            input=prompt
        )
        return {"result": response.output_text, "model": MODEL}
    except Exception as exc:
        raise HTTPException(status_code=502, detail="Falha na análise: " + str(exc)[:500])

@app.post("/generate-image")
def generate_image(request: ImageRequest, authorization: str | None = Header(default=None)):
    authorize(authorization)
    try:
        result = client().images.generate(
            model=IMAGE_MODEL,
            prompt=request.prompt,
            size="1024x1024",
            quality="medium",
        )
        data = result.data or []
        if not data or not data[0].b64_json:
            raise RuntimeError("A API não retornou a imagem.")
        return {"imageBase64": data[0].b64_json, "model": IMAGE_MODEL}
    except Exception as exc:
        raise HTTPException(status_code=502, detail="Falha na geração da imagem: " + str(exc)[:500])
