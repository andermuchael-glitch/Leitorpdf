package br.com.leitorpdf.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale

class PdfSpeechService : Service(), TextToSpeech.OnInitListener {

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

        private const val CHANNEL_ID = "pdf_reading"
        private const val NOTIFICATION_ID = 365
        private const val PREFS = "reading_progress"

        private fun normalizeReferences(text: String): String {
            val regex = Regex("\\b([\\p{L}0-9]+(?:\\s+[\\p{L}0-9]+)?)\\s+(\\d+)\\.(\\d+)-(\\d+)\\b")
            return text.replace(regex) {
                "${it.groupValues[1]} ${it.groupValues[2]} do ${it.groupValues[3]} ao ${it.groupValues[4]}"
            }
        }

        private fun sentenceParts(text: String): List<Pair<String, String>> {
            val normalized = text.replace("\r\n", "\n").replace("\r", "\n").trim()
            if (normalized.isBlank()) return emptyList()
            return normalized
                .split(Regex("(?<=[.!?…])\\s+|\\n{2,}"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { original -> normalizeReferences(original) to original }
        }
    }

    private lateinit var tts: TextToSpeech
    private var ready = false
    private var pages: List<String> = emptyList()
    private var currentPage = 1
    private var currentSentence = 0
    private var rate = 1f
    private var voiceName: String? = null
    private var uri = ""
    private var fileName = "PDF"
    private var page = 1
    private var paused = false

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        tts = TextToSpeech(this, this)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                paused = false
                saveProgress(true)
                updateNotification(true)
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId?.startsWith("pdf-reader-") != true || paused) return
                val parts = currentParts()
                currentSentence++
                if (currentSentence >= parts.size) {
                    currentPage++
                    currentSentence = 0
                }
                if (currentPage > pages.size) {
                    paused = true
                    prefs.edit().putBoolean("available", false).putBoolean("playing", false).apply()
                    updateNotification(false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    saveProgress(true)
                    speakCurrent()
                }
            }

            @Suppress("DEPRECATION")
            override fun onError(utteranceId: String?) {
                paused = true
                saveProgress(false)
                updateNotification(false)
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pauseReading()
            ACTION_STOP -> stopReading()
            ACTION_PLAY -> {
                val incomingPages = intent.getStringArrayListExtra(EXTRA_PAGES)
                if (!incomingPages.isNullOrEmpty()) {
                    pages = incomingPages
                    uri = intent.getStringExtra(EXTRA_URI).orEmpty()
                    fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "PDF"
                    rate = intent.getFloatExtra(EXTRA_RATE, 1f).coerceIn(0.5f, 2f)
                    voiceName = intent.getStringExtra(EXTRA_VOICE)
                    currentPage = intent.getIntExtra(EXTRA_PAGE, 1).coerceIn(1, pages.size)
                    currentSentence = intent.getIntExtra(EXTRA_CHUNK, 0).coerceAtLeast(0)
                }
                paused = false
                ensureForeground()
                saveProgress(true)
                if (ready) speakCurrent()
            }
        }
        return START_STICKY
    }

    private fun currentParts(): List<Pair<String, String>> =
        pages.getOrNull(currentPage - 1)?.let(::sentenceParts).orEmpty()

    private fun speakCurrent() {
        if (!ready || paused || pages.isEmpty() || currentPage > pages.size) return
        val parts = currentParts()
        if (currentSentence >= parts.size) {
            currentPage++
            currentSentence = 0
            if (currentPage > pages.size) return
            speakCurrent()
            return
        }

        val (spoken, original) = parts[currentSentence]
        tts.stop()
        tts.setSpeechRate(rate)

        val selected = voiceName?.let { wanted -> tts.voices?.firstOrNull { it.name == wanted } }
        if (selected != null) {
            val languageResult = tts.setLanguage(selected.locale)
            if (languageResult != TextToSpeech.LANG_MISSING_DATA &&
                languageResult != TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                // setLanguage pode trocar a voz; por isso a voz escolhida
                // precisa ser aplicada por ultimo.
                tts.voice = selected
            } else {
                tts.setLanguage(Locale("pt", "BR"))
            }
        } else {
            val result = tts.setLanguage(java.util.Locale("pt", "BR"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                paused = true
                saveProgress(false)
                updateNotification(false)
                return
            }
        }

        prefs.edit()
            .putInt("current_page", currentPage)
            .putInt("current_sentence", currentSentence)
            .putString("highlight_text", original)
            .putBoolean("playing", true)
            .apply()

        tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "pdf-reader-$currentPage-$currentSentence")
        updateNotification(true)
    }

    private fun pauseReading() {
        tts.stop()
        paused = true
        saveProgress(false)
        updateNotification(false)
    }

    private fun stopReading() {
        tts.stop()
        paused = true
        saveProgress(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun saveProgress(playing: Boolean) {
        if (uri.isBlank()) return
        prefs.edit()
            .putString("uri", uri)
            .putInt("page", currentPage)
            .putInt("chunk", currentSentence)
            .putInt("current_page", currentPage)
            .putInt("current_sentence", currentSentence)
            .putFloat("rate", rate)
            .putString("voice", voiceName)
            .putBoolean("available", true)
            .putBoolean("playing", playing)
            .apply()
    }

    private fun ensureForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
    }

    private fun updateNotification(playing: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(playing))
    }

    private fun buildNotification(playing: Boolean = !paused): Notification {
        val toggle = PendingIntent.getService(
            this,
            1,
            Intent(this, PdfSpeechService::class.java).setAction(
                if (playing) ACTION_PAUSE else ACTION_PLAY
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, PdfSpeechService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Leitor PDF • " + if (playing) "Ouvindo" else "Pausado")
            .setContentText(fileName + " • página " + currentPage + " de " + pages.size)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pausar" else "Continuar",
                toggle
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", stop)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Leitura de PDF",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            if (pages.isNotEmpty() && !paused) speakCurrent()
        }
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
