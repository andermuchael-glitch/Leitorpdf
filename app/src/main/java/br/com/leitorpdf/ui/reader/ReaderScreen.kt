package br.com.leitorpdf.ui.reader

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import br.com.leitorpdf.data.pdf.PdfPageView
import br.com.leitorpdf.reader.ReaderViewModel
import br.com.leitorpdf.reader.PdfTtsEngine
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(uri: Uri, fileName: String, viewModel: ReaderViewModel, onBack: () -> Unit, onOpenAnotherPdf: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdfView = remember { PdfPageView(context) }
    val ttsEngine = remember { PdfTtsEngine(context) }
    val isSpeaking by ttsEngine.isSpeaking.collectAsState()
    val ttsSpeed by ttsEngine.currentSpeed.collectAsState()

    DisposableEffect(Unit) {
        ttsEngine.setOnHighlight { pdfView.setHighlightText(it, scope) }
        ttsEngine.setOnPageChanged { viewModel.setSelectedPage(it) }
        onDispose { ttsEngine.release(); pdfView.closeDocument() }
    }

    LaunchedEffect(uri) {
        viewModel.openPdf(uri, fileName)
        pdfView.open(
            uri = uri,
            scope = scope,
            onReady = { /* page count available */ },
            onError = { /* handled by ViewModel text extraction */ }
        )
    }

    LaunchedEffect(state.pageTexts) {
        if (state.pageTexts.isNotEmpty()) ttsEngine.setPages(state.pageTexts, state.selectedPage - 1)
    }

    LaunchedEffect(state.selectedPage) {
        if (state.pageCount > 0) pdfView.goToPage(state.selectedPage - 1, scope)
    }

    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { pdfView }, modifier = Modifier.fillMaxSize())

        if (state.isLoading) Text("Preparando PDF…", Modifier.align(Alignment.Center), color = Color.White)

        Surface(Modifier.align(Alignment.TopCenter).padding(8.dp), RoundedCornerShape(20.dp), Color.Black.copy(0.6f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Voltar", tint = Color.White) }
                Text("P. ${state.selectedPage}/${state.pageCount}", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
                IconButton(onClick = {
                    if (isSpeaking) ttsEngine.pause() else ttsEngine.playFromPage(state.selectedPage - 1)
                }) {
                    Icon(if (isSpeaking) Icons.Default.Pause else Icons.Default.PlayArrow, if (isSpeaking) "Pausar" else "Ouvir", tint = Color(0xFF81C784))
                }
                IconButton(onClick = {
                    val next = when { ttsSpeed < 0.9f -> 1.0f; ttsSpeed < 1.4f -> 1.5f; else -> 0.75f }
                    ttsEngine.setSpeed(next)
                }) { Icon(Icons.Default.Speed, "${"%.1f".format(ttsSpeed)}x", tint = Color.White) }
            }
        }
    }
}
