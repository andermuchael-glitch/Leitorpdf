package br.com.leitorpdf.data.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfTextExtractor(private val context: Context) {
    suspend fun extract(uri: Uri): String = extractPages(uri).joinToString("\n\n").trim()

    suspend fun extractPages(uri: Uri): List<String> = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { document ->
                val stripper = PDFTextStripper()
                (0 until document.numberOfPages).map { page ->
                    stripper.startPage = page + 1
                    stripper.endPage = page + 1
                    stripper.getText(document).trim()
                }
            }
        } ?: error("Não foi possível abrir o PDF.")
    }
}
