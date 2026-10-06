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
    value.lowercase()
        .replace(Regex("""\s+"""), " ")
        .trim()

@Composable
fun ReflowReader(
    pageText: String,
    highlightText: String,
    fontSize: Float,
    lineHeightMultiplier: Float,
    backgroundMode: Int,
    modifier: Modifier = Modifier,
    onIncreaseFont: () -> Unit,
    onDecreaseFont: () -> Unit
) {
    val scrollState = rememberScrollState()
    val text = remember(pageText) { reflowPdfText(pageText) }
    val normalizedText = remember(text) { normalizedForHighlight(text) }
    val normalizedHighlight = remember(highlightText) { normalizedForHighlight(highlightText) }

    val (background, foreground, secondary) = when (backgroundMode) {
        1 -> Triple(Color(0xFFF4E8C8), Color(0xFF3F3525), Color(0xFF6F624D))
        2 -> Triple(Color(0xFF202124), Color(0xFFE8EAED), Color(0xFFB8BCC4))
        3 -> Triple(Color.Black, Color.White, Color(0xFFD0D0D0))
        else -> Triple(Color(0xFFFDFCF8), Color(0xFF202124), Color(0xFF5F6368))
    }

    val highlightStart = remember(normalizedText, normalizedHighlight) {
        if (normalizedHighlight.length < 3) -1
        else normalizedText.indexOf(normalizedHighlight)
    }

    val annotated = remember(text, highlightStart, normalizedHighlight, foreground) {
        if (highlightStart < 0) {
            buildAnnotatedString { append(text) }
        } else {
            val end = (highlightStart + normalizedHighlight.length).coerceAtMost(text.length)
            buildAnnotatedString {
                append(text.substring(0, highlightStart))
                withStyle(
                    SpanStyle(
                        background = Color(0xFFFFD54F),
                        color = Color.Black
                    )
                ) {
                    append(text.substring(highlightStart, end))
                }
                append(text.substring(end))
            }
        }
    }

    LaunchedEffect(pageText) {
        scrollState.scrollTo(0)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 22.dp, vertical = 76.dp)
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
                Text(
                    text = annotated,
                    color = foreground,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * lineHeightMultiplier).sp,
                    textAlign = TextAlign.Start,
                    style = TextStyle(
                        letterSpacing = 0.01.sp
                    )
                )
            }

            Spacer(Modifier.padding(bottom = 90.dp))
        }

        if (highlightStart >= 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 54.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFFFFD54F)
            ) {
                Text(
                    "🔊 acompanhando a leitura",
                    color = Color.Black,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        LinearProgressIndicator(
            progress = { scrollState.value.toFloat() / scrollState.maxValue.coerceAtLeast(1) },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            color = MaterialTheme.colorScheme.primary
        )
    }
}
