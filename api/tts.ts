import textToSpeech from "@google-cloud/text-to-speech";

const allowedVoices = new Set([
  "pt-BR-Chirp3-HD-Achernar",
  "pt-BR-Chirp3-HD-Achird",
  "pt-BR-Chirp3-HD-Charon",
  "pt-BR-Chirp3-HD-Enceladus",
  "pt-BR-Chirp3-HD-Fenrir",
  "pt-BR-Chirp3-HD-Gacrux",
  "pt-BR-Chirp3-HD-Iapetus",
  "pt-BR-Chirp3-HD-Kore",
  "pt-BR-Chirp3-HD-Orus",
  "pt-BR-Chirp3-HD-Rasalgethi",
  "pt-BR-Chirp3-HD-Sadachbia",
  "pt-BR-Chirp3-HD-Sadaltager",
  "pt-BR-Chirp3-HD-Schedar",
  "pt-BR-Chirp3-HD-Zubenelgenubi"
]);

let client;

function getClient() {
  if (client) return client;

  const projectId = process.env.GOOGLE_PROJECT_ID;
  const clientEmail = process.env.GOOGLE_CLIENT_EMAIL;
  const privateKey = process.env.GOOGLE_PRIVATE_KEY?.replace(/\\n/g, "\n");

  if (!projectId || !clientEmail || !privateKey) {
    throw new Error("Google Cloud TTS não está configurado no servidor.");
  }

  client = new textToSpeech.TextToSpeechClient({
    projectId,
    credentials: {
      client_email: clientEmail,
      private_key: privateKey
    }
  });

  return client;
}

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type, X-TTS-Token",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Cache-Control": "no-store"
  };
}

export async function OPTIONS() {
  return new Response(null, { status: 204, headers: corsHeaders() });
}

export async function GET() {
  return Response.json(
    { ok: true, service: "Leitor PDF • TTS profissional", model: "Chirp 3 HD" },
    { headers: corsHeaders() }
  );
}

export async function POST(request) {
  try {
    const expectedToken = process.env.TTS_ACCESS_TOKEN?.trim();
    if (expectedToken && request.headers.get("x-tts-token") !== expectedToken) {
      return Response.json({ error: "Não autorizado." }, { status: 401, headers: corsHeaders() });
    }

    const body = await request.json();
    const text = String(body?.text ?? "").trim();
    const voice = String(body?.voice ?? "pt-BR-Chirp3-HD-Rasalgethi").trim();
    const rate = Number(body?.rate ?? 1);

    if (!text) {
      return Response.json({ error: "Texto vazio." }, { status: 400, headers: corsHeaders() });
    }

    if (text.length > 5000) {
      return Response.json({ error: "O trecho excede o limite de 5000 caracteres." }, { status: 413, headers: corsHeaders() });
    }

    if (!allowedVoices.has(voice)) {
      return Response.json({ error: "Voz não permitida." }, { status: 400, headers: corsHeaders() });
    }

    const speakingRate = Math.min(2, Math.max(0.25, Number.isFinite(rate) ? rate : 1));

    const [response] = await getClient().synthesizeSpeech({
      input: { text },
      voice: {
        languageCode: "pt-BR",
        name: voice
      },
      audioConfig: {
        audioEncoding: "MP3",
        speakingRate
      }
    });

    const audio = Buffer.from(response.audioContent ?? []);

    if (!audio.length) {
      throw new Error("O Google Cloud não retornou áudio.");
    }

    return new Response(audio, {
      status: 200,
      headers: {
        ...corsHeaders(),
        "Content-Type": "audio/mpeg",
        "Content-Length": String(audio.length)
      }
    });
  } catch (error) {
    console.error("TTS error:", error);
    return Response.json(
      { error: error instanceof Error ? error.message : "Erro interno no TTS." },
      { status: 500, headers: corsHeaders() }
    );
  }
}
