package br.com.leitorpdf.reader

import android.app.Application
import android.net.Uri
import android.speech.tts.TextToSpeech
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
    val speechRate: Float = 1f,
    val error: String? = null
)

class ReaderViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {
    private val extractor = PdfTextExtractor(application)
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()
    private val tts = TextToSpeech(application, this)

    fun openPdf(uri: Uri, name: String) {
        _state.value = _state.value.copy(fileName = name, isLoading = true, error = null)
        viewModelScope.launch {
            runCatching { extractor.extract(uri) }
                .onSuccess { text ->
                    _state.value = _state.value.copy(
                        text = text,
                        isLoading = false,
                        error = if (text.isBlank()) "Este PDF parece ser escaneado. O OCR será adicionado na próxima etapa." else null
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
            tts.stop()
            _state.value = current.copy(isSpeaking = false)
            return
        }
        if (current.text.isBlank()) return
        tts.language = Locale("pt", "BR")
        tts.setSpeechRate(current.speechRate)
        tts.speak(current.text, TextToSpeech.QUEUE_FLUSH, null, "pdf-reader")
        _state.value = current.copy(isSpeaking = true)
    }

    fun setSpeechRate(rate: Float) {
        val safe = rate.coerceIn(0.5f, 2f)
        _state.value = _state.value.copy(speechRate = safe)
        tts.setSpeechRate(safe)
    }

    fun stopSpeech() {
        tts.stop()
        _state.value = _state.value.copy(isSpeaking = false)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("pt", "BR")
        }
    }

    override fun onCleared() {
        tts.stop()
        tts.shutdown()
        super.onCleared()
    }
}
