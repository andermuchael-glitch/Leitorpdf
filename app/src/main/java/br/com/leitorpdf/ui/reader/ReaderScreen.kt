package br.com.leitorpdf.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import br.com.leitorpdf.data.pdf.PdfPageView
import br.com.leitorpdf.reader.AiImageClient
import br.com.leitorpdf.reader.ReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

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
    val scope = rememberCoroutineScope()
    val ai = remember { AiImageClient(context) }

    var controlsVisible by remember { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }
    var readingMode by remember { mutableIntStateOf(2) } // 0 PDF, 1 Reflow, 2 Livro 3D
    var selectedText by remember { mutableStateOf("") }
    var pageInput by remember { mutableStateOf("1") }
    var showPageDialog by remember { mutableStateOf(false) }
    var showMarksDialog by remember { mutableStateOf(false) }
    var showReadingSettings by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var showAiDialog by remember { mutableStateOf(false) }
    var showAiSettings by remember { mutableStateOf(false) }
    var aiEndpoint by remember { mutableStateOf(ai.endpoint()) }
    var aiToken by remember { mutableStateOf(ai.token()) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var fontSize by remember { mutableStateOf(20f) }
    var lineHeight by remember { mutableStateOf(1.55f) }
    var backgroundMode by remember { mutableIntStateOf(0) }
    var filterMode by remember { mutableIntStateOf(0) }
    var brightness by remember { mutableStateOf(0f) }

    val pdfView = remember { PdfPageView(context) }

    LaunchedEffect(uri) { viewModel.openPdf(uri, fileName) }
    LaunchedEffect(state.selectedPage, readingMode) {
        selectedText = ""
        if (readingMode == 0 && state.pageCount > 0) {
            pdfView.goToPage(state.selectedPage - 1, scope)
        }
    }
    DisposableEffect(Unit) {
        onDispose { pdfView.closeDocument() }
    }
    BackHandler(onBack = onBack)

    fun copyText(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("LeitorPDF", text))
    }

    fun openImage(file: File) {
        val contentUri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "image/png")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    if (showPageDialog) {
        AlertDialog(
            onDismissRequest = { showPageDialog = false },
            title = { Text("Ir para página") },
            text = {
                OutlinedTextField(
                    value = pageInput,
                    onValueChange = { pageInput = it.filter(Char::isDigit).take(6) },
                    label = { Text("Página") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pageInput.toIntOrNull()?.let(viewModel::setSelectedPage)
                    showPageDialog = false
                }) { Text("Ir") }
            },
            dismissButton = { TextButton(onClick = { showPageDialog = false }) { Text("Cancelar") } }
        )
    }

    if (showMarksDialog) {
        AlertDialog(
            onDismissRequest = { showMarksDialog = false },
            title = { Text("Marcações e favoritos") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Páginas marcadas", style = MaterialTheme.typography.titleSmall)
                    if (state.bookmarks.isEmpty()) {
                        Text("Nenhuma página marcada.")
                    } else {
                        state.bookmarks.forEach { page ->
                            TextButton(
                                onClick = {
                                    viewModel.setSelectedPage(page)
                                    showMarksDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Página " + page) }
                        }
                    }
                    Text("Trechos marcados", style = MaterialTheme.typography.titleSmall)
                    if (state.highlights.isEmpty()) {
                        Text("Nenhum trecho marcado.")
                    } else {
                        state.highlights.takeLast(20).reversed().forEach { mark ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "P." + mark.page + "  " + mark.text,
                                    Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(onClick = { viewModel.removeHighlight(mark.id) }) {
                                    Icon(Icons.Default.DeleteOutline, "Excluir")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMarksDialog = false }) { Text("Fechar") } }
        )
    }

    if (showReadingSettings) {
        AlertDialog(
            onDismissRequest = { showReadingSettings = false },
            title = { Text("Experiência de leitura") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tamanho: " + fontSize.toInt() + " sp")
                    Slider(fontSize, { fontSize = it }, valueRange = 16f..34f)
                    Text("Espaçamento")
                    Slider(lineHeight, { lineHeight = it }, valueRange = 1.25f..1.9f)
                    Text("Fundo")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Claro", "Sépia", "Escuro", "Preto").forEachIndexed { i, label ->
                            OutlinedButton(
                                onClick = { backgroundMode = i },
                                Modifier.weight(1f)
                            ) { Text(label, fontSize = 10.sp) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showReadingSettings = false }) { Text("Concluir") } }
        )
    }

    if (showAppearance) {
        AlertDialog(
            onDismissRequest = { showAppearance = false },
            title = { Text("Aparência do PDF") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Filtro")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Normal", "Sépia", "Cinza", "Invertido").forEachIndexed { i, label ->
                            OutlinedButton(
                                onClick = { filterMode = i; pdfView.setFilterMode(i) },
                                Modifier.weight(1f)
                            ) { Text(label, fontSize = 10.sp) }
                        }
                    }
                    Text("Luminosidade")
                    Slider(
                        brightness,
                        {
                            brightness = it
                            pdfView.setBrightness(it)
                        },
                        valueRange = -0.45f..0.45f
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showAppearance = false }) { Text("Concluir") } }
        )
    }

    if (showAiSettings) {
        AlertDialog(
            onDismissRequest = { showAiSettings = false },
            title = { Text("Configurar IA") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Use um servidor intermediário. A chave da IA nunca fica no APK.")
                    OutlinedTextField(
                        aiEndpoint,
                        { aiEndpoint = it },
                        label = { Text("Servidor de IA") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        aiToken,
                        { aiToken = it },
                        label = { Text("Token opcional") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ai.setEndpoint(aiEndpoint)
                    ai.setToken(aiToken)
                    showAiSettings = false
                }) { Text("Salvar") }
            }
        )
    }

    if (showAiDialog) {
        AlertDialog(
            onDismissRequest = { if (!aiBusy) showAiDialog = false },
            title = { Text("Criar imagem com IA") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A IA transforma o trecho selecionado em uma ilustração.")
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            selectedText,
                            Modifier.padding(12.dp),
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    aiError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (aiBusy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Gerando imagem…")
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedText.isNotBlank() && !aiBusy,
                    onClick = {
                        aiBusy = true
                        aiError = null
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                ai.generateImage(
                                    "Crie uma ilustração cinematográfica, respeitosa e fiel ao sentido deste trecho. Não escreva palavras na imagem: " +
                                        selectedText
                                )
                            }.onSuccess { file ->
                                launch(Dispatchers.Main) {
                                    aiBusy = false
                                    showAiDialog = false
                                    openImage(file)
                                }
                            }.onFailure { e ->
                                launch(Dispatchers.Main) {
                                    aiBusy = false
                                    aiError = e.message ?: "Falha ao gerar imagem."
                                }
                            }
                        }
                    }
                ) { Text("Gerar") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { copyText(selectedText) }) { Text("Copiar") }
                    TextButton(onClick = {
                        showAiDialog = false
                        showAiSettings = true
                    }) { Text("Configurar") }
                }
            }
        )
    }

    Scaffold(containerColor = Color.Black) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (readingMode) {
                1 -> ReflowReader(
                    pageText = state.pageTexts.getOrNull(state.selectedPage - 1).orEmpty(),
                    highlights = viewModel.highlightsForPage(),
                    fontSize = fontSize,
                    lineHeightMultiplier = lineHeight,
                    backgroundMode = backgroundMode,
                    modifier = Modifier.fillMaxSize(),
                    onIncreaseFont = { fontSize = (fontSize + 1).coerceAtMost(34f) },
                    onDecreaseFont = { fontSize = (fontSize - 1).coerceAtLeast(16f) },
                    onTextSelected = { selectedText = it }
                )
                2 -> BookReader3D(
                    pageTexts = state.pageTexts,
                    selectedPage = state.selectedPage,
                    highlights = viewModel.highlightsForPage(),
                    fontSize = fontSize,
                    lineHeightMultiplier = lineHeight,
                    backgroundMode = backgroundMode,
                    modifier = Modifier.fillMaxSize(),
                    onPageChange = { page ->
                        if (page != state.selectedPage) viewModel.setSelectedPage(page)
                    },
                    onTextSelected = { selectedText = it }
                )
                else -> {
                    AndroidView(
                        factory = {
                            pdfView.apply {
                                open(uri, scope, onReady = {
                                    goToPage(state.selectedPage - 1, scope)
                                }, onError = {})
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        Modifier.fillMaxSize().pointerInput(state.selectedPage) {
                            var distance = 0f
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, amount ->
                                    change.consume()
                                    distance += amount
                                },
                                onDragEnd = {
                                    if (distance < -90) viewModel.nextPage()
                                    if (distance > 90) viewModel.previousPage()
                                }
                            )
                        }
                    )
                }
            }

            if (state.isLoading) {
                Text("Preparando o PDF…", Modifier.align(Alignment.Center), color = Color.White)
            }

            if (controlsVisible) {
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(6.dp),
                    RoundedCornerShape(20.dp),
                    Color.Black.copy(alpha = .58f)
                ) {
                    Row(Modifier.height(42.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, "Voltar", tint = Color.White)
                        }
                        Text(
                            "P. " + state.selectedPage + "/" + state.pageCount,
                            color = Color.White,
                            fontSize = 12.sp
                        )
                        IconButton(onClick = { viewModel.toggleBookmark() }) {
                            Icon(
                                if (state.bookmarks.contains(state.selectedPage)) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                "Favorito",
                                tint = if (state.bookmarks.contains(state.selectedPage)) Color(0xFFFFD54F) else Color.White
                            )
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, "Opções", tint = Color.White)
                            }
                            DropdownMenu(menuExpanded, { menuExpanded = false }) {
                                DropdownMenuItem(
                                    { Text("Abrir outro PDF") },
                                    { Icon(Icons.Default.PictureAsPdf, null) },
                                    { menuExpanded = false; onOpenAnotherPdf() }
                                )
                                DropdownMenuItem(
                                    { Text("Ir para página") },
                                    { Icon(Icons.Default.MenuBook, null) },
                                    {
                                        pageInput = state.selectedPage.toString()
                                        showPageDialog = true
                                        menuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    { Text("Marcações e favoritos") },
                                    { Icon(Icons.Default.FormatColorHighlight, null) },
                                    { showMarksDialog = true; menuExpanded = false }
                                )
                                DropdownMenuItem(
                                    { Text("Modo Reflow") },
                                    { Icon(Icons.Default.FormatSize, null) },
                                    { readingMode = 1; menuExpanded = false }
                                )
                                DropdownMenuItem(
                                    { Text("Modo Livro 3D") },
                                    { Icon(Icons.Default.AutoStories, null) },
                                    { readingMode = 2; menuExpanded = false }
                                )
                                DropdownMenuItem(
                                    { Text("PDF original") },
                                    { Icon(Icons.Default.PictureAsPdf, null) },
                                    { readingMode = 0; menuExpanded = false }
                                )
                                DropdownMenuItem(
                                    { Text("Ajustes da leitura") },
                                    { Icon(Icons.Default.Tune, null) },
                                    { showReadingSettings = true; menuExpanded = false }
                                )
                                DropdownMenuItem(
                                    { Text("Aparência do PDF") },
                                    { Icon(Icons.Default.Brightness6, null) },
                                    { showAppearance = true; menuExpanded = false }
                                )
                                DropdownMenuItem(
                                    { Text("IA • criar imagem") },
                                    { Icon(Icons.Default.AutoAwesome, null) },
                                    {
                                        selectedText = state.pageTexts.getOrNull(state.selectedPage - 1).orEmpty().take(4000)
                                        showAiDialog = true
                                        menuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    { Text("Configurar IA") },
                                    { Icon(Icons.Default.AutoAwesome, null) },
                                    { showAiSettings = true; menuExpanded = false }
                                )
                            }
                        }
                    }
                }

                if (selectedText.isNotBlank()) {
                    Surface(
                        Modifier.align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 10.dp, vertical = 62.dp),
                        RoundedCornerShape(20.dp),
                        MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                selectedText.length.toString() + " caracteres",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )
                            TextButton(onClick = {
                                viewModel.addHighlight(selectedText)
                                selectedText = ""
                            }) {
                                Icon(Icons.Default.FormatColorHighlight, null)
                                Text("Marcar")
                            }
                            TextButton(onClick = { copyText(selectedText) }) {
                                Icon(Icons.Default.ContentCopy, null)
                                Text("Copiar")
                            }
                            TextButton(onClick = { showAiDialog = true }) {
                                Icon(Icons.Default.AutoAwesome, null)
                                Text("IA")
                            }
                        }
                    }
                } else {
                    Surface(
                        Modifier.align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(8.dp),
                        RoundedCornerShape(22.dp),
                        Color.Black.copy(alpha = .84f)
                    ) {
                        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { viewModel.previousPage() },
                                enabled = state.selectedPage > 1
                            ) {
                                Icon(
                                    Icons.Default.ArrowBackIosNew,
                                    "Anterior",
                                    tint = if (state.selectedPage > 1) Color.White else Color.Gray
                                )
                            }
                            IconButton(onClick = {
                                readingMode = if (readingMode == 2) 1 else 2
                            }) {
                                Icon(
                                    if (readingMode == 2) Icons.Default.FormatSize else Icons.Default.AutoStories,
                                    "Alternar leitura",
                                    tint = Color.White
                                )
                            }
                            IconButton(
                                onClick = { viewModel.nextPage() },
                                enabled = state.selectedPage < state.pageCount
                            ) {
                                Icon(
                                    Icons.Default.ArrowForwardIos,
                                    "Próxima",
                                    tint = if (state.selectedPage < state.pageCount) Color.White else Color.Gray
                                )
                            }
                        }
                    }
                }
            }

            if (!controlsVisible) {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures(onTap = { controlsVisible = true })
                    }
                )
            }

            state.error?.let {
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(top = 56.dp, start = 12.dp, end = 12.dp),
                    RoundedCornerShape(14.dp),
                    MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        it,
                        Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}
