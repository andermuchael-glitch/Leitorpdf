package br.com.leitorpdf.ui.reader

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SettingsVoice
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.collectAsState
import br.com.leitorpdf.data.pdf.PdfPageView
import br.com.leitorpdf.reader.ReaderViewModel
import android.content.Intent
import android.provider.Settings
import br.com.leitorpdf.reader.AndroidTts
import kotlinx.coroutines.delay

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
    var showNeuralDialog by remember { mutableStateOf(false) }
    var neuralEndpoint by remember { mutableStateOf(viewModel.neuralEndpoint()) }
    var neuralToken by remember { mutableStateOf(viewModel.neuralToken()) }
    val voicePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked ->
        picked?.let(viewModel::importNeuralVoice)
    }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showAppearanceDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var pageInput by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf(0) }
    var brightness by remember { mutableStateOf(0f) }
    var highlightEnabled by remember { mutableStateOf(false) }
    var reflowMode by remember { mutableStateOf(false) }
    var showReadingSettings by remember { mutableStateOf(false) }
    var reflowFontSize by remember { mutableStateOf(21f) }
    var reflowLineHeight by remember { mutableStateOf(1.55f) }
    var reflowBackground by remember { mutableStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
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
        controlsVisible = true
        if (pageCount > 0) {
            pdfView.goToPage(state.selectedPage - 1, scope)
        }
    }

    LaunchedEffect(state.selectedPage, state.isSpeaking, reflowMode) {
        controlsVisible = true
        kotlinx.coroutines.delay(3500)
        controlsVisible = false
    }

    LaunchedEffect(state.highlightText) {
        if (pageCount > 0) {
            pdfView.setHighlightText(state.highlightText, scope)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.syncPlayback()
            delay(500)
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

    if (showNeuralDialog) {
        AlertDialog(
            onDismissRequest = { showNeuralDialog = false },
            title = { Text("Voz realista — Minha voz") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("O áudio é gerado por uma voz neural usando a sua gravação como referência.")
                    OutlinedTextField(
                        value = neuralEndpoint,
                        onValueChange = { neuralEndpoint = it },
                        label = { Text("Servidor de voz") },
                        placeholder = { Text("https://seu-servidor:8000") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = neuralToken,
                        onValueChange = { neuralToken = it },
                        label = { Text("Token (opcional)") },
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            viewModel.configureNeuralEndpoint(neuralEndpoint)
                            viewModel.configureNeuralToken(neuralToken)
                            voicePicker.launch("audio/*")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = neuralEndpoint.isNotBlank() && !state.neuralBusy
                    ) {
                        Icon(Icons.Default.RecordVoiceOver, null)
                        Text(if (state.neuralVoiceReady) "Trocar gravação da minha voz" else "Selecionar minha gravação")
                    }
                    if (state.neuralVoiceReady) {
                        Text("✓ Voz neural configurada", color = MaterialTheme.colorScheme.primary)
                    }
                    if (state.neuralBusy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Preparando voz…")
                    }
                    Text("Use uma gravação limpa, sem música e com 10–30 segundos de fala contínua.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.configureNeuralEndpoint(neuralEndpoint)
                    viewModel.configureNeuralToken(neuralToken)
                    showNeuralDialog = false
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { showNeuralDialog = false }) { Text("Fechar") } }
        )
    }

    if (showVoiceDialog) {
        AlertDialog(
            onDismissRequest = { showVoiceDialog = false },
            title = { Text("Voz e áudio") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Vozes do mecanismo TTS do Android",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "A narração principal usa voz neural realista. Configure uma gravação de referência para gerar a voz pelo servidor neural.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = {
                            showVoiceDialog = false; showNeuralDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.SettingsVoice, null)
                        Text("  Configurar voz neural")
                    }
                    Divider()
                    if (state.voices.isEmpty()) {
                        Text("Nenhuma voz em português encontrada.")
                    }
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
                                Text(
                                    "Voz " + (index + 1) + " — " + voice.label,
                                    modifier = Modifier.weight(1f)
                                )
                                if (state.selectedVoice == voice.name) Text("✓")
                            }
                        }
                    }
                    Divider()
                    Text(
                        "Velocidade: " + "%.1f".format(state.speechRate) + "x",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Slider(
                        value = state.speechRate,
                        onValueChange = viewModel::setSpeechRate,
                        valueRange = .5f..2f
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showVoiceDialog = false }) { Text("Fechar") }
            }
        )
    }

    if (showAudioDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.exportingAudio) showAudioDialog = false },
            title = { Text("Arquivos de narração") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Salvar a narração da página atual como arquivos WAV.")
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            "Downloads / LeitorPDF",
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                    if (state.exportingAudio) {
                        LinearProgressIndicator(
                            progress = { state.exportProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(state.exportMessage)
                    } else if (state.exportMessage.isNotBlank()) {
                        Text(
                            state.exportMessage,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Button(
                        onClick = { viewModel.exportCurrentPageAudio() },
                        enabled = state.speechReady &&
                            !state.exportingAudio &&
                            state.pageTexts.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, null)
                        Text("  Salvar áudio da página " + state.selectedPage)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showAudioDialog = false },
                    enabled = !state.exportingAudio
                ) { Text("Fechar") }
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
                    Text("Marca-texto (opcional)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
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
                    Text("O marca-texto é opcional e fica desligado por padrão para priorizar a fluidez.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showAppearanceDialog = false }) { Text("Concluir") } }
        )
    }

    if (showReadingSettings) {
        AlertDialog(
            onDismissRequest = { showReadingSettings = false },
            title = { Text("Leitura confortável") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Tamanho do texto: " + reflowFontSize.toInt() + " sp")
                    Slider(
                        value = reflowFontSize,
                        onValueChange = { reflowFontSize = it },
                        valueRange = 17f..34f
                    )
                    Text("Espaçamento entre linhas")
                    Slider(
                        value = reflowLineHeight,
                        onValueChange = { reflowLineHeight = it },
                        valueRange = 1.25f..1.9f
                    )
                    Text("Fundo", style = MaterialTheme.typography.titleSmall)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { reflowBackground = 0 },
                            modifier = Modifier.weight(1f)
                        ) { Text("Claro") }
                        OutlinedButton(
                            onClick = { reflowBackground = 1 },
                            modifier = Modifier.weight(1f)
                        ) { Text("Sépia") }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { reflowBackground = 2 },
                            modifier = Modifier.weight(1f)
                        ) { Text("Escuro") }
                        OutlinedButton(
                            onClick = { reflowBackground = 3 },
                            modifier = Modifier.weight(1f)
                        ) { Text("Preto") }
                    }
                    Text(
                        "O modo leitura reorganiza o texto extraído do PDF para ocupar a largura do celular, como em um leitor de e-book.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showReadingSettings = false }) { Text("Concluir") }
            }
        )
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Black
    ) { _ ->
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            if (reflowMode) {
                BookReader3D(
                    pageTexts = state.pageTexts,
                    selectedPage = state.selectedPage,
                    highlightText = if (highlightEnabled) state.highlightText else "",
                    fontSize = reflowFontSize,
                    lineHeightMultiplier = reflowLineHeight,
                    backgroundMode = reflowBackground,
                    modifier = Modifier.fillMaxSize(),
                    onPageChange = { page ->
                        if (page != state.selectedPage) viewModel.setSelectedPage(page)
                    }
                )
            } else {
                AndroidView(factory = { pdfView }, modifier = Modifier.fillMaxSize())

                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(state.selectedPage, state.pageCount) {
                            var distance = 0f
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    distance += dragAmount
                                },
                                onDragEnd = {
                                    when {
                                        distance < -90f -> viewModel.nextPage()
                                        distance > 90f -> viewModel.previousPage()
                                    }
                                }
                            )
                        }
                )
            }

            if (!controlsVisible) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { controlsVisible = true })
                        }
                )
            }

            if (state.isLoading) {
                Text(
                    "Preparando o PDF…",
                    modifier = Modifier.align(Alignment.Center),
                    color = androidx.compose.ui.graphics.Color.White
                )
            }

            if (controlsVisible) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp, start = 8.dp, end = 8.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = .48f)
            ) {
                Row(
                    modifier = Modifier.height(40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            "Voltar",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    Text(
                        "P. " + state.selectedPage + "/" + state.pageCount,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 3.dp)
                    )

                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                "Opções",
                                tint = Color.White,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
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
                                text = { Text("Configurar voz real") },
                                leadingIcon = { Icon(Icons.Default.RecordVoiceOver, null) },
                                onClick = { showNeuralDialog = true; menuExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Escolher voz") },
                                leadingIcon = { Icon(Icons.Default.RecordVoiceOver, null) },
                                onClick = { showVoiceDialog = true; menuExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Baixar narração") },
                                leadingIcon = { Icon(Icons.Default.Download, null) },
                                onClick = {
                                    showAudioDialog = true
                                    menuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (reflowMode) "Voltar ao PDF original" else "Modo Livro 3D") },
                                leadingIcon = { Icon(Icons.Default.AutoStories, null) },
                                onClick = {
                                    reflowMode = !reflowMode
                                    menuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Ajustes do livro") },
                                leadingIcon = { Icon(Icons.Default.Tune, null) },
                                onClick = { showReadingSettings = true; menuExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Aparência do PDF") },
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
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                shape = RoundedCornerShape(22.dp),
                color = Color.Black.copy(alpha = .84f)
            ) {
                Row(
                    modifier = Modifier
                        .height(48.dp)
                        .padding(horizontal = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(
                        onClick = { viewModel.previousPage() },
                        enabled = state.selectedPage > 1,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.Default.ArrowBackIosNew,
                            "Página anterior",
                            tint = if (state.selectedPage > 1) Color.White else Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.toggleSpeech() },
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        enabled = state.text.isNotBlank()
                    ) {
                        Icon(
                            if (state.isSpeaking) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (state.isSpeaking) "Pausar" else "Ouvir",
                            tint = Color.White,
                            modifier = Modifier.size(25.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.nextPage() },
                        enabled = state.selectedPage < state.pageCount,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.Default.ArrowForwardIos,
                            "Próxima página",
                            tint = if (state.selectedPage < state.pageCount) Color.White else Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = { showVoiceDialog = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.Default.Speed,
                            "Velocidade e voz",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
            }
        }
    }
}
