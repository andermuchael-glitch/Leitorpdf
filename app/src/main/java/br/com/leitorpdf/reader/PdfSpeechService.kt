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
        const val EXTRA_TEXT = "text"
        const val EXTRA_URI = "uri"
        const val EXTRA_FILE_NAME = "fileName"
        const val EXTRA_RATE = "rate"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_PAGE = "page"
        const val EXTRA_CHUNK = "chunk"

        private const val CHANNEL_ID = "pdf_reading"
        private const val NOTIFICATION_ID = 365
        private const val PREFS = "reading_progress"

        private fun chunks(text: String): List<String> {
            val normalized = text.replace("\r\n", "\n").replace("\r", "\n").trim()
            if (normalized.isEmpty()) return emptyList()
            if (normalized.length <= 3000) return listOf(normalized)

            val result = mutableListOf<String>()
            var remaining = normalized
            while (remaining.length > 3000) {
                var cut = remaining.lastIndexOf("\n", 3000)
                if (cut < 1500) cut = remaining.lastIndexOf(". ", 3000)
                if (cut < 1500) cut = 3000
                val end = if (remaining[cut] == '\n') cut else cut + 1
                result += remaining.substring(0, end).trim()
                remaining = remaining.substring(if (remaining[cut] == '\n') cut + 1 else cut).trim()
            }
            if (remaining.isNotEmpty()) result += remaining
            return result
        }
    }

    private lateinit var tts: TextToSpeech
    private var ready = false
    private var text = ""
    private var parts: List<String> = emptyList()
    private var currentChunk = 0
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
                updateNotification(true)
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId?.startsWith("pdf-reader-") != true) return
                currentChunk++
                saveProgress()
                if (currentChunk < parts.size) {
                    speakCurrent()
                } else {
                    paused = true
                    saveProgress()
                    updateNotification(false)
                }
            }

            @Suppress("DEPRECATION")
            override fun onError(utteranceId: String?) {
                paused = true
                saveProgress()
                updateNotification(false)
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pauseReading()
            ACTION_STOP -> stopReading()
            ACTION_PLAY -> {
                val incomingText = intent.getStringExtra(EXTRA_TEXT)
                if (!incomingText.isNullOrBlank()) {
                    text = incomingText
                    parts = chunks(text)
                    uri = intent.getStringExtra(EXTRA_URI).orEmpty()
                    fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "PDF"
                    rate = intent.getFloatExtra(EXTRA_RATE, 1f)
                    voiceName = intent.getStringExtra(EXTRA_VOICE)
                    page = intent.getIntExtra(EXTRA_PAGE, 1)
                    currentChunk = intent.getIntExtra(EXTRA_CHUNK, 0).coerceIn(0, (parts.size - 1).coerceAtLeast(0))
                    paused = false
                    ensureForeground()
                    if (ready) speakCurrent()
                } else if (parts.isNotEmpty()) {
                    paused = false
                    ensureForeground()
                    if (ready) speakCurrent()
                }
            }
        }
        return START_STICKY
    }

    private fun speakCurrent() {
        if (!ready || parts.isEmpty() || currentChunk >= parts.size) return

        tts.stop()
        tts.setSpeechRate(rate)

        val selected = voiceName?.let { wanted ->
            tts.voices?.firstOrNull { it.name == wanted }
        }
        if (selected != null) {
            tts.voice = selected
        } else {
            val result = tts.setLanguage(Locale("pt", "BR"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                paused = true
                updateNotification(false)
                return
            }
        }

        parts.drop(currentChunk).forEachIndexed { offset, part ->
            val mode = if (offset == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(part, mode, null, "pdf-reader-" + (currentChunk + offset))
        }
        updateNotification(true)
    }

    private fun pauseReading() {
        tts.stop()
        paused = true
        saveProgress()
        updateNotification(false)
    }

    private fun stopReading() {
        tts.stop()
        paused = true
        saveProgress()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun saveProgress() {
        if (uri.isBlank()) return
        prefs.edit()
            .putString("uri", uri)
            .putInt("page", page)
            .putInt("chunk", currentChunk.coerceAtLeast(0))
            .putFloat("rate", rate)
            .putString("voice", voiceName)
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
            .setContentText(fileName + " • página " + page)
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
            if (parts.isNotEmpty() && !paused) speakCurrent()
        }
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
