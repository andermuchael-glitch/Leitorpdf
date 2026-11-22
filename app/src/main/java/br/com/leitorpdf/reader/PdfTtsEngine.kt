package br.com.leitorpdf.reader

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class PdfTtsEngine(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pages = mutableListOf<String>()
    private var currentPageIndex = 0
    private var speed = 1.0f
    private var onHighlight: ((String) -> Unit)? = null
    private var onPageChanged: ((Int) -> Unit)? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentSpeed = MutableStateFlow(1.0f)
    val currentSpeed: StateFlow<Float> = _currentSpeed.asStateFlow()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("pt", "BR"))
                ready = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ready) {
                    tts?.setLanguage(Locale("pt"))
                    ready = true
                }
                tts?.setSpeechRate(speed)
                setupListener()
            }
        }
    }

    private fun setupListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
                utteranceId?.let { id ->
                    val page = id.toIntOrNull() ?: return
                    val text = pages.getOrNull(page) ?: return
                    onHighlight?.invoke(text.take(120))
                }
            }

            override fun onDone(utteranceId: String?) {
                val page = utteranceId?.toIntOrNull() ?: return
                if (page + 1 < pages.size && _isSpeaking.value) {
                    speakPage(page + 1)
                } else {
                    _isSpeaking.value = false
                    onHighlight?.invoke("")
                }
            }

            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                onHighlight?.invoke("")
            }
        })
    }

    fun setPages(newPages: List<String>, startPage: Int = 0) {
        pages.clear()
        pages.addAll(newPages)
        currentPageIndex = startPage.coerceIn(0, pages.size.coerceAtLeast(1) - 1)
    }

    fun setOnHighlight(callback: (String) -> Unit) {
        onHighlight = callback
    }

    fun setOnPageChanged(callback: (Int) -> Unit) {
        onPageChanged = callback
    }

    fun playFromPage(pageIndex: Int) {
        if (!ready || pages.isEmpty()) return
        stop()
        currentPageIndex = pageIndex.coerceIn(0, pages.lastIndex)
        speakPage(currentPageIndex)
    }

    fun pause() {
        tts?.stop()
        _isSpeaking.value = false
        onHighlight?.invoke("")
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
        onHighlight?.invoke("")
    }

    fun setSpeed(value: Float) {
        speed = value.coerceIn(0.5f, 2.0f)
        _currentSpeed.value = speed
        tts?.setSpeechRate(speed)
    }

    private fun speakPage(index: Int) {
        if (!ready || index !in pages.indices) return
        val text = pages[index].trim()
        if (text.isBlank()) {
            if (index + 1 < pages.size) speakPage(index + 1)
            return
        }
        currentPageIndex = index
        onPageChanged?.invoke(index + 1)
        val params = Bundle()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, index.toString())
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        _isSpeaking.value = false
    }
}
