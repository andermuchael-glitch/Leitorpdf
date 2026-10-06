package br.com.leitorpdf.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.view.View
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfPageView(context: Context) : View(context) {
    private var document: PDDocument? = null
    private var renderer: PDFRenderer? = null
    private var bitmap: Bitmap? = null
    private var renderJob: Job? = null
    private var highlightJob: Job? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(105, 255, 220, 0)
        style = Paint.Style.FILL
    }
    private var highlightText = ""
    private var highlightRects: List<RectF> = emptyList()
    private var textPositions: List<TextPosition> = emptyList()
    private var textSource = ""
    private var textMapping: List<Int> = emptyList()
    private var textPage = -1
    private var filterMode = 0
    private var brightness = 0f

    var pageCount: Int = 0
        private set
    var currentPage: Int = 0
        private set

    init {
        setBackgroundColor(Color.rgb(238, 240, 244))
    }

    fun open(uri: Uri, scope: CoroutineScope, onReady: (Int) -> Unit, onError: (Throwable) -> Unit) {
        closeDocument()
        renderJob = scope.launch(Dispatchers.IO) {
            try {
                PDFBoxResourceLoader.init(context.applicationContext)
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Não foi possível abrir o PDF.")
                val doc = input.use { PDDocument.load(it) }
                val pdfRenderer = PDFRenderer(doc).apply { setSubsamplingAllowed(true) }
                withContext(Dispatchers.Main) {
                    document = doc
                    renderer = pdfRenderer
                    pageCount = doc.numberOfPages
                    currentPage = 0
                    renderCurrent(scope)
                    onReady(pageCount)
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { onError(t) }
            }
        }
    }

    fun goToPage(page: Int, scope: CoroutineScope) {
        if (pageCount <= 0) return
        val target = page.coerceIn(0, pageCount - 1)
        if (currentPage == target) return
        currentPage = target
        highlightRects = emptyList()
        invalidate()
        renderCurrent(scope)
        prepareHighlightPage(scope)
    }

    fun setHighlightText(text: String, scope: CoroutineScope) {
        if (highlightText == text) return
        highlightText = text
        updateHighlightRects()
        invalidate()
        if (textPage != currentPage) prepareHighlightPage(scope)
    }

    fun setFilterMode(mode: Int) {
        filterMode = mode.coerceIn(0, 3)
        invalidate()
    }

    fun setBrightness(value: Float) {
        brightness = value.coerceIn(-0.45f, 0.45f)
        invalidate()
    }

    private fun renderCurrent(scope: CoroutineScope) {
        val pdfRenderer = renderer ?: return
        val targetPage = currentPage
        renderJob?.cancel()
        renderJob = scope.launch(Dispatchers.IO) {
            try {
                val rendered = pdfRenderer.renderImageWithDPI(targetPage, 110f)
                withContext(Dispatchers.Main) {
                    if (currentPage == targetPage) {
                        bitmap?.recycle()
                        bitmap = rendered
                        invalidate()
                    } else {
                        rendered.recycle()
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun prepareHighlightPage(scope: CoroutineScope) {
        val doc = document ?: return
        val targetPage = currentPage
        highlightJob?.cancel()
        highlightJob = scope.launch(Dispatchers.IO) {
            try {
                val positions = mutableListOf<TextPosition>()
                val stripper = object : PDFTextStripper() {
                    override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                        if (textPositions != null) positions.addAll(textPositions)
                    }
                }
                stripper.sortByPosition = true
                stripper.startPage = targetPage + 1
                stripper.endPage = targetPage + 1
                stripper.getText(doc)

                val source = StringBuilder()
                val mapping = mutableListOf<Int>()
                positions.forEachIndexed { index, position ->
                    val normalized = normalizeForMatch(position.unicode.orEmpty())
                    normalized.forEach { ch ->
                        source.append(ch)
                        mapping.add(index)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (currentPage == targetPage) {
                        textPositions = positions
                        textSource = source.toString()
                        textMapping = mapping
                        textPage = targetPage
                        updateHighlightRects()
                        invalidate()
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun updateHighlightRects() {
        if (highlightText.isBlank() || textPage != currentPage || textSource.isBlank()) {
            highlightRects = emptyList()
            return
        }

        val target = normalizeForMatch(highlightText)
        if (target.length < 3) {
            highlightRects = emptyList()
            return
        }

        val search = target.take(180)
        val start = textSource.indexOf(search)
        if (start < 0) {
            highlightRects = emptyList()
            return
        }

        val end = (start + search.length - 1).coerceAtMost(textMapping.lastIndex)
        val pageHeight = document?.getPage(currentPage)?.mediaBox?.height ?: return
        val scale = 110f / 72f

        highlightRects = (start..end)
            .map { textMapping[it] }
            .distinct()
            .mapNotNull { index ->
                val p = textPositions.getOrNull(index) ?: return@mapNotNull null
                RectF(
                    p.x * scale,
                    (pageHeight - p.y - p.height) * scale,
                    (p.x + p.width) * scale,
                    (pageHeight - p.y) * scale
                )
            }
    }

    private fun normalizeForMatch(value: String): String =
        value.lowercase().replace(Regex("""[^\p{L}\p{Nd}]+"""), "")

    private fun colorFilter(): ColorMatrixColorFilter? {
        val matrix = ColorMatrix()
        when (filterMode) {
            1 -> matrix.set(floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            2 -> matrix.setSaturation(0f)
            3 -> matrix.set(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        if (brightness != 0f) {
            val b = brightness * 255f
            val brightnessMatrix = ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, b,
                0f, 1f, 0f, 0f, b,
                0f, 0f, 1f, 0f, b,
                0f, 0f, 0f, 1f, 0f
            ))
            matrix.postConcat(brightnessMatrix)
        }
        return if (filterMode != 0 || brightness != 0f) {
            ColorMatrixColorFilter(matrix)
        } else {
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let {
            paint.colorFilter = colorFilter()
            val scale = minOf(width.toFloat() / it.width, height.toFloat() / it.height)
            val w = it.width * scale
            val h = it.height * scale
            val left = (width - w) / 2f
            val top = (height - h) / 2f
            val destination = RectF(left, top, left + w, top + h)

            canvas.drawBitmap(it, null, destination, paint)

            if (highlightRects.isNotEmpty()) {
                highlightRects.forEach { rect ->
                    canvas.drawRect(
                        left + rect.left * scale,
                        top + rect.top * scale,
                        left + rect.right * scale,
                        top + rect.bottom * scale,
                        highlightPaint
                    )
                }
            }
        }
    }

    fun closeDocument() {
        renderJob?.cancel()
        bitmap?.recycle()
        bitmap = null
        renderer = null
        document?.close()
        document = null
        highlightRects = emptyList()
        textPositions = emptyList()
        textSource = ""
        textMapping = emptyList()
        textPage = -1
        pageCount = 0
        currentPage = 0
    }

    override fun onDetachedFromWindow() {
        closeDocument()
        super.onDetachedFromWindow()
    }
}
