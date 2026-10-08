package br.com.leitorpdf.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.awaitPointerEvent
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
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
import br.com.leitorpdf.reader.ReaderAiClient
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
    val readerAi = remember { ReaderAiClient(context) }

    var controlsVisible by remember { mutableStateOf(false) }
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
    var concentration by remember { mutableStateOf(false) }
    var twoPages by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(1f) }
    var margin by remember { mutableStateOf(26f) }
    var showAiActions by remember { mutableStateOf(false) }
    var aiAction by remember { mutableStateOf("explicar") }
    var aiResult by remember { mutableStateOf("") }
    var showAiResult by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf(emptyList<Int>()) }
    var showIndexDialog by remember { mutableStateOf(false) }
    var showNotesDialog by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    var highlightColor by remember { mutableStateOf("yellow") }

    val pdfView = remember { PdfPageView(context) }

    LaunchedEffect(uri) {
        viewModel.openPdf(uri, fileName)
        val options = viewModel.readingOptions()
        concentration = options.first
        zoom = options.second
        margin = options.third
    }
    SideEffect {
        val activity = context as? Activity
        activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(state.selectedPage) { viewModel.recordHistory() }
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

    fun runAi(action: String) {
        val source = selectedText.ifBlank { state.pageTexts.getOrNull(state.selectedPage - 1).orEmpty() }
        if (source.isBlank()) return
        aiAction = action
        showAiActions = false
        aiError = null
        aiBusy = true
        scope.launch(Dispatchers.IO) {
            runCatching { readerAi.analyze(action, source, state.text.take(24000)) }
                .onSuccess { result -> launch(Dispatchers.Main) { aiBusy = false; aiResult = result; showAiResult = true } }
                .onFailure { e -> launch(Dispatchers.Main) { aiBusy = false; aiError = e.message ?: "Falha na IA." } }
        }
    }

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
                    Text("Zoom: " + (zoom * 100).toInt() + "%")
                    Slider(zoom, { zoom = it; viewModel.saveReadingOptions(concentration, twoPages, zoom, margin) }, valueRange = 0.85f..1.35f)
                    Text("Margens: " + margin.toInt() + " dp")
                    Slider(margin, { margin = it; viewModel.saveReadingOptions(concentration, twoPages, zoom, margin) }, valueRange = 12f..48f)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = concentration, onClick = { concentration = !concentration; viewModel.saveReadingOptions(concentration, twoPages, zoom, margin) }, label = { Text("Concentração") })
                        FilterChip(selected = twoPages, onClick = { twoPages = !twoPages; viewModel.saveReadingOptions(concentration, twoPages, zoom, margin) }, label = { Text("2 páginas") })
                    }
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

    if (showSearchDialog) {
        AlertDialog(onDismissRequest = { showSearchDialog = false }, title = { Text("Localizar no livro") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(searchQuery, { searchQuery = it }, label = { Text("Palavra ou frase") }, singleLine = true)
                if (searchResults.isNotEmpty()) Text("Encontrado nas páginas: " + searchResults.joinToString(", "))
            }
        }, confirmButton = { TextButton(onClick = {
            val q = searchQuery.trim().lowercase(); searchResults = if (q.isBlank()) emptyList() else state.pageTexts.mapIndexedNotNull { i, t -> if (t.lowercase().contains(q)) i + 1 else null };
            searchResults.firstOrNull()?.let(viewModel::setSelectedPage)
        }) { Text("Localizar") } }, dismissButton = { TextButton(onClick = { showSearchDialog = false }) { Text("Fechar") } })
    }

    if (showIndexDialog) {
        val chapters = state.pageTexts.mapIndexedNotNull { i, text -> val first = text.lines().map { it.trim() }.firstOrNull { it.length in 3..100 && (it.all { ch -> !ch.isLowerCase() } || it.matches(Regex("""(capítulo|chapter|\d+[.)-]).*""", RegexOption.IGNORE_CASE))) }; first?.let { i + 1 to it } }
        AlertDialog(onDismissRequest = { showIndexDialog = false }, title = { Text("Índice do livro") }, text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { if (chapters.isEmpty()) Text("Não foi possível identificar capítulos automaticamente.") else chapters.take(80).forEach { (page, title) -> TextButton(onClick = { viewModel.setSelectedPage(page); showIndexDialog = false }, modifier = Modifier.fillMaxWidth()) { Text("P. $page  $title", maxLines = 1, overflow = TextOverflow.Ellipsis) } } } }, confirmButton = { TextButton(onClick = { showIndexDialog = false }) { Text("Fechar") } })
    }

    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = { Text("Histórico de leitura") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val history = viewModel.history()
                    if (history.isEmpty()) Text("Ainda não há histórico.")
                    history.take(15).forEach { item ->
                        TextButton(
                            onClick = { showHistoryDialog = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(item.name + " • página " + item.page, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistoryDialog = false }) { Text("Fechar") } }
        )
    }

    if (showNotesDialog) {
        AlertDialog(onDismissRequest = { showNotesDialog = false }, title = { Text("Notas pessoais") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(noteText, { noteText = it }, label = { Text("Nova nota para a página " + state.selectedPage) }, minLines = 3)
                state.notes.takeLast(8).reversed().forEach { note -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("P.${note.page}  " + note.text, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis); IconButton(onClick = { viewModel.removeNote(note.id) }) { Icon(Icons.Default.DeleteOutline, "Excluir") } } }
            }
        }, confirmButton = { TextButton(onClick = { if (noteText.isNotBlank()) { viewModel.addNote(noteText); noteText = "" } }) { Text("Salvar nota") } }, dismissButton = { TextButton(onClick = { showNotesDialog = false }) { Text("Fechar") } })
    }

    if (showAiActions) {
        AlertDialog(onDismissRequest = { showAiActions = false }, title = { Text("IA de leitura") }, text = { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("explicar" to "Explicar este trecho","resumir" to "Resumir","simples" to "Explicar em linguagem simples","perguntas_capitulo" to "Fazer perguntas sobre o capítulo","conceitos" to "Encontrar conceitos importantes","estudo" to "Criar estudo / resumo","perguntas_estudo" to "Gerar perguntas para estudo").forEach { (key,label) -> TextButton(onClick = { runAi(key) }, modifier = Modifier.fillMaxWidth()) { Text(label) } }
        } }, confirmButton = { TextButton(onClick = { showAiActions = false; showAiSettings = true }) { Text("Configurar IA") } })
    }

    if (showAiResult) {
        AlertDialog(onDismissRequest = { showAiResult = false }, title = { Text("IA • " + aiAction) }, text = { androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 480.dp)) { item { Text(aiResult) } } }, confirmButton = { TextButton(onClick = { copyText(aiResult); showAiResult = false }) { Text("Copiar") } })
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
        Box(Modifier.fillMaxSize().background(Color.Black).readerLongPress { controlsVisible = !controlsVisible }) {
            when (readingMode) {
                1 -> ReflowReader(
                    pageText = state.pageTexts.getOrNull(state.selectedPage - 1).orEmpty(),
                    highlights = viewModel.highlightsForPage(),
                    fontSize = fontSize,
                    lineHeightMultiplier = lineHeight,
                    backgroundMode = backgroundMode,
                    concentration = concentration,
                    zoom = zoom,
                    margin = margin,
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
                                    text = { Text("Abrir outro PDF") },
                                    leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                                    onClick = { menuExpanded = false; onOpenAnotherPdf() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Ir para página") },
                                    leadingIcon = { Icon(Icons.Default.MenuBook, null) },
                                    onClick = {
                                        pageInput = state.selectedPage.toString()
                                        showPageDialog = true
                                        menuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Marcações e favoritos") },
                                    leadingIcon = { Icon(Icons.Default.Highlight, null) },
                                    onClick = { showMarksDialog = true; menuExpanded = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Modo Reflow") },
                                    leadingIcon = { Icon(Icons.Default.FormatSize, null) },
                                    onClick = { readingMode = 1; menuExpanded = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Modo Livro 3D") },
                                    leadingIcon = { Icon(Icons.Default.AutoStories, null) },
                                    onClick = { readingMode = 2; menuExpanded = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("PDF original") },
                                    leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                                    onClick = { readingMode = 0; menuExpanded = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Ajustes da leitura") },
                                    leadingIcon = { Icon(Icons.Default.Tune, null) },
                                    onClick = { showReadingSettings = true; menuExpanded = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Aparência do PDF") },
                                    leadingIcon = { Icon(Icons.Default.Brightness6, null) },
                                    onClick = { showAppearance = true; menuExpanded = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("IA • criar imagem") },
                                    leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                                    onClick = {
                                        selectedText = state.pageTexts.getOrNull(state.selectedPage - 1).orEmpty().take(4000)
                                        showAiDialog = true
                                        menuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Configurar IA") },
                                    leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                                    onClick = { showAiSettings = true; menuExpanded = false }
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
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                listOf(
                                    "yellow" to Color(0xFFFFD54F),
                                    "green" to Color(0xFF81C784),
                                    "blue" to Color(0xFF64B5F6),
                                    "pink" to Color(0xFFF48FB1)
                                ).forEach { (key, color) ->
                                    IconButton(onClick = { highlightColor = key; viewModel.addHighlight(selectedText, key); selectedText = "" }) {
                                        Icon(Icons.Default.Circle, null, tint = color)
                                    }
                                }
                            }
                            TextButton(onClick = { copyText(selectedText) }) {
                                Icon(Icons.Default.ContentCopy, null)
                                Text("Copiar")
                            }
                            TextButton(onClick = { showAiActions = true }) {
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

            // Controles ocultos: toque longo no leitor alterna a visibilidade.

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

private fun Modifier.readerLongPress(onLongPress: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.position
            val startTime = down.uptimeMillis
            var fired = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUpIgnoreConsumed()) break
                val dx = change.position.x - start.x
                val dy = change.position.y - start.y
                if (dx * dx + dy * dy > 1600f) break
                if (!fired && change.uptimeMillis - startTime >= 650L) {
                    fired = true
                    onLongPress()
                }
            }
        }
    }
}