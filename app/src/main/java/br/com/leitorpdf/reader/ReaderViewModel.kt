package br.com.leitorpdf.reader

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.leitorpdf.data.pdf.PdfTextExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReaderUiState(
    val fileName: String = "",
    val uri: String = "",
    val text: String = "",
    val pageTexts: List<String> = emptyList(),
    val pageCount: Int = 0,
    val selectedPage: Int = 1,
    val isLoading: Boolean = false,
    val resumeAvailable: Boolean = false,
    val resumePage: Int = 1,
    val highlights: List<ReadingHighlight> = emptyList(),
    val bookmarks: List<Int> = emptyList(),
    val error: String? = null
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val extractor = PdfTextExtractor(application)
    private val prefs =
        application.getSharedPreferences("reading_progress", Context.MODE_PRIVATE)
    private val annotations = ReadingAnnotationStore(application)

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    fun openPdf(uri: Uri, name: String) {
        val uriString = uri.toString()
        val savedUri = prefs.getString("uri", null)
        val lastUri = prefs.getString("last_uri", null)
        val same = savedUri == uriString || lastUri == uriString
        val savedPage = prefs.getInt("page", 1).coerceAtLeast(1)
        val available = prefs.getBoolean("available", false)

        prefs.edit()
            .putString("last_uri", uriString)
            .putString("last_name", name)
            .apply()

        _state.value = _state.value.copy(
            fileName = name,
            uri = uriString,
            isLoading = true,
            error = null,
            resumeAvailable = same && available,
            resumePage = savedPage,
            highlights = annotations.highlights(uriString),
            bookmarks = annotations.bookmarks(uriString)
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
                    persistPage(start)
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
        _state.value = _state.value.copy(selectedPage = safe)
        persistPage(safe)
    }

    fun nextPage() = setSelectedPage(_state.value.selectedPage + 1)
    fun previousPage() = setSelectedPage(_state.value.selectedPage - 1)

    fun toggleBookmark() {
        val c = _state.value
        if (c.uri.isBlank() || c.pageCount == 0) return
        annotations.toggleBookmark(c.uri, c.selectedPage)
        _state.value = c.copy(bookmarks = annotations.bookmarks(c.uri))
    }

    fun isCurrentPageBookmarked(): Boolean =
        annotations.isPageBookmarked(_state.value.uri, _state.value.selectedPage)

    fun addHighlight(text: String, color: String = "yellow") {
        val c = _state.value
        val clean = text.trim()
        if (c.uri.isBlank() || clean.isBlank()) return
        annotations.addHighlight(c.uri, c.selectedPage, clean, color)
        _state.value = c.copy(highlights = annotations.highlights(c.uri))
    }

    fun removeHighlight(id: Long) {
        val c = _state.value
        annotations.removeHighlight(c.uri, id)
        _state.value = c.copy(highlights = annotations.highlights(c.uri))
    }

    fun highlightsForPage(page: Int = _state.value.selectedPage): List<ReadingHighlight> =
        _state.value.highlights.filter { it.page == page }

    private fun persistPage(page: Int) {
        prefs.edit()
            .putString("uri", _state.value.uri)
            .putInt("page", page)
            .putBoolean("available", true)
            .apply()
    }
}
