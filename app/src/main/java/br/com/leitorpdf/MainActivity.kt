package br.com.leitorpdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.leitorpdf.reader.ReaderViewModel
import br.com.leitorpdf.ui.reader.ReaderScreen
import br.com.leitorpdf.ui.theme.LeitorPdfTheme

private val ListenOrange = Color(0xFFFF5A1F)
private val ListenPeach = Color(0xFFFFF3ED)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val themePrefs = getSharedPreferences("app_preferences", MODE_PRIVATE)
            var darkTheme by remember { mutableStateOf(themePrefs.getBoolean("dark_theme", false)) }
            LeitorPdfTheme(darkTheme = darkTheme) {
                LeitorPdfApp(
                    initialUri = intent?.data,
                    activity = this,
                    darkTheme = darkTheme,
                    onDarkThemeChange = {
                        darkTheme = it
                        themePrefs.edit().putBoolean("dark_theme", it).apply()
                    }
                )
            }
        }
    }
}

@Composable
private fun LeitorPdfApp(
    initialUri: Uri?,
    activity: ComponentActivity,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit
) {
    val prefs = activity.getSharedPreferences(
        "reading_progress",
        android.content.Context.MODE_PRIVATE
    )

    val rememberedUri = remember {
        initialUri ?: prefs.getString("last_uri", null)?.let(Uri::parse)
    }

    var selectedUri by remember { mutableStateOf(rememberedUri) }
    var textContent by remember { mutableStateOf<String?>(null) }
    var selectedName by remember {
        mutableStateOf(
            if (initialUri != null) {
                "Documento PDF"
            } else {
                prefs.getString("last_name", "Documento PDF") ?: "Documento PDF"
            }
        )
    }

    val readerViewModel: ReaderViewModel = viewModel()

    var showTextDialog by remember { mutableStateOf(false) }
    var showWebDialog by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            Toast.makeText(activity, "Página capturada. OCR da imagem será adicionado depois.", Toast.LENGTH_LONG).show()
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }

            val name = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() }
                ?: "Documento PDF"

            prefs.edit()
                .putString("last_uri", uri.toString())
                .putString("last_name", name)
                .apply()

            selectedUri = uri
            selectedName = name
        }
    }

    if (showTextDialog) {
        TextInputDialog(
            onDismiss = { showTextDialog = false },
            onOpen = { value -> textContent = value; showTextDialog = false }
        )
    }
    if (showWebDialog) {
        WebInputDialog(
            onDismiss = { showWebDialog = false },
            onOpen = { value ->
                showWebDialog = false
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) }
                    .onFailure { Toast.makeText(activity, "Não foi possível abrir o endereço.", Toast.LENGTH_SHORT).show() }
            }
        )
    }

    if (textContent != null && selectedUri == null) {
        TextReaderScreen(textContent.orEmpty(), darkTheme, onBack = { textContent = null })
    } else if (selectedUri != null) {
        ReaderScreen(
            uri = selectedUri!!,
            fileName = selectedName,
            viewModel = readerViewModel,
            onBack = {
                selectedUri = null
            },
            onOpenAnotherPdf = {
                picker.launch(arrayOf("application/pdf"))
            }
        )
    } else {
        HomeScreen(
            lastName = prefs.getString("last_name", null),
            lastPage = prefs.getInt("page", 1),
            hasLastDocument = prefs.getString("last_uri", null) != null,
            onOpenPdf = { picker.launch(arrayOf("application/pdf")) },
            onOpenText = { showTextDialog = true },
            onOpenWeb = { showWebDialog = true },
            onScan = { cameraLauncher.launch() },
            darkTheme = darkTheme,
            onDarkThemeChange = onDarkThemeChange,
            onContinue = {
                prefs.getString("last_uri", null)
                    ?.let(Uri::parse)
                    ?.let {
                        selectedUri = it
                        selectedName = prefs.getString("last_name", "Documento PDF")
                            ?: "Documento PDF"
                    }
            }
        )
    }
}

@Composable
private fun HomeScreen(
    lastName: String?,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    lastPage: Int,
    hasLastDocument: Boolean,
    onOpenPdf: () -> Unit,
    onOpenText: () -> Unit,
    onOpenWeb: () -> Unit,
    onScan: () -> Unit,
    onContinue: () -> Unit
) {
    var searchMode by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = ListenPeach
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "LeitorPDF",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Leitura inteligente • PDF + IA",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = { searchMode = !searchMode }) {
                    Icon(Icons.Default.Search, "Pesquisar")
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, "Configurações")
                }
            }

            if (searchMode) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White
                ) {
                    Text(
                        "Pesquisa da biblioteca em breve",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Text(
                "O que você quer ler?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ImportTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.PictureAsPdf,
                    title = "Importar PDF",
                    subtitle = "Do celular",
                    onClick = onOpenPdf
                )
                ImportTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.TextSnippet,
                    title = "Texto",
                    subtitle = "Colar conteúdo",
                    onClick = onOpenText
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ImportTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Link,
                    title = "Página web",
                    subtitle = "Abrir no navegador",
                    onClick = onOpenWeb
                )
                ImportTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.CameraAlt,
                    title = "Escanear",
                    subtitle = "Capturar página",
                    onClick = onScan
                )
            }

            Spacer(Modifier.height(26.dp))

            if (hasLastDocument && lastName != null) {
                Text(
                    "Continue lendo",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(10.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    onClick = onContinue
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(ListenOrange),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.AutoStories,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 14.dp)
                        ) {
                            Text(
                                lastName,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Continuar na página $lastPage",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(7.dp))
                            LinearProgressIndicator(
                                progress = { 0.18f },
                                modifier = Modifier.fillMaxWidth(),
                                color = ListenOrange,
                                trackColor = Color(0xFFE8E8E8)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(ListenOrange),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.ArrowForward,
                                null,
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(26.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Minha biblioteca",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onOpenPdf) {
                    Text("Adicionar")
                }
            }

            if (hasLastDocument && lastName != null) {
                LibraryItem(
                    title = lastName,
                    subtitle = "PDF • leitura inteligente",
                    onClick = onContinue
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.AutoStories,
                            null,
                            tint = ListenOrange,
                            modifier = Modifier.size(38.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Sua biblioteca está vazia",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Adicione um PDF para começar sua leitura.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    listOf(
                        "Modo livro 3D",
                        "Marcações coloridas",
                        "IA para leitura",
                        "Modo concentração"
                    )
                ) { feature ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.White
                    ) {
                        Text(
                            feature,
                            modifier = Modifier.padding(
                                horizontal = 13.dp,
                                vertical = 8.dp
                            ),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Configurações") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Aparência do aplicativo", fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !darkTheme, onClick = { onDarkThemeChange(false) }, label = { Text("Claro") })
                        FilterChip(selected = darkTheme, onClick = { onDarkThemeChange(true) }, label = { Text("Escuro") })
                    }
                    Text("No leitor, você também pode escolher tons de papel, sépia, azul e verde.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Concluir") } }
        )
    }
}

@Composable
private fun ImportTile(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(15.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFE3D8)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    null,
                    tint = ListenOrange,
                    modifier = Modifier.size(21.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(title, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LibraryItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFFFE3D8)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Description,
                    null,
                    tint = ListenOrange
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                Icons.Default.ArrowForward,
                null,
                tint = ListenOrange
            )

            IconButton(onClick = {}) {
                Icon(Icons.Default.MoreHoriz, "Mais opções")
            }
        }
    }
}


@Composable
private fun TextInputDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ler texto") },
        text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 6, label = { Text("Cole ou digite o texto") }) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onOpen(text.trim()) }) { Text("Ler") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun WebInputDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var url by remember { mutableStateOf("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Abrir página web") },
        text = { OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Endereço") }) },
        confirmButton = { TextButton(enabled = url.startsWith("http://") || url.startsWith("https://"), onClick = { onOpen(url.trim()) }) { Text("Abrir") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun TextReaderScreen(text: String, darkTheme: Boolean, onBack: () -> Unit) {
    var tone by remember { mutableIntStateOf(if (darkTheme) 2 else 0) }
    val (bg, fg) = when (tone) {
        1 -> Color(0xFFF4E8C8) to Color(0xFF3F3525)
        2 -> Color(0xFF202124) to Color(0xFFE8EAED)
        3 -> Color(0xFFEAF2FA) to Color(0xFF243447)
        4 -> Color(0xFFEAF4EA) to Color(0xFF243424)
        else -> Color(0xFFFDFCF8) to Color(0xFF202124)
    }
    Scaffold(containerColor = bg) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Voltar", tint = fg) }
                Text("Leitura de texto", Modifier.weight(1f), color = fg, fontWeight = FontWeight.Bold)
                IconButton(onClick = { tone = (tone + 1) % 5 }) { Icon(Icons.Default.Palette, "Mudar tom", tint = fg) }
            }
            androidx.compose.foundation.text.selection.SelectionContainer {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
                    item {
                        Text(text, color = fg, fontSize = 20.sp, lineHeight = 31.sp, modifier = Modifier.padding(top = 14.dp))
                    }
                }
            }
        }
    }
}
