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
    var showCloudDialog by remember { mutableStateOf(false) }
    var showAppearanceDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var pageInput by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf(0) }
    var brightness by remember { mutableStateOf(0f) }
    var highlightEnabled by remember { mutableStateOf(true) }
    var cloudEndpointInput by remember { mutableStateOf(viewModel.cloudEndpoint()) }
    val pdfView = remember { PdfPageView(context) }

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
            onDismissRequest = { showVoiceDialog = false },
            title = { Text("Voz da leitura") },
            text = {
                Column {
                    Text(
                        if (state.cloudTtsConfigured) "Narrador neural profissional • Português (Brasil)"
                        else "Para usar a voz profissional, conecte o servidor de voz neural.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Divider(Modifier.padding(vertical = 10.dp))
                    if (state.cloudTtsConfigured) {
                        state.voices.forEachIndexed { index, voice ->
                            TextButton(
                                onClick = {
                                    viewModel.selectVoice(voice.name)
                                    showVoiceDialog = false
                                },
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
                    } else {
                        Text("As vozes abaixo são neurais profissionais. Elas não usam o TTS tradicional do aparelho.")
                    }
                    OutlinedButton(
                        onClick = {
                            cloudEndpointInput = state.cloudTtsEndpoint
                            showVoiceDialog = false
                            showCloudDialog = true
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Text(if (state.cloudTtsConfigured) "Alterar servidor de voz" else "Configurar voz profissional")
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showCloudDialog) {
        AlertDialog(
            onDismissRequest = { showCloudDialog = false },
            title = { Text("Servidor da voz profissional") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Informe a URL HTTPS do endpoint /api/tts. A chave do Google Cloud fica somente no servidor, nunca dentro do APK.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = cloudEndpointInput,
                        onValueChange = { cloudEndpointInput = it },
                        label = { Text("URL do servidor TTS") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setCloudEndpoint(cloudEndpointInput)
                    showCloudDialog = false
                }) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { showCloudDialog = false }) { Text("Cancelar") }
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
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(fileName, maxLines = 1)
                        if (state.pageCount > 0) {
                            Text(
                                "Página " + state.selectedPage + " de " + state.pageCount,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Voltar")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, "Mais opções")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Abrir outro PDF") },
                                leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenAnotherPdf()
                                }
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
                                onClick = {
                                    showVoiceDialog = true
                                    menuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Aparência e marca-texto") },
                                leadingIcon = { Icon(Icons.Default.Brightness6, null) },
                                onClick = {
                                    showAppearanceDialog = true
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (state.pageCount > 0) {
                        LinearProgressIndicator(
                            progress = { state.selectedPage.toFloat() / state.pageCount.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                if (state.isSpeaking) "Reproduzindo em segundo plano" else "Leitura do PDF",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Página " + state.selectedPage + " de " + state.pageCount,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (state.isSpeaking)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                if (state.isSpeaking) "● Ouvindo" else "● Pronto",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = if (state.isSpeaking)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                viewModel.setSelectedPage(state.selectedPage - 1)
                            },
                            enabled = state.selectedPage > 1
                        ) {
                            Icon(Icons.Default.SkipPrevious, "Página anterior")
                        }

                        Card(
                            modifier = Modifier
                                .size(64.dp)
                                .padding(2.dp),
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            IconButton(
                                onClick = { viewModel.toggleSpeech() },
                                modifier = Modifier.fillMaxSize(),
                                enabled = state.text.isNotBlank()
                            ) {
                                Icon(
                                    if (state.isSpeaking) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    if (state.isSpeaking) "Pausar" else "Ouvir",
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                viewModel.setSelectedPage(state.selectedPage + 1)
                            },
                            enabled = state.selectedPage < state.pageCount
                        ) {
                            Icon(Icons.Default.SkipNext, "Próxima página")
                        }
                    }

                    if (state.resumeAvailable && !state.isSpeaking) {
                        OutlinedButton(
                            onClick = { viewModel.continueReading() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Bookmark, null)
                            Text("  Continuar de onde parei")
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Speed, "Velocidade", Modifier.size(20.dp))
                        Slider(
                            value = state.speechRate,
                            onValueChange = { viewModel.setSpeechRate(it) },
                            valueRange = 0.5f..2f,
                            steps = 5,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            String.format(Locale.getDefault(), "%.1fx", state.speechRate),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    OutlinedButton(
                        onClick = { showVoiceDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.RecordVoiceOver, null)
                        Text("  " + (state.voices.firstOrNull { it.name == state.selectedVoice }?.label ?: "Escolher voz"))
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
                    .padding(horizontal = 8.dp, vertical = 4.dp),
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
