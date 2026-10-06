package br.com.leitorpdf.reader

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class CloudTtsClient(private val context: Context) {

    companion object {
        private const val PREFS = "reading_progress"
        private const val ENDPOINT_KEY = "cloud_tts_endpoint"
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    fun endpoint(): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ENDPOINT_KEY, null)
            ?.trim()
            .orEmpty()

        return saved.ifBlank { br.com.leitorpdf.BuildConfig.TTS_ENDPOINT.trim() }
            .trimEnd('/')
    }

    fun saveEndpoint(value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ENDPOINT_KEY, value.trim().trimEnd('/'))
            .apply()
    }

    fun isConfigured(): Boolean = endpoint().isNotBlank()

    fun cacheFile(text: String, voice: String, rate: Float): File {
        val key = sha256("${voice}|${"%.2f".format(java.util.Locale.US, rate)}|${text}")
        val dir = File(context.cacheDir, "professional_tts")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${key}.mp3")
    }

    fun synthesize(text: String, voice: String, rate: Float): File? {
        if (text.isBlank() || voice.isBlank()) return null

        val cached = cacheFile(text, voice, rate)
        if (cached.exists() && cached.length() > 128) return cached

        val endpoint = endpoint()
        if (endpoint.isBlank()) return null

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "audio/mpeg")
        }

        try {
            val body = JSONObject()
                .put("text", text)
                .put("voice", voice)
                .put("rate", rate.coerceIn(0.25f, 2f))
                .toString()

            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (connection.responseCode !in 200..299) return null

            val temp = File(cached.parentFile, cached.name + ".part")
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output, 16 * 1024)
                }
            }

            if (temp.length() <= 128) {
                temp.delete()
                return null
            }

            if (cached.exists()) cached.delete()
            if (!temp.renameTo(cached)) {
                temp.copyTo(cached, overwrite = true)
                temp.delete()
            }

            return cached
        } finally {
            connection.disconnect()
        }
    }

    fun clearCache() {
        File(context.cacheDir, "professional_tts").deleteRecursively()
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(
            digest,
            Base64.NO_WRAP or Base64.URL_SAFE
        ).replace("=", "")
    }
}
