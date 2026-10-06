package br.com.leitorpdf.ui.reader

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.collectAsState
import br.com.leitorpdf.data.pdf.PdfPageView
import br.com.leitorpdf.reader.ReaderViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    uri: Uri,
    fileName: String,
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    onOpenAnotherPdf: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pageCount by remember { mutableStateOf(0) }
    var showPageDialog by remember { mutableStateOf(false) }
    var showVoiceDialog by remember { mutableStateOf(false) }
    var showAppearanceDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var pageInput by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf(0) }
    var brightness by remember { mutableStateOf(0f) }
    var highlightEnabled by remember { mutableStateOf(true) }
    val pdfView = remember { PdfPageView(context) }

    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        val controller = activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    BackHandler { onBack() }

    DisposableEffect(uri) {
        viewModel.openPdf(uri, fileName)
        pdfView.open(uri, scope, { count ->
            pageCount = count
        }, {})
        onDispose {
            pdfView.closeDocument()
        }
    }

    LaunchedEffect(state.selectedPage) {
        if (pageCount > 0) {
            pdfView.goToPage(state.selectedPage - 1, scope)
        }
    }

    LaunchedEffect(state.highlightText) {
        if (pageCount > 0) {
            pdfView.setHighlightText(state.highlightText, scope)
        }
    }

    LaunchedEffect(state.isSpeaking) {
        while (state.isSpeaking) {
            viewModel.syncPlayback()
            kotlinx.coroutines.delay(150)
        }
    }

    if (showPageDialog) {
        AlertDialog(
            onDismissRequest = { showPageDialog = false },
            title = { Text("Ir para página") },
            text = {
                OutlinedTextField(
                    value = pageInput,
                    onValueChange = { pageInput = it.filter(Char::isDigit) },
                    label = { Text("Número da página") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val page = pageInput.toIntOrNull()?.coerceIn(1, state.pageCount.coerceAtLeast(1)) ?: 1
                    viewModel.setSelectedPage(page)
                    showPageDialog = false
                }) { Text("Ir") }
            },
            dismissButton = {
                TextButton(onClick = { showPageDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (showVoiceDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.modelDownloading) showVoiceDialog = false },
            title = { Text("Voz da leitura") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Narrador neural local • Português (Brasil)",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Text(
                        "A voz é gerada no próprio celular. Depois do primeiro download, a leitura funciona sem Google Cloud, sem API e sem internet.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Divider()

                    if (state.modelDownloading) {
                        Text("Preparando o modelo de voz… \${state.modelProgress}%")
                        LinearProgressIndicator(
                            progress = { state.modelProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (!state.localTtsReady) {
                        Text(
                            "Na primeira utilização, o aplicativo precisa baixar o modelo neural Kokoro (aprox. 345 MB). Isso é feito uma única vez."
                        )
                        Button(
                            onClick = { viewModel.prepareLocalVoice() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Baixar e preparar voz")
                        }
                    } else {
                        Text(
                            "Modelo instalado e pronto para uso offline.",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    state.voices.forEachIndexed { index, voice ->
                        TextButton(
                            onClick = {
                                viewModel.selectVoice(voice.name)
                                showVoiceDialog = false
                            },
                            enabled = state.localTtsReady && !state.modelDownloading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Voz " + (index + 1) + " — " + voice.label)
                                if (state.selectedVoice == voice.name) Text("✓")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showVoiceDialog = false },
                    enabled = !state.modelDownloading
                ) {
                    Text("Fechar")
                }
            }
        )
    }

    if (showAppearanceDialog) {
        AlertDialog(
            onDismissRequest = { showAppearanceDialog = false },
            title = { Text("Aparência do PDF") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cor do PDF", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { filterMode = 0; pdfView.setFilterMode(0) }, modifier = Modifier.weight(1f)) { Text("Normal") }
                        OutlinedButton(onClick = { filterMode = 1; pdfView.setFilterMode(1) }, modifier = Modifier.weight(1f)) { Text("Sépia") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { filterMode = 2; pdfView.setFilterMode(2) }, modifier = Modifier.weight(1f)) { Text("Cinza") }
                        OutlinedButton(onClick = { filterMode = 3; pdfView.setFilterMode(3) }, modifier = Modifier.weight(1f)) { Text("Invertido") }
                    }
                    Text("Marca-texto sincronizado", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (highlightEnabled) "Ativado" else "Desativado",
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = {
                                highlightEnabled = !highlightEnabled
                                pdfView.setHighlightEnabled(highlightEnabled)
                                if (highlightEnabled) {
                                    pdfView.setHighlightText(state.highlightText, scope)
                                }
                            }
                        ) {
                            Text(if (highlightEnabled) "Desligar" else "Ligar")
                        }
                    }

                    Text("Luminosidade", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Brightness6, contentDescription = null, modifier = Modifier.size(20.dp))
                        Slider(
                            value = brightness,
                            onValueChange = { brightness = it; pdfView.setBrightness(it) },
                            valueRange = -0.45f..0.45f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text("O marca-texto acompanha o trecho lido mesmo com os filtros.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showAppearanceDialog = false }) { Text("Concluir") } }
        )
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Black
    ) { _ ->
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            AndroidView(factory = { pdfView }, modifier = Modifier.fillMaxSize())

            if (state.isLoading) {
                Text(
                    "Preparando o PDF…",
                    modifier = Modifier.align(Alignment.Center),
                    color = androidx.compose.ui.graphics.Color.White
                )
            }

            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                shape = RoundedCornerShape(50),
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = .55f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Voltar", tint = androidx.compose.ui.graphics.Color.White)
                    }
                    Text(
                        "Página ${state.selectedPage} / ${state.pageCount}",
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, "Opções", tint = androidx.compose.ui.graphics.Color.White)
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Abrir outro PDF") },
                                leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                                onClick = { menuExpanded = false; onOpenAnotherPdf() }
                            )
                            DropdownMenuItem(
                                text = { Text("Escolher página") },
                                leadingIcon = { Icon(Icons.Default.FormatListNumbered, null) },
                                onClick = {
                                    pageInput = state.selectedPage.toString()
                                    showPageDialog = true
                                    menuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Escolher voz") },
                                leadingIcon = { Icon(Icons.Default.RecordVoiceOver, null) },
                                onClick = { showVoiceDialog = true; menuExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Aparência e marca-texto") },
                                leadingIcon = { Icon(Icons.Default.Brightness6, null) },
                                onClick = { showAppearanceDialog = true; menuExpanded = false }
                            )
                        }
                    }
                }
            }

            state.error?.let {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp, start = 16.dp, end = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 10.dp
            ) {
                IconButton(
                    onClick = { viewModel.toggleSpeech() },
                    modifier = Modifier.size(64.dp),
                    enabled = state.text.isNotBlank()
                ) {
                    Icon(
                        if (state.isSpeaking) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (state.isSpeaking) "Pausar narração" else "Iniciar narração",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
        }
    }
}
