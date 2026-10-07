package br.com.leitorpdf.reader

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.leitorpdf.data.pdf.PdfTextExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    val exportingAudio: Boolean = false,
    val exportProgress: Int = 0,
    val exportMessage: String = "",
    val error: String? = null,
    val neuralVoiceReady: Boolean = false,
    val neuralBusy: Boolean = false
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val extractor = PdfTextExtractor(application)
    private val app = application
    private val neural = NeuralTtsClient(application)
    private val prefs =
        application.getSharedPreferences("reading_progress", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        ReaderUiState(
            selectedVoice = prefs.getString("voice", null),
            speechRate = prefs.getFloat("rate", 1f).coerceIn(.5f, 2f)
        )
    )

    private lateinit var tts: TextToSpeech

    init {
        _state.value = _state.value.copy(neuralVoiceReady = neural.voiceId() != null)
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val voices = AndroidTts.portugueseVoices(tts)
                val saved = prefs.getString("voice", null)
                val selected = voices.firstOrNull { it.name == saved }?.name
                    ?: voices.firstOrNull()?.name

                _state.value = _state.value.copy(
                    speechReady = true,
                    voices = voices.map { VoiceOption(it.name, it.label) },
                    selectedVoice = selected,
                    error = if (voices.isEmpty()) {
                        "Nenhuma voz em português está instalada. Toque em «Gerenciar vozes» para baixar uma."
                    } else null
                )

                if (selected != null) {
                    prefs.edit().putString("voice", selected).apply()
                }
            } else {
                _state.value = _state.value.copy(
                    speechReady = false,
                    error = "O mecanismo de voz do Android não está disponível."
                )
            }
        }
    }

    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    fun openPdf(uri: Uri, name: String) {
        val savedUri = prefs.getString("uri", null)
        val lastUri = prefs.getString("last_uri", null)
        val same = savedUri == uri.toString() || lastUri == uri.toString()
        val savedPage = prefs.getInt("page", 1).coerceAtLeast(1)
        val available = prefs.getBoolean("available", false)

        prefs.edit()
            .putString("last_uri", uri.toString())
            .putString("last_name", name)
            .apply()

        _state.value = _state.value.copy(
            fileName = name,
            uri = uri.toString(),
            isLoading = true,
            error = null,
            resumeAvailable = same && available,
            resumePage = savedPage,
            highlightText = if (same) prefs.getString("highlight_text", "").orEmpty() else ""
        )

        viewModelScope.launch {
            runCatching { extractor.extractPages(uri) }
                .onSuccess { pages ->
                    val total = pages.size.coerceAtLeast(1)
                    val start = if (same) savedPage.coerceIn(1, total) else 1
                    _state.value = _state.value.copy(
                        pageTexts = pages,
                        pageCount = pages.size,
                        selectedPage = start,
                        text = pages.joinToString("\n\n").trim(),
                        isLoading = false,
                        error = if (pages.joinToString("").isBlank())
                            "Este PDF parece ser escaneado. O OCR será adicionado depois."
                        else null
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
        val safe = page.coerceIn(1, count)
        _state.value = _state.value.copy(
            selectedPage = safe,
            highlightText = if (_state.value.isSpeaking) _state.value.highlightText else ""
        )
    }

    fun nextPage() = setSelectedPage(_state.value.selectedPage + 1)
    fun previousPage() = setSelectedPage(_state.value.selectedPage - 1)

    fun selectVoice(name: String) {
        if (_state.value.voices.none { it.name == name }) return
        _state.value = _state.value.copy(selectedVoice = name)
        prefs.edit().putString("voice", name).apply()

        if (_state.value.isSpeaking) {
            startService(
                prefs.getInt("current_page", _state.value.selectedPage),
                prefs.getInt("current_sentence", 0)
            )
        }
    }

    fun toggleSpeech() {
        val c = _state.value
        if (neural.endpoint().isNotBlank() && neural.voiceId() != null) {
            if (c.isSpeaking) { pauseNeural(); return }
            playNeuralPage(c.selectedPage)
            return
        }
        if (!c.speechReady) {
            _state.value = c.copy(
                error = "A voz do Android ainda não está pronta. Abra «Gerenciar vozes»."
            )
            return
        }

        if (c.isSpeaking) {
            pauseSpeech()
            return
        }

        val savedPage = prefs.getInt("page", c.selectedPage)
        val sameDocument = prefs.getString("uri", null) == c.uri
        if (sameDocument && c.selectedPage == savedPage && c.resumeAvailable) {
            continueReading()
        } else {
            startReadingFromPage(c.selectedPage)
        }
    }

    fun configureNeuralEndpoint(endpoint: String) { neural.setEndpoint(endpoint) }

    fun neuralEndpoint(): String = neural.endpoint()

    fun importNeuralVoice(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(neuralBusy = true, error = null)
            runCatching {
                val file = java.io.File(app.cacheDir, "voice_reference.wav")
                app.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                    ?: error("Não foi possível ler o áudio selecionado.")
                neural.uploadVoice(file, "Minha voz")
            }.onSuccess { id ->
                neural.setVoiceId(id)
                _state.value = _state.value.copy(neuralVoiceReady = true, neuralBusy = false, error = "Voz clonada configurada. Toque em ouvir.")
            }.onFailure { e ->
                _state.value = _state.value.copy(neuralBusy = false, error = e.message ?: "Falha ao enviar a voz.")
            }
        }
    }

    private fun playNeuralPage(page: Int) {
        val text = _state.value.pageTexts.getOrNull(page - 1).orEmpty()
        val voice = neural.voiceId() ?: return
        if (text.isBlank()) { _state.value = _state.value.copy(error = "Esta página não possui texto."); return }
        viewModelScope.launch {
            _state.value = _state.value.copy(neuralBusy = true, error = null)
            runCatching {
                val paths = NeuralTtsClient.chunkText(text).map { neural.synthesize(it, voice).absolutePath }
                require(paths.isNotEmpty()) { "Não foi possível gerar o áudio." }
                val intent = Intent(app, NeuralPlaybackService::class.java).apply {
                    action = NeuralPlaybackService.ACTION_PLAY_FILES
                    putStringArrayListExtra(NeuralPlaybackService.EXTRA_PATHS, ArrayList(paths))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(app, intent) else app.startService(intent)
            }.onSuccess {
                _state.value = _state.value.copy(isSpeaking = true, neuralBusy = false)
            }.onFailure { e ->
                _state.value = _state.value.copy(neuralBusy = false, isSpeaking = false, error = e.message ?: "Falha ao gerar a voz neural.")
            }
        }
    }

    private fun pauseNeural() {
        app.startService(Intent(app, NeuralPlaybackService::class.java).setAction("androidx.media3.session.action.MEDIA3_PLAY_PAUSE"))
        _state.value = _state.value.copy(isSpeaking = false)
    }

    fun startReadingFromPage(page: Int) {
        if (_state.value.pageTexts.isEmpty()) return
        val p = page.coerceIn(1, _state.value.pageTexts.size)
        setSelectedPage(p)
        startService(p, 0)
    }

    fun continueReading() {
        val c = _state.value
        if (c.pageTexts.isEmpty()) return
        if (prefs.getString("uri", null) != c.uri) {
            startReadingFromPage(c.selectedPage)
            return
        }
        val p = prefs.getInt("page", c.selectedPage).coerceIn(1, c.pageTexts.size)
        val s = prefs.getInt("chunk", 0).coerceAtLeast(0)
        setSelectedPage(p)
        startService(p, s)
    }

    private fun startService(page: Int, chunk: Int) {
        val c = _state.value
        if (c.pageTexts.isEmpty()) return

        val voice = c.selectedVoice ?: c.voices.firstOrNull()?.name
        val i = Intent(app, PdfSpeechService::class.java).apply {
            action = PdfSpeechService.ACTION_PLAY
            putStringArrayListExtra(
                PdfSpeechService.EXTRA_PAGES,
                ArrayList(c.pageTexts)
            )
            putExtra(PdfSpeechService.EXTRA_URI, c.uri)
            putExtra(PdfSpeechService.EXTRA_FILE_NAME, c.fileName)
            putExtra(PdfSpeechService.EXTRA_RATE, c.speechRate)
            putExtra(PdfSpeechService.EXTRA_VOICE, voice)
            putExtra(PdfSpeechService.EXTRA_PAGE, page)
            putExtra(PdfSpeechService.EXTRA_CHUNK, chunk)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(app, i)
        } else {
            app.startService(i)
        }

        _state.value = c.copy(
            isSpeaking = true,
            error = null,
            selectedPage = page
        )
    }

    fun exportCurrentPageAudio() {
        val c = _state.value
        if (!c.speechReady || c.pageTexts.isEmpty() || c.exportingAudio) return

        val i = Intent(app, PdfSpeechService::class.java).apply {
            action = PdfSpeechService.ACTION_EXPORT_PAGE
            putStringArrayListExtra(PdfSpeechService.EXTRA_PAGES, ArrayList(c.pageTexts))
            putExtra(PdfSpeechService.EXTRA_URI, c.uri)
            putExtra(PdfSpeechService.EXTRA_FILE_NAME, c.fileName)
            putExtra(PdfSpeechService.EXTRA_RATE, c.speechRate)
            putExtra(PdfSpeechService.EXTRA_VOICE, c.selectedVoice)
            putExtra(PdfSpeechService.EXTRA_PAGE, c.selectedPage)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(app, i)
        } else {
            app.startService(i)
        }

        _state.value = c.copy(
            exportingAudio = true,
            exportProgress = 0,
            exportMessage = "Preparando os arquivos de áudio…"
        )
    }

    fun syncPlayback() {
        val c = _state.value
        if (c.uri.isBlank()) return

        val playing = prefs.getBoolean("playing", false)
        val page = prefs.getInt("current_page", c.selectedPage)
            .coerceIn(1, c.pageCount.coerceAtLeast(1))
        val h = prefs.getString("highlight_text", "").orEmpty()
        val nh = if (playing) h else c.highlightText
        val speechError = prefs.getString("speech_error", null)
        val exporting = prefs.getBoolean("exporting", false)
        val progress = prefs.getInt("export_progress", 0)
        val message = prefs.getString("export_message", "").orEmpty()

        if (
            c.isSpeaking != playing ||
            c.selectedPage != page ||
            c.highlightText != nh ||
            c.error != speechError ||
            c.exportingAudio != exporting ||
            c.exportProgress != progress ||
            c.exportMessage != message
        ) {
            _state.value = c.copy(
                isSpeaking = playing,
                selectedPage = page,
                highlightText = nh,
                resumeAvailable = prefs.getBoolean("available", c.resumeAvailable),
                exportingAudio = exporting,
                exportProgress = progress,
                exportMessage = message,
                error = speechError
            )
        }
    }

    fun pauseSpeech() {
        app.startService(
            Intent(app, PdfSpeechService::class.java)
                .setAction(PdfSpeechService.ACTION_PAUSE)
        )
        _state.value = _state.value.copy(isSpeaking = false)
    }

    fun stopSpeech() {
        app.startService(
            Intent(app, PdfSpeechService::class.java)
                .setAction(PdfSpeechService.ACTION_STOP)
        )
        _state.value = _state.value.copy(isSpeaking = false, highlightText = "")
    }

    fun setSpeechRate(rate: Float) {
        val safe = rate.coerceIn(.5f, 2f)
        _state.value = _state.value.copy(speechRate = safe)
        prefs.edit().putFloat("rate", safe).apply()
    }

    override fun onCleared() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
        super.onCleared()
    }
}
