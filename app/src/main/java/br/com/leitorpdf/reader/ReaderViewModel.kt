package br.com.leitorpdf.reader

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.leitorpdf.data.pdf.PdfTextExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class VoiceOption(
    val name: String,
    val label: String
)

data class ReaderUiState(
    val fileName: String = "",
    val uri: String = "",
    val text: String = "",
    val pageTexts: List<String> = emptyList(),
    val pageCount: Int = 0,
    val selectedPage: Int = 1,
    val isLoading: Boolean = false,
    val isSpeaking: Boolean = false,
    val speechReady: Boolean = false,
    val speechRate: Float = 1f,
    val voices: List<VoiceOption> = emptyList(),
    val selectedVoice: String? = null,
    val resumeAvailable: Boolean = false,
    val resumePage: Int = 1,
    val highlightText: String = "",
    val error: String? = null
)

class ReaderViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {
    private val extractor = PdfTextExtractor(application)
    private val app = application
    private val prefs = application.getSharedPreferences("reading_progress", Context.MODE_PRIVATE)

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
    }

    fun openPdf(uri: Uri, name: String) {
        val savedUri = prefs.getString("uri", null)
        val sameDocument = savedUri == uri.toString()
        val savedPage = prefs.getInt("page", 1).coerceAtLeast(1)
        val savedChunk = prefs.getInt("chunk", 0)
        val savedAvailable = prefs.getBoolean("available", false)

        _state.value = _state.value.copy(
            fileName = name,
            uri = uri.toString(),
            isLoading = true,
            error = null,
            resumeAvailable = sameDocument && savedAvailable,
            resumePage = savedPage,
            highlightText = if (sameDocument) prefs.getString("highlight_text", "").orEmpty() else ""
        )

        viewModelScope.launch {
            runCatching { extractor.extractPages(uri) }
                .onSuccess { pages ->
                    val total = pages.size.coerceAtLeast(1)
                    val startPage = if (sameDocument) savedPage.coerceIn(1, total) else 1
                    _state.value = _state.value.copy(
                        pageTexts = pages,
                        pageCount = pages.size,
                        selectedPage = startPage,
                        text = pages.joinToString("\n\n").trim(),
                        isLoading = false,
                        error = if (pages.joinToString("").isBlank()) {
                            "Este PDF parece ser escaneado. O OCR será adicionado na próxima etapa."
                        } else null
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

    fun setSelectedPage(page: Int) {
        val count = _state.value.pageCount
        if (count <= 0) return
        _state.value = _state.value.copy(selectedPage = page.coerceIn(1, count), highlightText = if (_state.value.isSpeaking) _state.value.highlightText else "")
    }

    fun availableVoices(): List<VoiceOption> = _state.value.voices

    fun selectVoice(name: String?) {
        _state.value = _state.value.copy(selectedVoice = name)
        prefs.edit().putString("voice", name).apply()
        if (_state.value.isSpeaking) {
            val page = prefs.getInt("current_page", _state.value.selectedPage)
            val sentence = prefs.getInt("current_sentence", 0)
            startService(page, sentence)
        }
    }

    fun toggleSpeech() {
        val current = _state.value
        if (current.isSpeaking) {
            pauseSpeech()
        } else {
            startReadingFromPage(current.selectedPage)
        }
    }

    fun startReadingFromPage(page: Int) {
        val current = _state.value
        if (current.pageTexts.isEmpty()) return
        val safePage = page.coerceIn(1, current.pageTexts.size)
        setSelectedPage(safePage)

        val text = current.pageTexts.drop(safePage - 1).joinToString("\n\n").trim()
        startService(safePage, 0)
    }

    fun continueReading() {
        val current = _state.value
        if (current.pageTexts.isEmpty()) return
        val savedUri = prefs.getString("uri", null)
        if (savedUri != current.uri) {
            startReadingFromPage(current.selectedPage)
            return
        }

        val page = prefs.getInt("page", current.selectedPage).coerceIn(1, current.pageTexts.size)
        val chunk = prefs.getInt("chunk", 0).coerceAtLeast(0)
        val text = current.pageTexts.drop(page - 1).joinToString("\n\n").trim()
        setSelectedPage(page)
        startService(page, chunk)
    }

    private fun startService(page: Int, chunk: Int) {
        val current = _state.value
        if (current.pageTexts.isEmpty()) return

        val intent = Intent(app, PdfSpeechService::class.java).apply {
            action = PdfSpeechService.ACTION_PLAY
            putStringArrayListExtra(PdfSpeechService.EXTRA_PAGES, ArrayList(current.pageTexts))
            putExtra(PdfSpeechService.EXTRA_URI, current.uri)
            putExtra(PdfSpeechService.EXTRA_FILE_NAME, current.fileName)
            putExtra(PdfSpeechService.EXTRA_RATE, current.speechRate)
            putExtra(PdfSpeechService.EXTRA_VOICE, current.selectedVoice)
            putExtra(PdfSpeechService.EXTRA_PAGE, page)
            putExtra(PdfSpeechService.EXTRA_CHUNK, chunk)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(app, intent)
        } else {
            app.startService(intent)
        }

        _state.value = _state.value.copy(isSpeaking = true, error = null)
    }

    fun syncPlayback() {
        if (prefs.getString("uri", null) != _state.value.uri) return
        val playing = prefs.getBoolean("playing", false)
        val page = prefs.getInt("current_page", _state.value.selectedPage)
            .coerceIn(1, _state.value.pageCount.coerceAtLeast(1))
        val highlight = prefs.getString("highlight_text", "").orEmpty()
        val current = _state.value
        val newHighlight = if (playing) highlight else current.highlightText
        if (current.isSpeaking != playing || current.selectedPage != page || current.highlightText != newHighlight) {
            _state.value = current.copy(
                isSpeaking = playing,
                selectedPage = page,
                highlightText = newHighlight
            )
        }
    }

    fun pauseSpeech() {
        val intent = Intent(app, PdfSpeechService::class.java).setAction(PdfSpeechService.ACTION_PAUSE)
        app.startService(intent)
        _state.value = _state.value.copy(isSpeaking = false)
    }

    fun stopSpeech() {
        val intent = Intent(app, PdfSpeechService::class.java).setAction(PdfSpeechService.ACTION_STOP)
        app.startService(intent)
        _state.value = _state.value.copy(isSpeaking = false, highlightText = "")
    }

    fun setSpeechRate(rate: Float) {
        val safe = rate.coerceIn(0.5f, 2f)
        _state.value = _state.value.copy(speechRate = safe)
        prefs.edit().putFloat("rate", safe).apply()
        if (ttsReady) tts.setSpeechRate(safe)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false
            _state.value = _state.value.copy(
                speechReady = false,
                error = "Não foi possível iniciar o mecanismo de voz do Android."
            )
            return
        }

        ttsReady = true
        val savedVoice = prefs.getString("voice", null)
        val savedRate = prefs.getFloat("rate", 1f).coerceIn(0.5f, 2f)

        val options = tts.voices.orEmpty()
            .filter { it.locale.language == "pt" }
            .distinctBy { it.name }
            .sortedWith(compareBy<Voice>({ !it.locale.toLanguageTag().startsWith("pt-BR") }, { it.name }))
            .map { VoiceOption(it.name, voiceLabel(it)) }

        _state.value = _state.value.copy(
            speechReady = true,
            voices = options,
            selectedVoice = savedVoice?.takeIf { name -> options.any { it.name == name } },
            speechRate = savedRate,
            error = null
        )
        tts.setSpeechRate(savedRate)
    }

    private fun voiceLabel(voice: Voice): String {
        val language = voice.locale.displayLanguage.replaceFirstChar { it.uppercase() }
        val country = voice.locale.displayCountry
        val quality = if (voice.quality >= Voice.QUALITY_HIGH) "Alta qualidade" else "Padrão"
        return if (country.isBlank()) "$language • $quality" else "$language ($country) • $quality"
    }

    override fun onCleared() {
        tts.shutdown()
        super.onCleared()
    }
}
