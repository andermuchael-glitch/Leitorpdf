package br.com.leitorpdf.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.view.View
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
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
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    var pageCount: Int = 0
        private set
    var currentPage: Int = 0
        private set

    init { setBackgroundColor(Color.rgb(238, 240, 244)) }

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

    fun nextPage(scope: CoroutineScope) {
        if (currentPage + 1 < pageCount) {
            currentPage++
            renderCurrent(scope)
        }
    }

    fun previousPage(scope: CoroutineScope) {
        if (currentPage > 0) {
            currentPage--
            renderCurrent(scope)
        }
    }

    private fun renderCurrent(scope: CoroutineScope) {
        val pdfRenderer = renderer ?: return
        renderJob?.cancel()
        renderJob = scope.launch(Dispatchers.IO) {
            try {
                val targetDpi = 110f
                val rendered = pdfRenderer.renderImageWithDPI(currentPage, targetDpi)
                withContext(Dispatchers.Main) {
                    bitmap?.recycle()
                    bitmap = rendered
                    invalidate()
                }
            } catch (_: Throwable) { }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (pageCount > 0) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let {
            val scale = minOf(width.toFloat() / it.width, height.toFloat() / it.height)
            val w = it.width * scale
            val h = it.height * scale
            val left = (width - w) / 2f
            val top = (height - h) / 2f
            canvas.drawBitmap(it, null, android.graphics.RectF(left, top, left + w, top + h), paint)
        }
    }

    fun closeDocument() {
        renderJob?.cancel()
        bitmap?.recycle()
        bitmap = null
        renderer = null
        document?.close()
        document = null
        pageCount = 0
        currentPage = 0
    }

    override fun onDetachedFromWindow() {
        closeDocument()
        super.onDetachedFromWindow()
    }
}
