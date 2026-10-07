package br.com.leitorpdf.reader

import android.app.PendingIntent
import android.content.Intent
import br.com.leitorpdf.MainActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class NeuralPlaybackService : MediaSessionService() {
    companion object { const val ACTION_PLAY_FILES = "br.com.leitorpdf.action.PLAY_FILES"; const val EXTRA_PATHS = "paths" }
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(), true
            )
        }
        mediaSession = MediaSession.Builder(this, player!!)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            ).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PLAY_FILES) {
            val paths = intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty()
            if (paths.isNotEmpty()) enqueueAndPlay(paths)
        }
        return START_STICKY
    }

    fun playFile(path: String) {
        player?.apply { setMediaItem(MediaItem.fromUri(path)); prepare(); play() }
    }

    fun enqueueAndPlay(paths: List<String>) {
        player?.apply { setMediaItems(paths.map { MediaItem.fromUri(it) }); prepare(); play() }
    }

    override fun onDestroy() {
        mediaSession?.release(); player?.release()
        mediaSession = null; player = null
        super.onDestroy()
    }
}
