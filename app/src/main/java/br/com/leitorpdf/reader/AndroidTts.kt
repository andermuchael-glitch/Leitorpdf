package br.com.leitorpdf.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

object AndroidTts {
    data class VoiceInfo(
        val name: String,
        val label: String,
        val locale: Locale,
        val requiresNetwork: Boolean
    )

    fun label(voice: Voice): String {
        val language = voice.locale.displayLanguage.replaceFirstChar { it.uppercase() }
        val country = voice.locale.displayCountry
        val network = if (voice.isNetworkConnectionRequired) "online" else "instalada"
        val quality = when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> "muito alta"
            voice.quality >= Voice.QUALITY_HIGH -> "alta"
            voice.quality >= Voice.QUALITY_NORMAL -> "normal"
            else -> "básica"
        }
        val locale = if (country.isBlank()) language else "$language ($country)"
        return "$locale • $quality • $network"
    }

    fun portugueseVoices(tts: TextToSpeech): List<VoiceInfo> {
        return tts.voices
            .asSequence()
            .filter { it.locale.language.equals("pt", ignoreCase = true) }
            .filter { !it.isNetworkConnectionRequired }
            .distinctBy { it.name }
            .sortedWith(compareBy<Voice> { it.locale.country != "BR" }.thenBy { it.name })
            .map { VoiceInfo(it.name, label(it), it.locale, it.isNetworkConnectionRequired) }
            .toList()
    }

    fun findVoice(tts: TextToSpeech, name: String?): Voice? {
        return tts.voices.firstOrNull { it.name == name }
            ?: tts.voices
                .filter { it.locale.language == "pt" && !it.isNetworkConnectionRequired }
                .sortedBy { it.locale.country != "BR" }
                .firstOrNull()
    }

    fun openSettings(context: Context) {
        val intent = android.content.Intent(android.provider.Settings.ACTION_TTS_SETTINGS)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
