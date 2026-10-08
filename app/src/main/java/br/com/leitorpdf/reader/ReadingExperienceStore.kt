package br.com.leitorpdf.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ReadingNote(val id: Long, val page: Int, val text: String, val createdAt: Long = System.currentTimeMillis())
data class ReadingHistoryItem(val uri: String, val name: String, val page: Int, val updatedAt: Long)
data class ReadingAppearance(
    val fontSize: Float = 20f,
    val lineHeight: Float = 1.55f,
    val backgroundMode: Int = 0,
    val filterMode: Int = 0,
    val brightness: Float = 0f,
    val readingMode: Int = 2
)

class ReadingExperienceStore(context: Context) {
    private val prefs = context.getSharedPreferences("reading_experience", Context.MODE_PRIVATE)
    private fun key(uri: String, suffix: String) = uri.hashCode().toString() + "_" + suffix

    fun notes(uri: String): List<ReadingNote> = runCatching {
        val a = JSONArray(prefs.getString(key(uri, "notes"), "[]"))
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(ReadingNote(o.optLong("id"), o.optInt("page", 1), o.optString("text"), o.optLong("createdAt")))
            }
        }
    }.getOrDefault(emptyList())

    fun addNote(uri: String, page: Int, text: String) {
        val list = notes(uri).toMutableList()
        list.add(ReadingNote(System.currentTimeMillis(), page, text.trim().take(3000)))
        val a = JSONArray()
        list.takeLast(100).forEach { n ->
            a.put(JSONObject().apply {
                put("id", n.id); put("page", n.page); put("text", n.text); put("createdAt", n.createdAt)
            })
        }
        prefs.edit().putString(key(uri, "notes"), a.toString()).apply()
    }

    fun removeNote(uri: String, id: Long) {
        val a = JSONArray()
        notes(uri).filterNot { it.id == id }.forEach { n ->
            a.put(JSONObject().apply {
                put("id", n.id); put("page", n.page); put("text", n.text); put("createdAt", n.createdAt)
            })
        }
        prefs.edit().putString(key(uri, "notes"), a.toString()).apply()
    }

    fun setReadingOptions(uri: String, concentration: Boolean, twoPages: Boolean, zoom: Float, margin: Float) {
        prefs.edit()
            .putBoolean(key(uri, "concentration"), concentration)
            .putBoolean(key(uri, "two_pages"), twoPages)
            .putFloat(key(uri, "zoom"), zoom)
            .putFloat(key(uri, "margin"), margin)
            .apply()
    }

    fun setReadingAppearance(uri: String, appearance: ReadingAppearance) {
        prefs.edit()
            .putFloat(key(uri, "font_size"), appearance.fontSize)
            .putFloat(key(uri, "line_height"), appearance.lineHeight)
            .putInt(key(uri, "background_mode"), appearance.backgroundMode)
            .putInt(key(uri, "filter_mode"), appearance.filterMode)
            .putFloat(key(uri, "brightness"), appearance.brightness)
            .putInt(key(uri, "reading_mode"), appearance.readingMode)
            .apply()
    }

    fun readingAppearance(uri: String) = ReadingAppearance(
        fontSize = prefs.getFloat(key(uri, "font_size"), 20f),
        lineHeight = prefs.getFloat(key(uri, "line_height"), 1.55f),
        backgroundMode = prefs.getInt(key(uri, "background_mode"), 0),
        filterMode = prefs.getInt(key(uri, "filter_mode"), 0),
        brightness = prefs.getFloat(key(uri, "brightness"), 0f),
        readingMode = prefs.getInt(key(uri, "reading_mode"), 2)
    )

    fun concentration(uri: String) = prefs.getBoolean(key(uri, "concentration"), false)
    fun twoPages(uri: String) = prefs.getBoolean(key(uri, "two_pages"), false)
    fun zoom(uri: String) = prefs.getFloat(key(uri, "zoom"), 1f)
    fun margin(uri: String) = prefs.getFloat(key(uri, "margin"), 26f)

    fun addHistory(uri: String, name: String, page: Int) {
        val list = history().filterNot { it.uri == uri }.toMutableList()
        list.add(ReadingHistoryItem(uri, name, page, System.currentTimeMillis()))
        val a = JSONArray()
        list.sortedByDescending { it.updatedAt }.take(30).forEach { h ->
            a.put(JSONObject().apply {
                put("uri", h.uri); put("name", h.name); put("page", h.page); put("updatedAt", h.updatedAt)
            })
        }
        prefs.edit().putString("history", a.toString()).apply()
    }

    fun history(): List<ReadingHistoryItem> = runCatching {
        val a = JSONArray(prefs.getString("history", "[]"))
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(ReadingHistoryItem(o.optString("uri"), o.optString("name"), o.optInt("page", 1), o.optLong("updatedAt")))
            }
        }
    }.getOrDefault(emptyList())
}
