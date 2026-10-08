package br.com.leitorpdf.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class LibraryBook(
    val uri: String,
    val title: String,
    val coverPath: String?,
    val page: Int = 1,
    val updatedAt: Long = System.currentTimeMillis()
)

class PdfLibraryStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("pdf_library", Context.MODE_PRIVATE)
    private val key = "books"

    fun books(): List<LibraryBook> = runCatching {
        val a = JSONArray(prefs.getString(key, "[]"))
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(
                    LibraryBook(
                        uri = o.optString("uri"),
                        title = o.optString("title", "Documento PDF"),
                        coverPath = o.optString("coverPath").takeIf { it.isNotBlank() },
                        page = o.optInt("page", 1).coerceAtLeast(1),
                        updatedAt = o.optLong("updatedAt", 0L)
                    )
                )
            }
        }.sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    fun addOrUpdate(book: LibraryBook) {
        val list = books().filterNot { it.uri == book.uri }.toMutableList()
        list.add(book)
        save(list.sortedByDescending { it.updatedAt }.take(100))
    }

    fun updatePage(uri: String, page: Int) {
        val current = books().firstOrNull { it.uri == uri } ?: return
        addOrUpdate(current.copy(page = page, updatedAt = System.currentTimeMillis()))
    }

    private fun save(list: List<LibraryBook>) {
        val a = JSONArray()
        list.forEach { b ->
            a.put(JSONObject().apply {
                put("uri", b.uri)
                put("title", b.title)
                put("coverPath", b.coverPath ?: "")
                put("page", b.page)
                put("updatedAt", b.updatedAt)
            })
        }
        prefs.edit().putString(key, a.toString()).apply()
    }

    companion object {
        fun detectTitle(context: Context, uri: Uri, fallbackName: String): String {
            return runCatching {
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    com.tom_roush.pdfbox.pdmodel.PDDocument.load(input).use { doc ->
                        val metadataTitle = doc.documentInformation?.title?.trim()
                            ?.takeIf { it.isNotBlank() }
                        if (metadataTitle != null) return@runCatching cleanTitle(metadataTitle)

                        val filename = fallbackName
                            .substringAfterLast('/')
                            .substringBeforeLast('.', fallbackName)
                            .trim()
                        if (filename.isNotBlank() && !looksGeneric(filename)) {
                            return@runCatching cleanTitle(filename)
                        }

                        val stripper = com.tom_roush.pdfbox.text.PDFTextStripper().apply {
                            startPage = 1
                            endPage = 1
                        }
                        val firstPage = stripper.getText(doc)
                            .lineSequence()
                            .map { it.trim() }
                            .filter { it.length >= 3 }
                            .firstOrNull { !looksGeneric(it) }

                        cleanTitle(firstPage ?: "Documento PDF")
                    }
                } ?: cleanTitle(fallbackName)
            }.getOrDefault(cleanTitle(fallbackName))
        }

        fun createCover(context: Context, uri: Uri, cacheDir: File): String? {
            return runCatching {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
                pfd.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        if (renderer.pageCount <= 0) return@runCatching null
                        renderer.openPage(0).use { page ->
                            val width = 360
                            val ratio = page.height.toFloat() / page.width.toFloat()
                            val height = (width * ratio).toInt().coerceAtMost(560)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val file = File(cacheDir, "cover_\${uri.toString().hashCode()}.jpg")
                            FileOutputStream(file).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
                            }
                            bitmap.recycle()
                            file.absolutePath
                        }
                    }
                }
            }.getOrNull()
        }

        private fun looksGeneric(value: String): Boolean {
            val v = value.lowercase()
                .replace(Regex("[^a-z0-9áéíóúãõç ]"), "")
                .trim()
            return v.isBlank() || v == "documento pdf" || v == "document" ||
                v == "untitled" || v.startsWith("acc") || v.startsWith("file") ||
                v.startsWith("download")
        }

        private fun cleanTitle(value: String): String =
            value.replace(Regex("(?i)\\.(pdf)$"), "")
                .replace(Regex("\\s+"), " ")
                .trim().take(100).ifBlank { "Documento PDF" }
    }
}
