package br.com.leitorpdf.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import br.com.leitorpdf.reader.ReadingRecorderStore
import br.com.leitorpdf.reader.ReadingStatsSnapshot
import br.com.leitorpdf.reader.ReadingStatsStore
import java.util.Locale

@Composable
fun ReadingDashboard() {
    val context = LocalContext.current
    val statsStore = remember { ReadingStatsStore(context) }
    val recorderStore = remember { ReadingRecorderStore(context) }
    var snapshot by remember { mutableStateOf(statsStore.snapshot(recorderStore.recordings())) }
    var showDetails by remember { mutableStateOf(false) }
    var showGoal by remember { mutableStateOf(false) }
    var goalText by remember { mutableStateOf(statsStore.goalMinutes().toString()) }
    var showProject by remember { mutableStateOf(false) }
    var projectText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = statsStore.snapshot(recorderStore.recordings())
            kotlinx.coroutines.delay(5000)
        }
    }

    val progress = (snapshot.todayMs / 60000f / snapshot.goalMinutes).coerceIn(0f, 1f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Minha leitura", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(formatMinutes(snapshot.todayMs) + " hoje • " + snapshot.streakDays + " dias seguidos", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { showDetails = true }) { Icon(Icons.Default.EmojiEvents, "Conquistas") }
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                if (progress >= 1f) "🎉 Meta de hoje concluída!" else "Meta: " + snapshot.goalMinutes + " min • faltam " + ((snapshot.goalMinutes - snapshot.todayMs / 60000).coerceAtLeast(0)).toInt() + " min",
                style = MaterialTheme.typography.labelMedium
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip("Semana", formatMinutes(snapshot.weekMs))
                StatChip("Mês", formatMinutes(snapshot.monthMs))
                StatChip("Total", formatMinutes(snapshot.totalMs))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showGoal = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Flag, null); Spacer(Modifier.width(5.dp)); Text("Meta")
                }
                OutlinedButton(onClick = { showProject = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Folder, null); Spacer(Modifier.width(5.dp)); Text("Projetos")
                }
            }
        }
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("Conquistas e progresso") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Documentos hoje: " + snapshot.documentsToday)
                    Text("Gravações hoje: " + snapshot.recordingsToday)
                    Text("Tempo gravado: " + formatMinutes(recorderStore.recordings().sumOf { it.durationMs }))
                    if (snapshot.achievements.isEmpty()) Text("Continue lendo para desbloquear sua primeira conquista.")
                    snapshot.achievements.forEach { Text("🏅 " + it) }
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Fechar") } }
        )
    }

    if (showGoal) {
        AlertDialog(
            onDismissRequest = { showGoal = false },
            title = { Text("Meta diária") },
            text = { OutlinedTextField(goalText, { goalText = it.filter(Char::isDigit).take(3) }, label = { Text("Minutos por dia") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { statsStore.setGoalMinutes(goalText.toIntOrNull() ?: 30); snapshot = statsStore.snapshot(recorderStore.recordings()); showGoal = false }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { showGoal = false }) { Text("Cancelar") } }
        )
    }

    if (showProject) {
        AlertDialog(
            onDismissRequest = { showProject = false },
            title = { Text("Projetos de leitura") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(projectText, { projectText = it }, label = { Text("Novo projeto") }, singleLine = true)
                    snapshot.projects.forEach { project -> Text("📚 " + project.name) }
                }
            },
            confirmButton = {
                TextButton(onClick = { if (projectText.isNotBlank()) statsStore.createProject(projectText); projectText = ""; snapshot = statsStore.snapshot(recorderStore.recordings()); showProject = false }) { Text("Criar projeto") }
            },
            dismissButton = { TextButton(onClick = { showProject = false }) { Text("Fechar") } }
        )
    }
}

@Composable
private fun RowScope.StatChip(label: String, value: String) {
    Surface(modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
        Column(Modifier.padding(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatMinutes(ms: Long): String {
    val minutes = (ms / 60000L).coerceAtLeast(0)
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) String.format(Locale.getDefault(), "%dh %02dmin", h, m) else minutes.toString() + "min"
}
