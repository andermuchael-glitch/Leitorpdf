package br.com.leitorpdf.ui.reader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import br.com.leitorpdf.reader.ReadingRecorderService
import br.com.leitorpdf.reader.ReadingRecorderStore
import br.com.leitorpdf.reader.ReadingRecording
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

@Composable
fun ReadingRecorderPanel(uri: Uri, bookTitle: String, page: Int) {
    val context = LocalContext.current
    val store = remember { ReadingRecorderStore(context) }
    var status by remember { mutableStateOf(store.state()) }
    var recordings by remember { mutableStateOf(store.recordings()) }
    var showStart by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var musicUri by remember { mutableStateOf<Uri?>(null) }
    var musicName by remember { mutableStateOf("") }
    var musicVolume by remember { mutableFloatStateOf(.12f) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingId by remember { mutableStateOf<Long?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showStart = true
        else android.widget.Toast.makeText(context, "Permissão do microfone é necessária para gravar.", android.widget.Toast.LENGTH_LONG).show()
    }
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            musicUri = picked
            musicName = picked.lastPathSegment?.substringAfterLast('/')?.take(50) ?: "Trilha selecionada"
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            status = store.state()
            recordings = store.recordings()
            delay(500)
        }
    }
    DisposableEffect(Unit) { onDispose { player?.release() } }

    fun send(action: String) = context.startService(Intent(context, ReadingRecorderService::class.java).setAction(action).apply { if (action == ReadingRecorderService.ACTION_STOP) putExtra(ReadingRecorderService.EXTRA_END_PAGE, page) })
    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val intent = Intent(context, ReadingRecorderService::class.java)
            .setAction(ReadingRecorderService.ACTION_START)
            .putExtra(ReadingRecorderService.EXTRA_URI, uri.toString())
            .putExtra(ReadingRecorderService.EXTRA_BOOK, bookTitle)
            .putExtra(ReadingRecorderService.EXTRA_START_PAGE, page)
            .putExtra(ReadingRecorderService.EXTRA_MUSIC, musicUri?.toString())
            .putExtra(ReadingRecorderService.EXTRA_MUSIC_VOLUME, musicVolume)
        if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        showStart = false
    }
    fun play(recording: ReadingRecording) {
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(recording.filePath); prepare()
            setOnCompletionListener { playingId = null; release(); player = null }
            start()
        }
        playingId = recording.id
    }
    fun share(recording: ReadingRecording) {
        val file = File(recording.filePath)
        if (!file.exists()) return
        val contentUri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Exportar gravação"))
    }

    if (showStart) {
        AlertDialog(
            onDismissRequest = { showStart = false },
            title = { Text("🎙️ Gravar leitura") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("A gravação fica vinculada a este PDF e começa na página " + page + ".")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MusicNote, null)
                        TextButton(onClick = { musicPicker.launch(arrayOf("audio/*")) }) {
                            Text(if (musicName.isBlank()) "Escolher música de fundo" else musicName)
                        }
                    }
                    Text("Volume da música: " + (musicVolume * 100).toInt() + "%")
                    Slider(musicVolume, { musicVolume = it }, valueRange = 0f..0.35f)
                    Text("A trilha toca durante a sessão para criar um ambiente de leitura. A voz é salva em M4A.")
                }
            },
            confirmButton = { Button(onClick = ::start) { Icon(Icons.Default.Radio, null); Spacer(Modifier.width(6.dp)); Text("Começar") } },
            dismissButton = { TextButton(onClick = { showStart = false }) { Text("Cancelar") } }
        )
    }

    if (showList) {
        AlertDialog(
            onDismissRequest = { showList = false },
            title = { Text("🎙️ Minhas gravações") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 480.dp)) {
                    if (recordings.isEmpty()) item { Text("Nenhuma gravação ainda.") }
                    items(recordings) { recording ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(recording.bookTitle, maxLines = 1)
                                Text(formatMs(recording.durationMs) + " • pág. " + recording.startPage, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { play(recording) }) { Icon(Icons.Default.PlayArrow, "Ouvir") }
                            IconButton(onClick = { share(recording) }) { Icon(Icons.Default.Share, "Exportar") }
                            IconButton(onClick = { File(recording.filePath).delete(); store.remove(recording.id); recordings = store.recordings() }) { Icon(Icons.Default.DeleteOutline, "Excluir") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showList = false }) { Text("Fechar") } }
        )
    }

    if (status.recording) {
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.RecordVoiceOver, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(if (status.paused) "Gravação pausada" else "Gravando leitura", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(formatMs(status.elapsedMs), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { send(if (status.paused) ReadingRecorderService.ACTION_RESUME else ReadingRecorderService.ACTION_PAUSE) }) {
                    Icon(if (status.paused) Icons.Default.PlayArrow else Icons.Default.Pause, "Pausar")
                }
                IconButton(onClick = { send(ReadingRecorderService.ACTION_STOP) }) { Icon(Icons.Default.Stop, "Finalizar") }
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { showStart = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.RecordVoiceOver, null); Spacer(Modifier.width(6.dp)); Text("Gravar")
            }
            OutlinedButton(onClick = { showList = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Folder, null); Spacer(Modifier.width(6.dp)); Text("Gravações")
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
    else String.format(Locale.getDefault(), "%02d:%02d", m, s)
}
