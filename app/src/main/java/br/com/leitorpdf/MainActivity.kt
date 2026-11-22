package br.com.leitorpdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import android.graphics.BitmapFactory
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.leitorpdf.reader.ReaderViewModel
import br.com.leitorpdf.reader.PdfLibraryStore
import br.com.leitorpdf.reader.LibraryBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import br.com.leitorpdf.ui.reader.ReaderScreen
import br.com.leitorpdf.ui.reader.ReadingDashboard
import br.com.leitorpdf.ui.theme.LeitorPdfTheme

private val ListenOrange = Color(0xFFFF5A1F)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themePrefs = getSharedPreferences("app_preferences", MODE_PRIVATE)
            var darkTheme by remember { mutableStateOf(themePrefs.getBoolean("dark_theme", false)) }
            LeitorPdfTheme(darkTheme = darkTheme) {
                LeitorPdfApp(intent?.data, this, darkTheme) { darkTheme = it; themePrefs.edit().putBoolean("dark_theme", it).apply() }
            }
        }
    }
}

@Composable
private fun LeitorPdfApp(initialUri: Uri?, activity: ComponentActivity, darkTheme: Boolean, onDarkThemeChange: (Boolean) -> Unit) {
    val prefs = activity.getSharedPreferences("reading_progress", android.content.Context.MODE_PRIVATE)
    val rememberedUri = remember { initialUri ?: prefs.getString("last_uri", null)?.let(Uri::parse) }
    var selectedUri by remember { mutableStateOf(rememberedUri) }
    var selectedName by remember { mutableStateOf(if (initialUri != null) "Documento PDF" else prefs.getString("last_name", "Documento PDF") ?: "Documento PDF") }
    val readerViewModel: ReaderViewModel = viewModel()
    val libraryStore = remember { PdfLibraryStore(activity) }
    var libraryBooks by remember { mutableStateOf(libraryStore.books()) }
    var bookToDelete by remember { mutableStateOf<LibraryBook?>(null) }

    LaunchedEffect(selectedUri) {
        val uri = selectedUri ?: return@LaunchedEffect
        val metadata = withContext(Dispatchers.IO) {
            val title = PdfLibraryStore.detectTitle(activity, uri, selectedName)
            val cover = PdfLibraryStore.createCover(activity, uri, activity.cacheDir)
            title to cover
        }
        libraryStore.addOrUpdate(LibraryBook(uri.toString(), metadata.first, metadata.second, prefs.getInt("page", 1), System.currentTimeMillis()))
        libraryBooks = libraryStore.books()
        if (selectedName != metadata.first) { selectedName = metadata.first; prefs.edit().putString("last_name", metadata.first).apply() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {}
            val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Documento PDF"
            prefs.edit().putString("last_uri", uri.toString()).putString("last_name", name).apply()
            selectedUri = uri; selectedName = name
        }
    }

    if (bookToDelete != null) {
        AlertDialog(onDismissRequest = { bookToDelete = null }, title = { Text("Remover PDF da biblioteca?") }, text = { Text("O arquivo original continuará no celular.") }, confirmButton = { TextButton(onClick = { bookToDelete?.let { libraryStore.remove(it.uri); libraryBooks = libraryStore.books() }; bookToDelete = null }) { Text("Remover") } }, dismissButton = { TextButton(onClick = { bookToDelete = null }) { Text("Cancelar") } })
    }

    if (selectedUri != null) {
        ReaderScreen(selectedUri!!, selectedName, readerViewModel, { selectedUri = null }, { picker.launch(arrayOf("application/pdf")) })
    } else {
        HomeScreen(libraryBooks.firstOrNull()?.title ?: prefs.getString("last_name", null), darkTheme, onDarkThemeChange, libraryBooks.firstOrNull()?.page ?: prefs.getInt("page", 1), libraryBooks.isNotEmpty() || prefs.getString("last_uri", null) != null, libraryBooks.firstOrNull()?.coverPath, libraryBooks, { picker.launch(arrayOf("application/pdf")) }, {}, {}, {}, { prefs.getString("last_uri", null)?.let(Uri::parse)?.let { selectedUri = it; selectedName = libraryBooks.firstOrNull()?.title ?: prefs.getString("last_name", "Documento PDF") ?: "Documento PDF" } }, { selectedUri = Uri.parse(it.uri); selectedName = it.title }, { bookToDelete = it })
    }
}

@Composable
private fun HomeScreen(lastName: String?, darkTheme: Boolean, onDarkThemeChange: (Boolean) -> Unit, lastPage: Int, hasLastDocument: Boolean, lastCoverPath: String?, libraryBooks: List<LibraryBook>, onOpenPdf: () -> Unit, onOpenText: () -> Unit, onOpenWeb: () -> Unit, onScan: () -> Unit, onContinue: () -> Unit, onOpenBook: (LibraryBook) -> Unit, onDeleteBook: (LibraryBook) -> Unit) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(18.dp).verticalScroll(rememberScrollState())) {
            Text("LeitorPDF", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Leitura inteligente • PDF + IA + TTS", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            if (hasLastDocument && lastName != null) {
                Card(onClick = onContinue, Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoverThumbnail(lastCoverPath, Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)))
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(lastName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Continuar na página $lastPage", style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(progress = { 0f }, Modifier.fillMaxWidth(), color = ListenOrange)
                        }
                        Icon(Icons.Default.ArrowForward, null, tint = ListenOrange)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Minha biblioteca", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            libraryBooks.take(8).forEach { book ->
                LibraryItem(book.title, "Página ${book.page}", book.coverPath, { onOpenBook(book) }, { onDeleteBook(book) })
            }
            ReadingDashboard()
        }
    }
}

@Composable
private fun CoverThumbnail(path: String?, modifier: Modifier) {
    val bitmap = remember(path) { path?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() } }
    if (bitmap != null) Image(bitmap, "Capa", modifier) else Box(modifier.background(Color(0xFFFFE3D8)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Description, null, tint = ListenOrange) }
}

@Composable
private fun LibraryItem(title: String, subtitle: String, coverPath: String?, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onClick, Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverThumbnail(coverPath, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "Remover") }
        }
    }
}
