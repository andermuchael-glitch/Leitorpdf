package br.com.leitorpdf.ui.reader

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.collectAsState
import br.com.leitorpdf.data.pdf.PdfPageView
import br.com.leitorpdf.reader.ReaderViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(uri: Uri, fileName: String, viewModel: ReaderViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pageCount by remember { mutableStateOf(0) }
    var pageNumber by remember { mutableStateOf(1) }
    val pdfView = remember { PdfPageView(context) }

    DisposableEffect(uri) {
        viewModel.openPdf(uri, fileName)
        pdfView.open(uri, scope, { count ->
            pageCount = count
            pageNumber = 1
        }, {})
        onDispose {
            pdfView.closeDocument()
            viewModel.stopSpeech()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(fileName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Voltar")
                    }
                }
            )
        },
        bottomBar = {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(10.dp),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Página \${pageNumber}\${if (pageCount > 0) " de \${pageCount}" else ""}")
                        Text(
                            if (state.isSpeaking) "Ouvindo" else "Lendo",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                pdfView.previousPage(scope)
                                pageNumber = (pageNumber - 1).coerceAtLeast(1)
                            },
                            enabled = pageNumber > 1
                        ) { Icon(Icons.Default.SkipPrevious, "Página anterior") }

                        IconButton(
                            modifier = Modifier.size(58.dp),
                            onClick = { viewModel.toggleSpeech() },
                            enabled = state.text.isNotBlank()
                        ) {
                            Icon(
                                if (state.isSpeaking) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (state.isSpeaking) "Pausar áudio" else "Ouvir PDF"
                            )
                        }

                        IconButton(
                            onClick = {
                                pdfView.nextPage(scope)
                                pageNumber = (pageNumber + 1).coerceAtMost(pageCount.coerceAtLeast(1))
                            },
                            enabled = pageCount > 0 && pageNumber < pageCount
                        ) { Icon(Icons.Default.SkipNext, "Próxima página") }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Speed, "Velocidade", Modifier.size(20.dp))
                        Slider(
                            value = state.speechRate,
                            onValueChange = { viewModel.setSpeechRate(it) },
                            valueRange = 0.5f..2f,
                            steps = 5,
                            modifier = Modifier.weight(1f)
                        )
                        Text("\${state.speechRate.roundToInt()}x")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(factory = { pdfView }, modifier = Modifier.fillMaxSize())
                if (state.isLoading) Text("Preparando o PDF…")
            }

            state.error?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
