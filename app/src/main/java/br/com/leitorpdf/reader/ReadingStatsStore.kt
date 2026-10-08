package br.com.leitorpdf.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ReadingProject(val id: Long, val name: String, val createdAt: Long)
data class ReadingStatsSnapshot(
    val todayMs: Long, val weekMs: Long, val monthMs: Long, val totalMs: Long,
    val streakDays: Int, val goalMinutes: Int, val documentsToday: Int,
    val recordingsToday: Int, val achievements: List<String>, val projects: List<ReadingProject>
)

class ReadingStatsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("reading_stats", Context.MODE_PRIVATE)
    private val sessionsKey = "sessions"
    private val projectsKey = "projects"
    private val goalKey = "goal_minutes"
    private val projectMapKey = "project_map"

    fun addReadingMs(ms: Long, uri: String = "", bookTitle: String = "") {
        if (ms <= 0L) return
        val now = System.currentTimeMillis()
        val a = JSONArray(prefs.getString(sessionsKey, "[]"))
        a.put(JSONObject().apply { put("at", now); put("ms", ms.coerceAtMost(3600000L)); put("uri", uri); put("book", bookTitle) })
        val cutoff = now - 180L * 24 * 60 * 60 * 1000
        val kept = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optLong("at") >= cutoff) kept.put(a.getJSONObject(i))
        prefs.edit().putString(sessionsKey, kept.toString()).apply()
    }

    fun setGoalMinutes(minutes: Int) = prefs.edit().putInt(goalKey, minutes.coerceIn(5, 600)).apply()
    fun goalMinutes(): Int = prefs.getInt(goalKey, 30)

    fun createProject(name: String) {
        val clean = name.trim().take(80)
        if (clean.isBlank()) return
        val a = JSONArray(prefs.getString(projectsKey, "[]"))
        if ((0 until a.length()).any { a.getJSONObject(it).optString("name").equals(clean, true) }) return
        a.put(JSONObject().apply { put("id", System.currentTimeMillis()); put("name", clean); put("createdAt", System.currentTimeMillis()) })
        prefs.edit().putString(projectsKey, a.toString()).apply()
    }

    fun projects(): List<ReadingProject> = runCatching {
        val a = JSONArray(prefs.getString(projectsKey, "[]"))
        buildList { for (i in 0 until a.length()) { val o = a.getJSONObject(i); add(ReadingProject(o.optLong("id"), o.optString("name"), o.optLong("createdAt"))) } }
    }.getOrDefault(emptyList())

    fun assignProject(uri: String, projectId: Long?) {
        val a = JSONObject(prefs.getString(projectMapKey, "{}"))
        if (projectId == null) a.remove(uri) else a.put(uri, projectId)
        prefs.edit().putString(projectMapKey, a.toString()).apply()
    }

    fun projectFor(uri: String): ReadingProject? {
        val id = JSONObject(prefs.getString(projectMapKey, "{}")).optLong(uri, -1L)
        return projects().firstOrNull { it.id == id }
    }

    fun snapshot(recordings: List<ReadingRecording> = emptyList()): ReadingStatsSnapshot {
        val now = System.currentTimeMillis()
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val weekStart = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val monthStart = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val sessions = runCatching {
            val a = JSONArray(prefs.getString(sessionsKey, "[]"))
            buildList { for (i in 0 until a.length()) add(a.getJSONObject(i)) }
        }.getOrDefault(emptyList())
        fun sumSince(start: Long) = sessions.filter { it.optLong("at") >= start }.sumOf { it.optLong("ms") }
        val days = sessions.map { dayKey(it.optLong("at")) }.distinct()
        var streak = 0
        var cursor = dayKey(now)
        while (days.contains(cursor)) { streak++; cursor = previousDay(cursor) }
        val todayUris = sessions.filter { it.optLong("at") >= todayStart }.map { it.optString("uri") }.filter { it.isNotBlank() }.distinct().size
        val recordingsToday = recordings.count { it.createdAt >= todayStart }
        val today = sumSince(todayStart)
        val month = sumSince(monthStart)
        return ReadingStatsSnapshot(
            today, sumSince(weekStart), month, sessions.sumOf { it.optLong("ms") },
            streak, goalMinutes(), todayUris, recordingsToday,
            achievements(today, month, streak, recordings), projects()
        )
    }

    private fun achievements(today: Long, month: Long, streak: Int, recordings: List<ReadingRecording>): List<String> {
        val all = mutableListOf<String>()
        val totalMinutes = runCatching {
            val a = JSONArray(prefs.getString(sessionsKey, "[]"))
            (0 until a.length()).sumOf { a.getJSONObject(it).optLong("ms") } / 60000
        }.getOrDefault(0L)
        if (totalMinutes >= 1) all += "Primeira leitura"
        if (recordings.isNotEmpty()) all += "Primeira gravação"
        if (totalMinutes >= 60) all += "1 hora de leitura"
        if (totalMinutes >= 600) all += "10 horas de leitura"
        if (streak >= 7) all += "7 dias seguidos"
        if (streak >= 30) all += "30 dias seguidos"
        if (recordings.sumOf { it.durationMs } >= 3600000L) all += "1 hora gravada"
        if (today / 60000 >= goalMinutes()) all += "Meta diária concluída"
        if (month / 60000 >= 10 * goalMinutes()) all += "Mês consistente"
        return all.distinct()
    }

    private fun dayKey(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
    private fun previousDay(key: String): String {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key) ?: return key
        val c = Calendar.getInstance().apply { time = d; add(Calendar.DAY_OF_YEAR, -1) }
        return dayKey(c.timeInMillis)
    }
}
