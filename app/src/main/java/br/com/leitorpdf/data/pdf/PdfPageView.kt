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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PdfPageView(context: Context) : View(context) {
    private var document: PDDocument? = null
    private var renderer: PDFRenderer? = null
    private var bitmap: Bitmap? = null
    private var renderJob: Job? = null
    private var highlightJob: Job? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 214, 0)
        style = Paint.Style.FILL
    }

    private var highlightText = ""
    private var highlightRects: List<RectF> = emptyList()
    private var textPositions: List<TextPosition> = emptyList()
    private var textSource = ""
    private var textMapping: List<Int> = emptyList()
    private var textPage = -1
    private var lastHighlightEnd = 0
    private var filterMode = 0
    private var brightness = 0f

    var pageCount: Int = 0
        private set
    var currentPage: Int = 0
        private set

    init {
        setBackgroundColor(Color.rgb(238, 240, 244))
        isFocusable = true
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
                    prepareHighlightPage(scope)
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
        if (currentPage == target && bitmap != null) {
            prepareHighlightPage(scope)
            return
        }

        currentPage = target
        highlightRects = emptyList()
        lastHighlightEnd = 0
        textPage = -1
        invalidate()

        renderCurrent(scope)
        prepareHighlightPage(scope)
    }

    fun setHighlightText(text: String, scope: CoroutineScope) {
        if (highlightText == text) return

        highlightText = text
        // O TTS muda o trecho com frequência. Nunca fazemos extração do PDF
        // na thread principal: somente a busca/mapeamento já preparado é usado.
        updateHighlightRects()
        invalidate()

        if (textPage != currentPage) {
            prepareHighlightPage(scope)
        }
    }

    fun setFilterMode(mode: Int) {
        filterMode = mode.coerceIn(0, 3)
        invalidate()
    }

    fun setBrightness(value: Float) {
        brightness = value.coerceIn(-0.45f, 0.45f)
        invalidate()
    }

    fun setHighlightEnabled(enabled: Boolean) {
        if (!enabled) {
            highlightText = ""
            highlightRects = emptyList()
            invalidate()
        }
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

    /**
     * Prepara o mapa visual uma vez por página.
     *
     * O PDF usa origem no canto inferior esquerdo, enquanto a View usa origem
     * no canto superior esquerdo. A ordenação explícita por linha + X evita o
     * efeito de o marca-texto começar no final da página e "subir".
     */
    private fun prepareHighlightPage(scope: CoroutineScope) {
        val doc = document ?: return
        val targetPage = currentPage

        highlightJob?.cancel()
        highlightJob = scope.launch(Dispatchers.IO) {
            try {
                val rawPositions = mutableListOf<TextPosition>()
                val stripper = object : PDFTextStripper() {
                    override fun writeString(
                        text: String?,
                        textPositions: MutableList<TextPosition>?
                    ) {
                        if (textPositions != null) rawPositions.addAll(textPositions)
                    }
                }

                stripper.sortByPosition = true
                stripper.startPage = targetPage + 1
                stripper.endPage = targetPage + 1
                stripper.getText(doc)

                val positions = rawPositions
                    .filter { it.unicode?.isNotEmpty() == true }
                    .sortedWith(
                        compareByDescending<TextPosition> { it.y }
                            .thenBy { it.x }
                    )

                // Mantemos exatamente um caractere por TextPosition no mapa.
                // A busca usa a mesma normalização sem espaços/pontuação para que
                // o texto do TTS corresponda ao texto visual do PDF.
                val source = StringBuilder()
                val mapping = mutableListOf<Int>()

                positions.forEachIndexed { index, position ->
                    position.unicode.orEmpty().forEach { ch ->
                        val normalized = normalizeForMatch(ch.toString())
                        normalized.forEach { normalizedChar ->
                            source.append(normalizedChar)
                            mapping.add(index)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (currentPage == targetPage) {
                        textPositions = positions
                        textSource = source.toString()
                        textMapping = mapping
                        textPage = targetPage
                        lastHighlightEnd = 0
                        updateHighlightRects()
                        invalidate()
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Encontra o próximo trecho em ordem de leitura e transforma os caracteres
     * encontrados em poucos retângulos por linha. Isso reduz drasticamente o
     * custo de desenho e elimina o lag causado por centenas de drawRect().
     */
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

        val searchStart = lastHighlightEnd.coerceIn(0, textSource.length)
        var start = textSource.indexOf(target, searchStart)

        // Se a extração do TTS/PDF tiver pequenas diferenças, tenta uma versão
        // mais curta sem voltar a destacar aleatoriamente o fim da página.
        if (start < 0) {
            val fallback = target.take(min(220, target.length))
            start = textSource.indexOf(fallback, searchStart)
        }

        if (start < 0 && searchStart > 0) {
            start = textSource.indexOf(target)
            if (start < 0) {
                val fallback = target.take(min(220, target.length))
                start = textSource.indexOf(fallback)
            }
        }

        if (start < 0) {
            highlightRects = emptyList()
            return
        }

        val effectiveLength = min(
            target.length,
            max(3, textSource.length - start)
        )
        val end = (start + effectiveLength - 1).coerceAtMost(textMapping.lastIndex)
        lastHighlightEnd = start + effectiveLength

        val selectedIndices = (start..end)
            .mapNotNull { textMapping.getOrNull(it) }
            .filter { it >= 0 }
            .distinct()

        if (selectedIndices.isEmpty()) {
            highlightRects = emptyList()
            return
        }

        val pageHeight = document?.getPage(currentPage)?.mediaBox?.height ?: return
        val scale = 110f / 72f

        // Agrupa caracteres próximos da mesma linha em um único retângulo.
        val rects = mutableListOf<RectF>()
        val sorted = selectedIndices.sortedWith(
            compareByDescending<Int> { textPositions[it].y }
                .thenBy { textPositions[it].x }
        )

        var current: RectF? = null
        var currentY = Float.NaN

        for (index in sorted) {
            val p = textPositions.getOrNull(index) ?: continue
            val top = (pageHeight - p.y - p.height) * scale
            val bottom = (pageHeight - p.y) * scale
            val left = p.x * scale
            val right = (p.x + p.width) * scale

            if (current == null || abs(top - currentY) > max(3f, p.height * scale * 0.7f)) {
                current?.let { rects.add(it) }
                current = RectF(left, top, right, bottom)
                currentY = top
            } else {
                current!!.left = min(current!!.left, left)
                current!!.right = max(current!!.right, right)
                current!!.top = min(current!!.top, top)
                current!!.bottom = max(current!!.bottom, bottom)
            }
        }

        current?.let { rects.add(it) }
        highlightRects = rects
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
        } else null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        bitmap?.let { image ->
            paint.colorFilter = colorFilter()

            val scale = minOf(
                width.toFloat() / image.width,
                height.toFloat() / image.height
            )
            val w = image.width * scale
            val h = image.height * scale
            val left = (width - w) / 2f
            val top = (height - h) / 2f
            val destination = RectF(left, top, left + w, top + h)

            canvas.drawBitmap(image, null, destination, paint)

            if (highlightRects.isNotEmpty()) {
                highlightRects.forEach { rect ->
                    val mapped = RectF(
                        left + rect.left * scale,
                        top + rect.top * scale,
                        left + rect.right * scale,
                        top + rect.bottom * scale
                    )
                    canvas.drawRoundRect(
                        mapped,
                        5f,
                        5f,
                        highlightPaint
                    )
                }
            }
        }
    }

    fun closeDocument() {
        renderJob?.cancel()
        highlightJob?.cancel()

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
        lastHighlightEnd = 0
        pageCount = 0
        currentPage = 0
    }

    override fun onDetachedFromWindow() {
        closeDocument()
        super.onDetachedFromWindow()
    }
}
