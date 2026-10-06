package br.com.leitorpdf.reader

import android.app.Application
import android.media.AudioAttributes
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.leitorpdf.data.pdf.PdfTextExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class ReaderUiState(
    val fileName: String = "",
    val text: String = "",
    val isLoading: Boolean = false,
    val isSpeaking: Boolean = false,
    val speechReady: Boolean = false,
    val speechRate: Float = 1f,
    val error: String? = null
)

class ReaderViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {
    private val extractor = PdfTextExtractor(application)
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val tts = TextToSpeech(application, this)
    private var ttsReady = false

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _state.value = _state.value.copy(isSpeaking = true)
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId?.startsWith("pdf-reader-") == true) {
                    _state.value = _state.value.copy(isSpeaking = false)
                }
            }

            @Suppress("DEPRECATION")
            override fun onError(utteranceId: String?) {
                _state.value = _state.value.copy(
                    isSpeaking = false,
                    error = "Não foi possível reproduzir o áudio. Verifique o volume de mídia e o mecanismo de voz do Android."
                )
            }
        })
    }

    fun openPdf(uri: Uri, name: String) {
        _state.value = _state.value.copy(
            fileName = name,
            isLoading = true,
            error = null
        )

        viewModelScope.launch {
            runCatching { extractor.extract(uri) }
                .onSuccess { text ->
                    _state.value = _state.value.copy(
                        text = text,
                        isLoading = false,
                        error = if (text.isBlank()) {
                            "Este PDF parece ser escaneado. O OCR será adicionado na próxima etapa."
                        } else {
                            null
                        }
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Não foi possível extrair o texto deste PDF."
                    )
                }
        }
    }

    fun toggleSpeech() {
        val current = _state.value

        if (current.isSpeaking) {
            stopSpeech()
            return
        }

        if (current.text.isBlank()) return

        if (!ttsReady) {
            _state.value = current.copy(
                error = "O mecanismo de voz ainda está iniciando. Tente novamente em alguns segundos."
            )
            return
        }

        val languageResult = tts.setLanguage(Locale("pt", "BR"))
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            _state.value = current.copy(
                error = "A voz em português não está instalada. Instale uma voz em português nas configurações de Texto para fala do Android."
            )
            return
        }

        tts.stop()
        tts.setSpeechRate(current.speechRate)

        val chunks = splitText(current.text)
        chunks.forEachIndexed { index, chunk ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(
                chunk,
                queueMode,
                null,
                "pdf-reader-$index"
            )
        }

        _state.value = current.copy(isSpeaking = true, error = null)
    }

    private fun splitText(text: String): List<String> {
        val normalized = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()

        if (normalized.length <= 3000) return listOf(normalized)

        val result = mutableListOf<String>()
        var remaining = normalized

        while (remaining.length > 3000) {
            var cut = remaining.lastIndexOf("\n", 3000)
            if (cut < 1500) cut = remaining.lastIndexOf(". ", 3000)
            if (cut < 1500) cut = 3000

            result += remaining.substring(0, cut + if (remaining[cut] == '\n') 0 else 1).trim()
            remaining = remaining.substring(cut + if (remaining[cut] == '\n') 1 else 0).trim()
        }

        if (remaining.isNotEmpty()) result += remaining
        return result
    }

    fun setSpeechRate(rate: Float) {
        val safe = rate.coerceIn(0.5f, 2f)
        _state.value = _state.value.copy(speechRate = safe)
        if (ttsReady) tts.setSpeechRate(safe)
    }

    fun stopSpeech() {
        tts.stop()
        _state.value = _state.value.copy(isSpeaking = false)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("pt", "BR"))
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED

            _state.value = _state.value.copy(
                speechReady = ttsReady,
                error = if (!ttsReady) {
                    "A voz em português não está disponível no aparelho."
                } else {
                    null
                }
            )

            if (ttsReady) {
                tts.setSpeechRate(_state.value.speechRate)
            }
        } else {
            ttsReady = false
            _state.value = _state.value.copy(
                speechReady = false,
                error = "Não foi possível iniciar o mecanismo de voz do Android."
            )
        }
    }

    override fun onCleared() {
        tts.stop()
        tts.shutdown()
        super.onCleared()
    }
}
