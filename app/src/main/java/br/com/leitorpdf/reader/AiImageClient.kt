package br.com.leitorpdf.reader

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AiImageClient(context: Context) {
    private val prefs =
        context.getSharedPreferences("reader_ai", Context.MODE_PRIVATE)

    fun endpoint(): String = prefs.getString("endpoint", "").orEmpty().trim()
    fun token(): String = prefs.getString("token", "").orEmpty()

    fun setEndpoint(value: String) =
        prefs.edit().putString("endpoint", value.trim().trimEnd('/')).apply()

    fun setToken(value: String) =
        prefs.edit().putString("token", value.trim()).apply()

    fun generateImage(prompt: String): File {
        val endpoint = endpoint()
        require(endpoint.isNotBlank()) { "Configure o endereço do servidor de IA primeiro." }

        val connection = (URL(endpoint + "/generate-image").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (token().isNotBlank()) {
                setRequestProperty("Authorization", "Bearer " + token())
            }
        }

        val body = JSONObject().put("prompt", prompt.take(12000)).toString()
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val responseText = (if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }).use { it?.bufferedReader()?.readText().orEmpty() }

        if (connection.responseCode !in 200..299) {
            val detail = runCatching { JSONObject(responseText).optString("detail") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            throw IllegalStateException(
                detail ?: ("O servidor de IA retornou HTTP " + connection.responseCode + ".")
            )
        }

        val json = JSONObject(responseText)
        val base64 = json.optString("imageBase64")
        require(base64.isNotBlank()) { "O servidor de IA não retornou uma imagem." }

        val bytes = Base64.decode(base64, Base64.DEFAULT)
        return File.createTempFile("leitorpdf_ai_", ".png").apply {
            writeBytes(bytes)
        }
    }
}
