package br.com.leitorpdf.ui.reader

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import br.com.leitorpdf.data.pdf.PdfPageView
import br.com.leitorpdf.reader.ReaderViewModel
import br.com.leitorpdf.reader.PdfTtsEngine
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun ReaderScreen(uri: Uri, fileName: String, viewModel: ReaderViewModel, onBack: () -> Unit, onOpenAnotherPdf: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdfView = remember { PdfPageView(context) }
    val ttsEngine = remember { PdfTtsEngine(context) }
    val isSpeaking by ttsEngine.isSpeaking.collectAsState()
    val ttsSpeed by ttsEngine.currentSpeed.collectAsState()
    var dragAccum by remember { mutableStateOf(0f) }

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
            onReady = { },
            onError = { }
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

        // Overlay for tap zones and swipe
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragAccum = 0f },
                        onHorizontalDrag = { _, dragAmount -> dragAccum += dragAmount },
                        onDragEnd = {
                            if (abs(dragAccum) > 80) {
                                if (dragAccum < 0) viewModel.nextPage()
                                else viewModel.previousPage()
                            }
                            dragAccum = 0f
                        }
                    )
                }
        ) {
            // Left tap zone (previous page)
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(80.dp)
                    .align(Alignment.CenterStart)
                    .pointerInput(Unit) {
                        // handled by parent swipe; leave empty for now
                    }
            )
        }

        if (state.isLoading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            Text("Preparando PDF…", Modifier.align(Alignment.Center).padding(top = 48.dp), color = Color.White)
        }

        // Top bar
        Surface(
            Modifier.align(Alignment.TopCenter).padding(8.dp),
            RoundedCornerShape(20.dp),
            Color.Black.copy(alpha = 0.65f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Voltar", tint = Color.White) }
                Text(
                    "P. ${state.selectedPage}/${state.pageCount}",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                IconButton(onClick = {
                    if (isSpeaking) ttsEngine.pause() else ttsEngine.playFromPage(state.selectedPage - 1)
                }) {
                    Icon(
                        if (isSpeaking) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isSpeaking) "Pausar" else "Ouvir",
                        tint = Color(0xFF81C784)
                    )
                }
                IconButton(onClick = {
                    val next = when {
                        ttsSpeed < 0.9f -> 1.0f
                        ttsSpeed < 1.4f -> 1.5f
                        else -> 0.75f
                    }
                    ttsEngine.setSpeed(next)
                }) { Icon(Icons.Default.Speed, "${"%.1f".format(ttsSpeed)}x", tint = Color.White) }
            }
        }

        // Bottom navigation bar
        Surface(
            Modifier.align(Alignment.BottomCenter).padding(12.dp),
            RoundedCornerShape(28.dp),
            Color.Black.copy(alpha = 0.7f)
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = { viewModel.previousPage() },
                    enabled = state.selectedPage > 1
                ) {
                    Icon(Icons.Default.ChevronLeft, "Anterior", tint = Color.White)
                }

                Text(
                    "${state.selectedPage} / ${state.pageCount}",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                IconButton(
                    onClick = { viewModel.nextPage() },
                    enabled = state.selectedPage < state.pageCount
                ) {
                    Icon(Icons.Default.ChevronRight, "Próxima", tint = Color.White)
                }
            }
        }
    }
}
