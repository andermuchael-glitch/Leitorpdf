package br.com.leitorpdf.reader

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ReaderAiClient(context: Context) {
    private val prefs = context.getSharedPreferences("reader_ai", Context.MODE_PRIVATE)
    fun endpoint(): String = prefs.getString("endpoint", "").orEmpty().trim().trimEnd('/')
    fun token(): String = prefs.getString("token", "").orEmpty().trim()
    fun setEndpoint(value: String) = prefs.edit().putString("endpoint", value.trim().trimEnd('/')).apply()
    fun setToken(value: String) = prefs.edit().putString("token", value.trim()).apply()

    fun analyze(action: String, text: String, contextText: String = ""): String {
        require(endpoint().isNotBlank()) { "Configure o servidor de IA primeiro." }
        val connection = (URL(endpoint() + "/analyze").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (token().isNotBlank()) setRequestProperty("Authorization", "Bearer " + token())
        }
        val body = JSONObject()
            .put("action", action)
            .put("text", text.take(18000))
            .put("context", contextText.take(24000))
            .toString()
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val response = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (connection.responseCode !in 200..299) {
            val detail = runCatching { JSONObject(response).optString("detail") }.getOrNull().orEmpty()
            throw IllegalStateException(detail.ifBlank { "Servidor de IA: HTTP " + connection.responseCode })
        }
        val result = JSONObject(response).optString("result")
        require(result.isNotBlank()) { "A IA não retornou uma resposta." }
        return result
    }
}
