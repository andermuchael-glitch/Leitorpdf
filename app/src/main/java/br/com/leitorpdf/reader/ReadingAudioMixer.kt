package br.com.leitorpdf.reader

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.DefaultGainProvider
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

@UnstableApi
object ReadingAudioMixer {
    fun export(context: Context, recording: ReadingRecording, musicVolume: Float, onComplete: (File) -> Unit, onError: (Throwable) -> Unit) {
        val music = recording.musicUri ?: run { onError(IllegalArgumentException("Esta gravação não possui trilha musical.")); return }
        val voiceFile = File(recording.filePath)
        if (!voiceFile.exists()) { onError(IllegalStateException("A gravação de voz não foi encontrada.")); return }

        val output = File(ReadingRecorderStore(context).recordingsDirectory(context), "mix_" + System.currentTimeMillis() + ".m4a")
        val voice = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(voiceFile))).build()
        val gain = GainProcessor(DefaultGainProvider.Builder(musicVolume.coerceIn(0f, 0.35f)).build())
        val musicItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(music)))
            .setEffects(Effects(listOf(gain), emptyList()))
            .build()
        val voiceSequence = EditedMediaItemSequence.withAudioFrom(listOf(voice))
        val musicSequence = EditedMediaItemSequence.withAudioFrom(listOf(musicItem)).buildUpon().setIsLooping(true).build()
        val composition = Composition.Builder(listOf(voiceSequence, musicSequence)).build()

        Transformer.Builder(context)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) { onComplete(output) }
                override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                    output.delete()
                    onError(exception)
                }
            })
            .build()
            .start(composition, output.absolutePath)
    }
}
