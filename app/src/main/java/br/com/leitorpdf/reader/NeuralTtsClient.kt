package br.com.leitorpdf.reader

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class NeuralTtsClient(private val context: Context) {
    private val prefs = context.getSharedPreferences("neural_tts", Context.MODE_PRIVATE)
    fun endpoint() = prefs.getString("endpoint", "")?.trim()?.trimEnd('/').orEmpty()
    fun setEndpoint(value: String) = prefs.edit().putString("endpoint", value.trim().trimEnd('/')).apply()
    fun voiceId() = prefs.getString("voice_id", null)
    fun token() = prefs.getString("token", "")?.trim().orEmpty()
    fun setToken(value: String) = prefs.edit().putString("token", value.trim()).apply()
    fun setVoiceId(value: String) = prefs.edit().putString("voice_id", value).apply()

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        val base = endpoint()
        if (base.isBlank()) return@withContext false
        runCatching { request("$base/health").code in 200..299 }.getOrDefault(false)
    }

    suspend fun uploadVoice(audio: File, name: String): String = withContext(Dispatchers.IO) {
        val base = endpoint()
        require(base.isNotBlank()) { "Configure o endereço do servidor de voz." }
        val boundary = "----LeitorPdf${UUID.randomUUID()}"
        val c = URL("$base/voices").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 20_000; c.readTimeout = 120_000
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        token().takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        c.outputStream.use { out ->
            fun line(s: String) = out.write((s + "\r\n").toByteArray())
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"name\""); line(""); line(name)
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"audio\"; filename=\"voice.wav\"")
            line("Content-Type: audio/wav"); line("")
            audio.inputStream().use { it.copyTo(out) }
            line(""); line("--$boundary--")
        }
        val body = (c.inputStream ?: c.errorStream).use { it?.bufferedReader()?.readText().orEmpty() }
        if (c.responseCode !in 200..299) error("Servidor de voz: HTTP ${c.responseCode}")
        JSONObject(body).getString("id")
    }

    suspend fun synthesize(text: String, voiceId: String): File = withContext(Dispatchers.IO) {
        val base = endpoint()
        require(base.isNotBlank()) { "Configure o endereço do servidor de voz." }
        require(text.length <= 300) { "Trecho de áudio acima do limite de 300 caracteres." }
        val payload = JSONObject().apply {
            put("text", text); put("voice_id", voiceId); put("language_id", "pt")
            put("exaggeration", 0.5); put("temperature", 0.8); put("cfg_weight", 0.5); put("seed", 0)
        }.toString()
        val c = URL("$base/tts").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 20_000; c.readTimeout = 180_000
        c.setRequestProperty("Content-Type", "application/json")
        token().takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        c.outputStream.use { it.write(payload.toByteArray()) }
        val body = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (c.responseCode !in 200..299) error("Servidor de voz: HTTP ${c.responseCode} ${body.take(300)}")
        val audioId = JSONObject(body).getString("audio_id")
        val out = File(context.cacheDir, "neural_${UUID.randomUUID()}.wav")
        val d = URL("$base/audio/$audioId").openConnection() as HttpURLConnection
        d.connectTimeout = 20_000; d.readTimeout = 120_000
        token().takeIf { it.isNotBlank() }?.let { d.setRequestProperty("Authorization", "Bearer $it") }
        if (d.responseCode !in 200..299) error("Falha ao baixar áudio: HTTP ${d.responseCode}")
        d.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        out
    }

    private fun request(url: String): Result {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        return Result(c.responseCode)
    }
    private data class Result(val code: Int)

    companion object {
        fun chunkText(text: String, max: Int = 280): List<String> {
            val clean = text.replace("\\s+".toRegex(), " ").trim()
            if (clean.isBlank()) return emptyList()
            val result = mutableListOf<String>(); var current = ""
            for (sentence in clean.split(Regex("(?<=[.!?;:])\\s+"))) {
                if (sentence.length > max) {
                    if (current.isNotBlank()) { result += current; current = "" }
                    var rest = sentence
                    while (rest.length > max) {
                        val cut = rest.lastIndexOf(' ', max).takeIf { it > 40 } ?: max
                        result += rest.substring(0, cut).trim(); rest = rest.substring(cut).trim()
                    }
                    current = rest
                } else if (current.length + sentence.length + 1 <= max) {
                    current = if (current.isBlank()) sentence else "$current $sentence"
                } else { result += current; current = sentence }
            }
            if (current.isNotBlank()) result += current
            return result
        }
    }
}
