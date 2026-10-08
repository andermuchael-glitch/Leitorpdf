package br.com.leitorpdf.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ReadingHighlight(
    val id: Long,
    val page: Int,
    val text: String,
    val color: String = "yellow",
    val createdAt: Long = System.currentTimeMillis()
)

class ReadingAnnotationStore(context: Context) {
    private val prefs = context.getSharedPreferences("reading_annotations", Context.MODE_PRIVATE)

    private fun key(uri: String, suffix: String): String =
        uri.hashCode().toString() + "_" + suffix

    fun highlights(uri: String): List<ReadingHighlight> {
        if (uri.isBlank()) return emptyList()
        val raw = prefs.getString(key(uri, "highlights"), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        ReadingHighlight(
                            id = o.optLong("id"),
                            page = o.optInt("page", 1),
                            text = o.optString("text"),
                            color = o.optString("color", "yellow"),
                            createdAt = o.optLong("createdAt", 0L)
                        )
                    )
                }
            }.filter { it.text.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun addHighlight(uri: String, page: Int, text: String, color: String = "yellow") {
        val clean = text.trim()
        if (uri.isBlank() || clean.isBlank()) return
        val current = highlights(uri).toMutableList()
        current.removeAll { it.page == page && it.text.equals(clean, ignoreCase = true) }
        current.add(
            ReadingHighlight(
                id = System.currentTimeMillis(),
                page = page,
                text = clean.take(1200),
                color = color
            )
        )
        saveHighlights(uri, current.takeLast(200))
    }

    fun removeHighlight(uri: String, id: Long) {
        saveHighlights(uri, highlights(uri).filterNot { it.id == id })
    }

    fun isPageBookmarked(uri: String, page: Int): Boolean =
        bookmarks(uri).contains(page)

    fun toggleBookmark(uri: String, page: Int): Boolean {
        val current = bookmarks(uri).toMutableSet()
        val added = if (current.contains(page)) {
            current.remove(page)
            false
        } else {
            current.add(page)
            true
        }
        prefs.edit().putString(
            key(uri, "bookmarks"),
            JSONArray(current.sorted()).toString()
        ).apply()
        return added
    }

    fun bookmarks(uri: String): List<Int> {
        if (uri.isBlank()) return emptyList()
        val raw = prefs.getString(key(uri, "bookmarks"), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) add(array.optInt(i))
            }.filter { it > 0 }.distinct().sorted()
        }.getOrDefault(emptyList())
    }

    private fun saveHighlights(uri: String, items: List<ReadingHighlight>) {
        val array = JSONArray()
        items.forEach { h ->
            array.put(
                JSONObject().apply {
                    put("id", h.id)
                    put("page", h.page)
                    put("text", h.text)
                    put("color", h.color)
                    put("createdAt", h.createdAt)
                }
            )
        }
        prefs.edit().putString(key(uri, "highlights"), array.toString()).apply()
    }
}
