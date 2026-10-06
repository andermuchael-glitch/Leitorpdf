package br.com.leitorpdf.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class PdfSpeechService : Service() {
    companion object {
        const val ACTION_PLAY = "br.com.leitorpdf.PLAY"
        const val ACTION_PAUSE = "br.com.leitorpdf.PAUSE"
        const val ACTION_STOP = "br.com.leitorpdf.STOP"
        const val EXTRA_PAGES = "pages"
        const val EXTRA_URI = "uri"
        const val EXTRA_FILE_NAME = "fileName"
        const val EXTRA_RATE = "rate"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_PAGE = "page"
        const val EXTRA_CHUNK = "chunk"

        private const val CHANNEL = "pdf_reading"
        private const val ID = 365
        private const val PREFS = "reading_progress"
        private const val MAX_CACHE_FILES = 80
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private var pages = emptyList<String>()
    private var page = 1
    private var sentence = 0
    private var rate = 1f
    private var voice = KokoroLocalTts.VOICE_ALEX
    private var uri = ""
    private var fileName = "PDF"
    private var paused = false
    private var player: MediaPlayer? = null
    private var generation = 0L

    override fun onCreate() {
        super.onCreate()
        channel()
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            ACTION_PAUSE -> pause()
            ACTION_STOP -> stop()
            ACTION_PLAY -> {
                i.getStringArrayListExtra(EXTRA_PAGES)?.takeIf { it.isNotEmpty() }?.let {
                    pages = it
                    uri = i.getStringExtra(EXTRA_URI).orEmpty()
                    fileName = i.getStringExtra(EXTRA_FILE_NAME) ?: "PDF"
                    rate = i.getFloatExtra(EXTRA_RATE, 1f).coerceIn(.5f, 2f)
                    voice = i.getStringExtra(EXTRA_VOICE)
                        ?.takeIf { v -> KokoroLocalTts.voices.any { it.id == v } }
                        ?: KokoroLocalTts.VOICE_ALEX
                    page = i.getIntExtra(EXTRA_PAGE, 1).coerceIn(1, pages.size)
                    sentence = i.getIntExtra(EXTRA_CHUNK, 0).coerceAtLeast(0)
                }
                paused = false
                foreground()
                start()
            }
        }
        return START_STICKY
    }

    private fun start() {
        val g = ++generation
        scope.launch {
            try {
                if (!KokoroLocalTts.isReady(this@PdfSpeechService)) {
                    prefs.edit()
                        .putBoolean("playing", false)
                        .putBoolean("kokoro_downloading", true)
                        .putInt("kokoro_progress", 0)
                        .remove("speech_error")
                        .apply()
                    notify(false, "Baixando modelo de voz neural…")

                    withContext(Dispatchers.IO) {
                        KokoroLocalTts.prepare(this@PdfSpeechService) { progress ->
                            prefs.edit()
                                .putBoolean("kokoro_downloading", true)
                                .putInt("kokoro_progress", progress)
                                .apply()
                            notify(false, "Preparando voz neural • $progress%")
                        }
                    }
                    prefs.edit()
                        .putBoolean("kokoro_downloading", false)
                        .putInt("kokoro_progress", 100)
                        .apply()
                }

                if (g != generation || paused) return@launch
                localSegment(g, voice)
            } catch (e: Throwable) {
                if (g == generation && !paused) {
                    fail(e.message ?: "Não foi possível preparar a voz neural offline.")
                }
            }
        }
    }

    private fun localSegment(g: Long, selectedVoice: String) {
        val p = normalize() ?: return
        scope.launch {
            try {
                val file = withContext(Dispatchers.Default) {
                    val text = parts(pages[p.first - 1]).getOrNull(p.second)?.first
                        ?: return@withContext null
                    val cached = cacheFile(text, selectedVoice, rate)
                    if (cached.isFile && cached.length() > 44L) {
                        cached
                    } else {
                        KokoroLocalTts.synthesize(
                            this@PdfSpeechService,
                            text,
                            selectedVoice,
                            rate,
                            cached
                        )
                    }
                }

                if (g != generation || paused) return@launch
                if (file == null || !file.isFile) {
                    fail("Não foi possível gerar o áudio neural.")
                    return@launch
                }

                started(p.first, p.second)
                play(file, g, p)
                prefetch(p, selectedVoice, g)
            } catch (e: Throwable) {
                if (g == generation && !paused) {
                    fail(e.message ?: "Erro ao gerar a voz neural.")
                }
            }
        }
    }

    private fun prefetch(current: Pair<Int, Int>, selectedVoice: String, g: Long) {
        val next = generateSequence(
            advance(current.first, current.second)
        ) { advance(it.first, it.second) }
            .take(2)
            .toList()

        scope.launch(Dispatchers.Default) {
            for (position in next) {
                if (g != generation || paused) return@launch
                val text = parts(pages[position.first - 1])
                    .getOrNull(position.second)?.first ?: continue
                val cached = cacheFile(text, selectedVoice, rate)
                if (!cached.isFile || cached.length() <= 44L) {
                    runCatching {
                        KokoroLocalTts.synthesize(
                            this@PdfSpeechService,
                            text,
                            selectedVoice,
                            rate,
                            cached
                        )
                    }
                }
            }
            pruneCache()
        }
    }

    private fun play(file: File, g: Long, p: Pair<Int, Int>) {
        release()
        val m = MediaPlayer()
        player = m
        m.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        try {
            m.setDataSource(file.absolutePath)
        } catch (_: Throwable) {
            release()
            if (g == generation && !paused) fail("Falha ao abrir o áudio neural.")
            return
        }

        m.setOnPreparedListener {
            if (g != generation || paused) {
                release()
                return@setOnPreparedListener
            }
            m.start()
            started(p.first, p.second)
            save(true)
            notify(true)
        }

        m.setOnCompletionListener {
            if (g != generation || paused) return@setOnCompletionListener
            release()
            finished(p.first, p.second)
        }

        m.setOnErrorListener { _, _, _ ->
            if (g == generation && !paused) fail("Falha ao reproduzir o áudio neural.")
            release()
            true
        }

        try {
            m.prepareAsync()
        } catch (_: Throwable) {
            release()
            if (g == generation && !paused) fail("Falha ao preparar o áudio neural.")
        }
    }

    private fun started(p: Int, s: Int) {
        page = p
        sentence = s
        val original = parts(pages.getOrNull(p - 1).orEmpty())
            .getOrNull(s)?.second.orEmpty()

        prefs.edit()
            .putInt("current_page", p)
            .putInt("current_sentence", s)
            .putString("highlight_text", original)
            .putBoolean("playing", true)
            .putBoolean("available", true)
            .remove("speech_error")
            .apply()

        save(true)
        notify(true)
    }

    private fun finished(p: Int, s: Int) {
        if (paused) return
        val n = advance(p, s)
        if (n == null) {
            paused = true
            prefs.edit()
                .putBoolean("available", false)
                .putBoolean("playing", false)
                .apply()
            notify(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        page = n.first
        sentence = n.second
        save(true)
        scope.launch(Dispatchers.Main.immediate) {
            localSegment(generation, voice)
        }
    }

    private fun normalize(): Pair<Int, Int>? {
        var p = page.coerceIn(1, pages.size)
        var s = sentence.coerceAtLeast(0)

        while (p <= pages.size) {
            val ps = parts(pages[p - 1])
            if (s < ps.size) {
                page = p
                sentence = s
                return p to s
            }
            p++
            s = 0
        }
        return null
    }

    private fun advance(p: Int, s: Int): Pair<Int, Int>? {
        val ps = parts(pages.getOrNull(p - 1).orEmpty())
        if (s + 1 < ps.size) return p to s + 1
        return if (p + 1 <= pages.size) p + 1 to 0 else null
    }

    private fun parts(text: String): List<Pair<String, String>> {
        val normalized = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()

        if (normalized.isBlank()) return emptyList()

        val sentences = normalized
            .split(Regex("(?<=[.!?…])\\s+|\\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val result = mutableListOf<Pair<String, String>>()
        for (sentenceText in sentences) {
            if (sentenceText.length <= 280) {
                result += sentenceText to sentenceText
            } else {
                var start = 0
                while (start < sentenceText.length) {
                    var end = minOf(start + 280, sentenceText.length)
                    if (end < sentenceText.length) {
                        val breakAt = sentenceText.substring(start, end).lastIndexOf(' ')
                            .let { if (it >= 0) start + it else start }
                        if (breakAt > start + 120) end = breakAt
                    }
                    val chunk = sentenceText.substring(start, end).trim()
                    if (chunk.isNotBlank()) result += chunk to chunk
                    start = end
                }
            }
        }
        return result
    }

    private fun cacheFile(text: String, selectedVoice: String, speed: Float): File {
        val input = "$selectedVoice|$speed|$text"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dir = File(cacheDir, "kokoro_audio").apply { mkdirs() }
        return File(dir, "$digest.wav")
    }

    private fun pruneCache() {
        val dir = File(cacheDir, "kokoro_audio")
        val files = dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(MAX_CACHE_FILES).forEach { it.delete() }
    }

    private fun pause() {
        paused = true
        generation++
        release()
        save(false)
        notify(false)
    }

    private fun stop() {
        paused = true
        generation++
        release()
        save(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun release() {
        player?.let {
            runCatching { it.stop() }
            it.reset()
            it.release()
        }
        player = null
    }

    private fun save(playing: Boolean) {
        if (uri.isBlank()) return
        prefs.edit()
            .putString("uri", uri)
            .putInt("page", page)
            .putInt("chunk", sentence)
            .putInt("current_page", page)
            .putInt("current_sentence", sentence)
            .putFloat("rate", rate)
            .putString("voice", voice)
            .putBoolean("available", true)
            .putBoolean("playing", playing)
            .apply()
    }

    private fun foreground() {
        val notification = build(false, "Preparando voz neural offline…")
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this,
                ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            ServiceCompat.startForeground(this, ID, notification, 0)
        }
    }

    private fun notify(playing: Boolean, message: String? = null) {
        getSystemService(NotificationManager::class.java)
            .notify(ID, build(playing, message))
    }

    private fun build(playing: Boolean, message: String? = null): Notification {
        val toggle = PendingIntent.getService(
            this,
            1,
            Intent(this, PdfSpeechService::class.java)
                .setAction(if (playing) ACTION_PAUSE else ACTION_PLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, PdfSpeechService::class.java)
                .setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(
                "Leitor PDF • " +
                    if (playing) "Narrador neural" else "Voz neural offline"
            )
            .setContentText(
                message ?: "$fileName • página $page de \${pages.size}"
            )
            .setOngoing(playing || message != null)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                if (playing) "Pausar" else "Continuar",
                toggle
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Parar",
                stop
            )
            .build()
    }

    private fun channel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Leitura de PDF",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
    }

    private fun fail(message: String) {
        paused = true
        generation++
        release()
        prefs.edit()
            .putBoolean("playing", false)
            .putString("speech_error", message)
            .putBoolean("kokoro_downloading", false)
            .apply()
        notify(false, message)
    }

    override fun onDestroy() {
        generation++
        scope.cancel()
        release()
        KokoroLocalTts.release()
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
