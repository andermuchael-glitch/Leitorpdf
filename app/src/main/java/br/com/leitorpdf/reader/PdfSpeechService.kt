package br.com.leitorpdf.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import kotlin.coroutines.resume

class PdfSpeechService : Service() {
    companion object {
        const val ACTION_PLAY = "br.com.leitorpdf.PLAY"
        const val ACTION_PAUSE = "br.com.leitorpdf.PAUSE"
        const val ACTION_STOP = "br.com.leitorpdf.STOP"
        const val ACTION_EXPORT_PAGE = "br.com.leitorpdf.EXPORT_PAGE"

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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingPlay = false
    private var pendingExport = false

    private var pages = emptyList<String>()
    private var page = 1
    private var sentence = 0
    private var rate = 1f
    private var voiceName: String? = null
    private var uri = ""
    private var fileName = "PDF"
    private var paused = false
    private var generation = 0L
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusGranted = false

    override fun onCreate() {
        super.onCreate()
        channel()

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                configureTts()
                installPlaybackListener()

                if (pendingExport) {
                    pendingExport = false
                    exportPage()
                } else if (pendingPlay) {
                    pendingPlay = false
                    beginPlayback()
                }
            } else {
                fail("O mecanismo de voz do Android não está disponível.")
            }
        }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            ACTION_PAUSE -> pause()
            ACTION_STOP -> stopReading()
            ACTION_PLAY -> {
                readExtras(i)
                paused = false
                foreground()
                if (ttsReady) beginPlayback() else pendingPlay = true
            }
            ACTION_EXPORT_PAGE -> {
                readExtras(i)
                paused = true
                tts?.stop()
                foreground()
                if (ttsReady) exportPage() else pendingExport = true
            }
        }
        return START_STICKY
    }

    private fun readExtras(i: Intent) {
        i.getStringArrayListExtra(EXTRA_PAGES)
            ?.takeIf { it.isNotEmpty() }
            ?.let { pages = it }

        uri = i.getStringExtra(EXTRA_URI).orEmpty()
        fileName = i.getStringExtra(EXTRA_FILE_NAME) ?: "PDF"
        rate = i.getFloatExtra(EXTRA_RATE, 1f).coerceIn(.5f, 2f)
        voiceName = i.getStringExtra(EXTRA_VOICE)
        page = i.getIntExtra(EXTRA_PAGE, 1).coerceIn(1, pages.size.coerceAtLeast(1))
        sentence = i.getIntExtra(EXTRA_CHUNK, 0).coerceAtLeast(0)
    }

    private fun configureTts() {
        val engine = tts ?: return
        val selected = AndroidTts.findVoice(engine, voiceName)
        if (selected != null) {
            runCatching { engine.voice = selected }
        } else {
            runCatching { engine.language = Locale("pt", "BR") }
        }
        engine.setSpeechRate(rate)
        engine.setPitch(1f)
    }

    private fun installPlaybackListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val parsed = parseUtterance(utteranceId) ?: return
                if (paused) return

                page = parsed.first
                sentence = parsed.second
                saveProgress(true)
                notifyReading()
            }

            override fun onDone(utteranceId: String?) {
                val parsed = parseUtterance(utteranceId) ?: return
                if (paused) return

                val p = parsed.first
                val s = parsed.second
                val currentParts = parts(pages.getOrNull(p - 1).orEmpty())

                if (s + 1 < currentParts.size) return

                if (p < pages.size) {
                    page = p + 1
                    sentence = 0
                    scope.launch { queueCurrentPage(generation) }
                } else {
                    finishReading()
                }
            }

            override fun onError(utteranceId: String?) {
                if (!paused) fail("O mecanismo de voz encontrou um erro durante a leitura.")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (!paused) fail("O mecanismo de voz encontrou um erro durante a leitura.")
            }
        })
    }

    private fun beginPlayback() {
        if (!ttsReady || pages.isEmpty()) {
            fail("Não há texto disponível para narrar.")
            return
        }

        val g = ++generation
        paused = false
        requestAudioFocus()
        configureTts()
        tts?.stop()

        scope.launch { queueCurrentPage(g) }
    }

    private suspend fun queueCurrentPage(g: Long) {
        if (g != generation || paused || !ttsReady) return

        val currentParts = parts(pages.getOrNull(page - 1).orEmpty())
        if (currentParts.isEmpty()) {
            if (page < pages.size) {
                page++
                sentence = 0
                queueCurrentPage(g)
            } else {
                finishReading()
            }
            return
        }

        val start = sentence.coerceIn(0, currentParts.lastIndex)
        val engine = tts ?: return

        withContext(Dispatchers.Main.immediate) {
            if (g != generation || paused) return@withContext

            engine.stop()

            for (index in start..currentParts.lastIndex) {
                val result = engine.speak(
                    currentParts[index].first,
                    if (index == start) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    Bundle(),
                    utteranceId(page, index)
                )

                if (result == TextToSpeech.ERROR) {
                    fail("Não foi possível iniciar a narração.")
                    return@withContext
                }
            }
        }
    }

    private fun finishReading() {
        paused = true
        abandonAudioFocus()
        prefs.edit()
            .putBoolean("playing", false)
            .putBoolean("available", false)
            .remove("highlight_text")
            .apply()
        notify(false, "Leitura concluída")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun pause() {
        paused = true
        generation++
        tts?.stop()
        abandonAudioFocus()
        saveProgress(false)
        notify(false, "Pausado")
    }

    private fun stopReading() {
        paused = true
        generation++
        tts?.stop()
        abandonAudioFocus()
        prefs.edit()
            .putBoolean("playing", false)
            .remove("highlight_text")
            .apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun parts(text: String): List<Pair<String, String>> {
        val normalized = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()

        if (normalized.isBlank()) return emptyList()

        val blocks = normalized
            .split(Regex("(?<=[.!?…])\\s+|\\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val result = mutableListOf<Pair<String, String>>()

        for (original in blocks) {
            if (original.length <= 320) {
                result += normalizeReferences(original) to original
                continue
            }

            var start = 0
            while (start < original.length) {
                var end = minOf(start + 320, original.length)
                if (end < original.length) {
                    val localBreak = original.substring(start, end).lastIndexOf(' ')
                    if (localBreak > 120) end = start + localBreak
                }

                val piece = original.substring(start, end).trim()
                if (piece.isNotBlank()) {
                    result += normalizeReferences(piece) to piece
                }
                start = end
            }
        }

        return result
    }

    private fun normalizeReferences(text: String): String {
        val regex = Regex("\\b([\\p{L}0-9]+(?:\\s+[\\p{L}0-9]+)?)\\s+(\\d+)\\.(\\d+)-(\\d+)\\b")
        return text.replace(regex) {
            "${it.groupValues[1]} ${it.groupValues[2]} do ${it.groupValues[3]} ao ${it.groupValues[4]}"
        }
    }

    private fun utteranceId(p: Int, s: Int): String = "pdf:$p:$s"

    private fun parseUtterance(id: String?): Pair<Int, Int>? {
        val pieces = id?.split(":") ?: return null
        if (pieces.size != 3 || pieces[0] != "pdf") return null

        val p = pieces[1].toIntOrNull() ?: return null
        val s = pieces[2].toIntOrNull() ?: return null
        return p to s
    }

    private fun saveProgress(playing: Boolean) {
        if (uri.isBlank()) return

        val original = parts(pages.getOrNull(page - 1).orEmpty())
            .getOrNull(sentence)?.second.orEmpty()

        prefs.edit()
            .putString("uri", uri)
            .putInt("page", page)
            .putInt("chunk", sentence)
            .putInt("current_page", page)
            .putInt("current_sentence", sentence)
            .putFloat("rate", rate)
            .putString("voice", voiceName)
            .putBoolean("available", true)
            .putBoolean("playing", playing)
            .putString("highlight_text", if (playing) original else "")
            .apply()
    }

    private fun exportPage() {
        if (!ttsReady || pages.isEmpty()) {
            finishExport("Não foi possível iniciar a exportação.")
            return
        }

        val targetPage = page.coerceIn(1, pages.size)
        val targetParts = parts(pages[targetPage - 1])

        if (targetParts.isEmpty()) {
            finishExport("Esta página não possui texto para narrar.")
            return
        }

        generation++
        tts?.stop()
        val g = generation

        scope.launch(Dispatchers.IO) {
            try {
                val tempDir = File(cacheDir, "tts_export").apply { mkdirs() }
                var done = 0

                for ((index, pair) in targetParts.withIndex()) {
                    if (g != generation) return@launch

                    val temp = File(tempDir, "part_$index.wav")
                    if (temp.exists()) temp.delete()

                    val ok = synthesizeToFile(pair.first, temp)
                    if (!ok || !temp.isFile || temp.length() < 100) {
                        throw IllegalStateException("Falha ao gerar o áudio do trecho ${index + 1}.")
                    }

                    val base = sanitize(fileName.substringBeforeLast('.'))
                    val display = "${base}_pagina_${targetPage}_trecho_${index + 1}.wav"
                    saveToDownloads(temp, display)

                    done++
                    prefs.edit()
                        .putBoolean("exporting", true)
                        .putInt("export_progress", ((done * 100f) / targetParts.size).toInt())
                        .putString("export_message", "Salvando trecho $done de ${targetParts.size}…")
                        .apply()
                }

                prefs.edit()
                    .putBoolean("exporting", false)
                    .putInt("export_progress", 100)
                    .putString("export_message", "$done arquivos salvos em Downloads/LeitorPDF.")
                    .apply()

                notify(false, "$done arquivos salvos em Downloads/LeitorPDF")
            } catch (e: Throwable) {
                finishExport(e.message ?: "Não foi possível salvar os arquivos de áudio.")
            } finally {
                File(cacheDir, "tts_export").deleteRecursively()
                installPlaybackListener()
            }
        }
    }

    private suspend fun synthesizeToFile(text: String, file: File): Boolean =
        suspendCancellableCoroutine { continuation ->
            val engine = tts

            if (engine == null) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }

            val utterance = "export_${System.nanoTime()}"

            val listener = object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == utterance && continuation.isActive) {
                        continuation.resume(true)
                    }
                }

                override fun onError(utteranceId: String?) {
                    if (utteranceId == utterance && continuation.isActive) {
                        continuation.resume(false)
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == utterance && continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }

            scope.launch(Dispatchers.Main.immediate) {
                engine.setOnUtteranceProgressListener(listener)
                val result = engine.synthesizeToFile(
                    text,
                    Bundle(),
                    file,
                    utterance
                )

                if (result == TextToSpeech.ERROR && continuation.isActive) {
                    continuation.resume(false)
                }
            }

            continuation.invokeOnCancellation {
                runCatching { engine.stop() }
            }
        }

    private fun saveToDownloads(source: File, displayName: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/LeitorPDF"
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = contentResolver
            val destination = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("Não foi possível criar o arquivo em Downloads.")

            try {
                resolver.openOutputStream(destination)?.use { output ->
                    FileInputStream(source).use { input ->
                        input.copyTo(output)
                    }
                } ?: error("Não foi possível gravar o arquivo de áudio.")

                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(destination, values, null, null)
            } catch (e: Throwable) {
                resolver.delete(destination, null, null)
                throw e
            }
        } else {
            val dir = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .resolve("LeitorPDF")
                .apply { mkdirs() }

            source.copyTo(
                File(dir, displayName),
                overwrite = true
            )
        }
    }

    private fun sanitize(value: String): String =
        value
            .replace(Regex("[^A-Za-z0-9À-ÿ _-]"), "_")
            .trim()
            .ifBlank { "PDF" }

    private fun finishExport(message: String) {
        prefs.edit()
            .putBoolean("exporting", false)
            .putInt("export_progress", 0)
            .putString("export_message", message)
            .apply()

        notify(false, message)
    }

    private fun requestAudioFocus() {
        val manager = getSystemService(AudioManager::class.java) ?: return

        if (Build.VERSION.SDK_INT >= 26) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(false)
                .build()

            audioFocusRequest = request
            audioFocusGranted =
                manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            audioFocusGranted = manager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (!audioFocusGranted) return

        val manager = getSystemService(AudioManager::class.java) ?: return

        if (Build.VERSION.SDK_INT >= 26) {
            audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            manager.abandonAudioFocus(null)
        }

        audioFocusGranted = false
        audioFocusRequest = null
    }

    private fun foreground() {
        val notification = build(false, "Leitor PDF")

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

    private fun notifyReading() {
        notify(true)
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
                .setAction(if (playing) ACTION_PAUSE else ACTION_PLAY)
                .apply {
                    putExtra(EXTRA_URI, uri)
                    putExtra(EXTRA_FILE_NAME, fileName)
                    putExtra(EXTRA_RATE, rate)
                    putExtra(EXTRA_VOICE, voiceName)
                    putExtra(EXTRA_PAGE, page)
                    putExtra(EXTRA_CHUNK, sentence)
                    putStringArrayListExtra(EXTRA_PAGES, ArrayList(pages))
                },
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
            .setContentTitle("Leitor PDF • Narração")
            .setContentText(message ?: "$fileName • página $page de ${pages.size}")
            .setOngoing(playing || message != null)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
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
        tts?.stop()
        abandonAudioFocus()

        prefs.edit()
            .putBoolean("playing", false)
            .putString("speech_error", message)
            .apply()

        notify(false, message)
    }

    override fun onDestroy() {
        generation++
        paused = true
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        abandonAudioFocus()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
