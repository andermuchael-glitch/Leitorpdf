package br.com.leitorpdf.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import br.com.leitorpdf.R

class ReadingRecorderService : Service() {
    companion object {
        const val ACTION_START = "br.com.leitorpdf.START_RECORDING"
        const val ACTION_PAUSE = "br.com.leitorpdf.PAUSE_RECORDING"
        const val ACTION_RESUME = "br.com.leitorpdf.RESUME_RECORDING"
        const val ACTION_STOP = "br.com.leitorpdf.STOP_RECORDING"
        const val ACTION_CANCEL = "br.com.leitorpdf.CANCEL_RECORDING"
        const val EXTRA_URI = "uri"
        const val EXTRA_BOOK = "book"
        const val EXTRA_START_PAGE = "start_page"
        const val EXTRA_MUSIC = "music_uri"
        const val EXTRA_MUSIC_VOLUME = "music_volume"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_END_PAGE = "end_page"
        const val NOTIFICATION_ID = 3651
        const val CHANNEL_ID = "reading_recording"
    }

    private var recorder: MediaRecorder? = null
    private var musicPlayer: MediaPlayer? = null
    private var startedAt = 0L
    private var accumulatedMs = 0L
    private var lastResumeAt = 0L
    private var currentFile: String? = null
    private var currentBook = ""
    private var currentUri = ""
    private var startPage = 1
    private var musicUri: String? = null
    private var musicVolume = .12f
    private var bitrate = 96000
    private var endPage = 1
    private val store by lazy { ReadingRecorderStore(applicationContext) }

    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) endPage = intent.getIntExtra(EXTRA_END_PAGE, endPage)
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording(false)
            ACTION_CANCEL -> stopRecording(true)
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (recorder != null) return
        if (Build.VERSION.SDK_INT >= 29) startForeground(
            NOTIFICATION_ID, notification("Gravação de leitura em andamento"),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ) else startForeground(NOTIFICATION_ID, notification("Gravação de leitura em andamento"))
        val dir = store.recordingsDirectory(this)
        val file = java.io.File(dir, "leitura_${System.currentTimeMillis()}.m4a")
        try {
            recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(bitrate)
                setAudioSamplingRate(44100)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            startedAt = System.currentTimeMillis()
            accumulatedMs = 0L
            lastResumeAt = startedAt
            currentFile = file.absolutePath
            currentBook = intent.getStringExtra(EXTRA_BOOK).orEmpty()
            currentUri = intent.getStringExtra(EXTRA_URI).orEmpty()
            startPage = intent.getIntExtra(EXTRA_START_PAGE, 1)
            endPage = startPage
            musicUri = intent.getStringExtra(EXTRA_MUSIC)
            musicVolume = intent.getFloatExtra(EXTRA_MUSIC_VOLUME, .12f).coerceIn(0f, .4f)
            bitrate = intent.getIntExtra(EXTRA_BITRATE, 96000).coerceIn(64000, 128000)
            startMusic()
            saveState(false)
        } catch (_: Exception) {
            recorder?.release(); recorder = null; file.delete()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); store.clearState()
        }
    }

    private fun startMusic() {
        val raw = musicUri ?: return
        runCatching {
            musicPlayer?.release()
            musicPlayer = MediaPlayer.create(this, Uri.parse(raw))
            musicPlayer?.setVolume(musicVolume, musicVolume)
            musicPlayer?.isLooping = true
            musicPlayer?.start()
        }
    }

    private fun pauseRecording() {
        if (recorder == null) return
        if (Build.VERSION.SDK_INT >= 24) runCatching { recorder?.pause() }
        accumulatedMs += System.currentTimeMillis() - lastResumeAt
        lastResumeAt = 0L
        runCatching { musicPlayer?.pause() }
        saveState(true); updateNotification("Gravação pausada")
    }

    private fun resumeRecording() {
        if (recorder == null) return
        if (Build.VERSION.SDK_INT >= 24) runCatching { recorder?.resume() }
        lastResumeAt = System.currentTimeMillis()
        runCatching { musicPlayer?.start() }
        saveState(false); updateNotification("Gravação de leitura em andamento")
    }

    private fun stopRecording(cancel: Boolean) {
        val r = recorder ?: return
        val elapsed = if (lastResumeAt > 0L) accumulatedMs + System.currentTimeMillis() - lastResumeAt else accumulatedMs
        val file = currentFile
        runCatching { r.stop() }; runCatching { r.release() }; recorder = null
        runCatching { musicPlayer?.stop() }; musicPlayer?.release(); musicPlayer = null
        if (!cancel && file != null && java.io.File(file).exists()) {
            store.add(ReadingRecording(
                System.currentTimeMillis(), "Minha leitura", currentBook.ifBlank { "Documento" },
                currentUri, startPage, endPage.coerceAtLeast(startPage), elapsed, file, musicUri
            ))
        } else file?.let { java.io.File(it).delete() }
        store.clearState(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun saveState(paused: Boolean) {
        store.saveState(RecorderStatus(
            recording = recorder != null, paused = paused, startedAt = startedAt,
            elapsedMs = accumulatedMs + if (!paused && lastResumeAt > 0L) System.currentTimeMillis() - lastResumeAt else 0L,
            filePath = currentFile, bookTitle = currentBook, startPage = startPage
        ))
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_leitorpdf)
            .setContentTitle("LeitorPDF").setContentText(text).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).build()

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Gravação de leitura", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
