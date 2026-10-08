package br.com.leitorpdf.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import br.com.leitorpdf.reader.ReadingHighlight

private fun reflowPdfText(raw: String): String {
    if (raw.isBlank()) return ""
    return raw
        .replace("\u00ad", "")
        .replace(Regex("""(?<=\p{L})-\s*\n\s*(?=\p{L})"""), "")
        .replace(Regex("""[ \t]+"""), " ")
        .replace(Regex("""\n[ \t]+"""), "\n")
        .replace(Regex("""[ \t]+\n"""), "\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .split("\n\n")
        .map { paragraph ->
            paragraph
                .replace(Regex("""\s*\n\s*"""), " ")
                .replace(Regex(""" {2,}"""), " ")
                .trim()
        }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
}

private fun normalizedForHighlight(value: String): String =
    value.lowercase().replace(Regex("""\s+"""), " ").trim()

@Composable
fun ReflowReader(
    pageText: String,
    highlights: List<ReadingHighlight>,
    fontSize: Float,
    lineHeightMultiplier: Float,
    backgroundMode: Int,
    modifier: Modifier = Modifier,
    onIncreaseFont: () -> Unit,
    onDecreaseFont: () -> Unit,
    onTextSelected: (String) -> Unit = {},
    concentration: Boolean = false,
    zoom: Float = 1f,
    margin: Float = 22f
) {
    val scrollState = rememberScrollState()
    val selectionState = rememberSelectionState()
    val text = remember(pageText) { reflowPdfText(pageText) }

    val (background, foreground, secondary) = when (backgroundMode) {
        1 -> Triple(Color(0xFFF4E8C8), Color(0xFF3F3525), Color(0xFF6F624D))
        2 -> Triple(Color(0xFF202124), Color(0xFFE8EAED), Color(0xFFB8BCC4))
        3 -> Triple(Color.Black, Color.White, Color(0xFFD0D0D0))
        else -> Triple(Color(0xFFFDFCF8), Color(0xFF202124), Color(0xFF5F6368))
    }

    val annotated = remember(text, highlights, foreground) {
        buildAnnotatedString {
            append(text)
            highlights.forEach { mark ->
                val needle = normalizedForHighlight(mark.text)
                if (needle.length >= 3) {
                    var from = 0
                    while (from < text.length) {
                        val index = normalizedForHighlight(text.substring(from)).indexOf(needle)
                        if (index < 0) break
                        val start = from + index
                        val end = (start + mark.text.length).coerceAtMost(text.length)
                        addStyle(
                            SpanStyle(background = when (mark.color) { "green" -> Color(0xFF81C784); "blue" -> Color(0xFF64B5F6); "pink" -> Color(0xFFF48FB1); else -> Color(0xFFFFD54F) }, color = Color.Black),
                            start,
                            end
                        )
                        from = end
                    }
                }
            }
        }
    }

    LaunchedEffect(pageText) {
        scrollState.scrollTo(0)
        selectionState.clear()
    }

    LaunchedEffect(selectionState.selectedTexts) {
        val selected = selectionState.selectedTexts.joinToString("\n") { it.text }.trim()
        onTextSelected(selected.take(4000))
    }

    Box(
        modifier = modifier.fillMaxSize().background(background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = margin.dp, vertical = 76.dp)
                .widthIn(max = 720.dp)
                .align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Modo leitura",
                    style = MaterialTheme.typography.labelLarge,
                    color = secondary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDecreaseFont) {
                    Icon(Icons.Default.FormatSize, "Diminuir texto", tint = secondary)
                }
                IconButton(onClick = onIncreaseFont) {
                    Icon(Icons.Default.FormatSize, "Aumentar texto", tint = foreground)
                }
            }

            if (text.isBlank()) {
                Text(
                    "Não foi possível extrair texto desta página.",
                    color = foreground,
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                SelectionContainer(state = selectionState) {
                    Text(
                        text = annotated,
                        color = foreground,
                        fontSize = (fontSize * zoom).sp,
                        lineHeight = (fontSize * zoom * lineHeightMultiplier).sp,
                        textAlign = TextAlign.Start,
                        style = TextStyle(letterSpacing = 0.01.sp)
                    )
                }
            }

            Spacer(Modifier.padding(bottom = 90.dp))
        }

        if (selectionState.selectedTexts.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 52.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    "Texto selecionado • use as ações acima",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        LinearProgressIndicator(
            progress = { scrollState.value.toFloat() / scrollState.maxValue.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
            color = MaterialTheme.colorScheme.primary
        )
    }
}
