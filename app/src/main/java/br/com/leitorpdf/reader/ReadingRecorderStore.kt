package br.com.leitorpdf.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ReadingRecording(
    val id: Long, val title: String, val bookTitle: String, val uri: String,
    val startPage: Int, val endPage: Int, val durationMs: Long, val filePath: String,
    val musicUri: String? = null, val createdAt: Long = System.currentTimeMillis()
)

data class RecorderStatus(
    val recording: Boolean = false, val paused: Boolean = false, val startedAt: Long = 0L,
    val elapsedMs: Long = 0L, val filePath: String? = null, val bookTitle: String = "", val startPage: Int = 1
)

class ReadingRecorderStore(context: Context) {
    private val prefs = context.getSharedPreferences("reading_recorder", Context.MODE_PRIVATE)
    private val key = "recordings"
    private val stateKey = "state"

    fun recordings(): List<ReadingRecording> = runCatching {
        val a = JSONArray(prefs.getString(key, "[]"))
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(ReadingRecording(
                    o.optLong("id"), o.optString("title", "Minha leitura"),
                    o.optString("bookTitle", "Documento"), o.optString("uri"),
                    o.optInt("startPage", 1), o.optInt("endPage", 1),
                    o.optLong("durationMs", 0L), o.optString("filePath"),
                    o.optString("musicUri").takeIf { it.isNotBlank() }, o.optLong("createdAt", 0L)
                ))
            }
        }.sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    fun add(recording: ReadingRecording) {
        val a = JSONArray()
        (recordings().filterNot { it.id == recording.id } + recording).take(100).forEach { r ->
            a.put(JSONObject().apply {
                put("id", r.id); put("title", r.title); put("bookTitle", r.bookTitle); put("uri", r.uri)
                put("startPage", r.startPage); put("endPage", r.endPage); put("durationMs", r.durationMs)
                put("filePath", r.filePath); put("musicUri", r.musicUri ?: ""); put("createdAt", r.createdAt)
            })
        }
        prefs.edit().putString(key, a.toString()).apply()
    }

    fun remove(id: Long) {
        val a = JSONArray()
        recordings().filterNot { it.id == id }.forEach { r ->
            a.put(JSONObject().apply {
                put("id", r.id); put("title", r.title); put("bookTitle", r.bookTitle); put("uri", r.uri)
                put("startPage", r.startPage); put("endPage", r.endPage); put("durationMs", r.durationMs)
                put("filePath", r.filePath); put("musicUri", r.musicUri ?: ""); put("createdAt", r.createdAt)
            })
        }
        prefs.edit().putString(key, a.toString()).apply()
    }

    fun state(): RecorderStatus = runCatching {
        val o = JSONObject(prefs.getString(stateKey, "{}"))
        RecorderStatus(
            o.optBoolean("recording"), o.optBoolean("paused"), o.optLong("startedAt"),
            o.optLong("elapsedMs"), o.optString("filePath").takeIf { it.isNotBlank() },
            o.optString("bookTitle"), o.optInt("startPage", 1)
        )
    }.getOrDefault(RecorderStatus())

    fun saveState(status: RecorderStatus) {
        prefs.edit().putString(stateKey, JSONObject().apply {
            put("recording", status.recording); put("paused", status.paused)
            put("startedAt", status.startedAt); put("elapsedMs", status.elapsedMs)
            put("filePath", status.filePath ?: ""); put("bookTitle", status.bookTitle)
            put("startPage", status.startPage)
        }.toString()).apply()
    }

    fun clearState() = saveState(RecorderStatus())

    fun recordingsDirectory(context: Context): File =
        File(context.filesDir, "recordings").apply { mkdirs() }
}
