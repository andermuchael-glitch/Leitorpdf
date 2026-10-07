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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.absoluteValue

private fun cleanBookText(raw: String): String {
    if (raw.isBlank()) return ""
    return raw
        .replace("\u00ad", "")
        .replace(Regex("""(?<=\p{L})-\s*\n\s*(?=\p{L})"""), "")
        .replace(Regex("""[ \t]+"""), " ")
        .replace(Regex("""\n[ \t]+"""), "\n")
        .replace(Regex("""[ \t]+\n"""), "\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .split("\n\n")
        .map { it.replace(Regex("""\s*\n\s*"""), " ").trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
}

private fun normalizeBook(value: String): String =
    value.lowercase().replace(Regex("""[^\p{L}\p{Nd}]+"""), "")

@Composable
fun BookReader3D(
    pageTexts: List<String>,
    selectedPage: Int,
    highlightText: String,
    fontSize: Float,
    lineHeightMultiplier: Float,
    backgroundMode: Int,
    modifier: Modifier = Modifier,
    onPageChange: (Int) -> Unit = {}
) {
    val pagerState = rememberPagerState(
        initialPage = (selectedPage - 1).coerceIn(0, (pageTexts.size - 1).coerceAtLeast(0)),
        pageCount = { pageTexts.size }
    )

    LaunchedEffect(selectedPage, pageTexts.size) {
        val target = (selectedPage - 1).coerceIn(0, (pageTexts.size - 1).coerceAtLeast(0))
        if (pagerState.currentPage != target) {
            pagerState.animateScrollToPage(target)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collectLatest { page ->
            if (pageTexts.isNotEmpty()) onPageChange(page + 1)
        }
    }

    val (bookBackground, paper, foreground, secondary) = when (backgroundMode) {
        1 -> listOf(Color(0xFFF0E1BD), Color(0xFFF8EBCB), Color(0xFF3E3425), Color(0xFF75664E))
        2 -> listOf(Color(0xFF17181B), Color(0xFF242529), Color(0xFFE9E9EC), Color(0xFFB5B7C0))
        3 -> listOf(Color.Black, Color(0xFF080808), Color.White, Color(0xFFCCCCCC))
        else -> listOf(Color(0xFFE8E8EC), Color(0xFFFFFEFA), Color(0xFF252525), Color(0xFF6D6D72))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bookBackground)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 42.dp, bottom = 58.dp),
            beyondViewportPageCount = 1,
            pageSpacing = 8.dp
        ) { page ->
            val offset = (
                (pagerState.currentPage - page) +
                    pagerState.currentPageOffsetFraction
                )
            val absoluteOffset = offset.absoluteValue

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .graphicsLayer {
                        rotationY = (offset * 18f).coerceIn(-18f, 18f)
                        cameraDistance = 28f * density
                        transformOrigin = TransformOrigin(
                            pivotFractionX = if (offset >= 0f) 1f else 0f,
                            pivotFractionY = 0.5f
                        )
                        alpha = 1f - (absoluteOffset * 0.18f).coerceIn(0f, 0.18f)
                        shadowElevation = (8f - absoluteOffset * 6f).coerceAtLeast(0f)
                    }
                    .clip(RoundedCornerShape(5.dp))
                    .background(paper)
            ) {
                val cleaned = remember(pageTexts.getOrNull(page)) {
                    cleanBookText(pageTexts.getOrNull(page).orEmpty())
                }
                val normalized = remember(cleaned) { normalizeBook(cleaned) }
                val normalizedHighlight = remember(highlightText) { normalizeBook(highlightText) }
                val start = remember(normalized, normalizedHighlight) {
                    if (normalizedHighlight.length >= 3) {
                        normalized.indexOf(normalizedHighlight)
                    } else -1
                }

                val annotated = remember(cleaned, start, normalizedHighlight) {
                    if (start < 0) {
                        buildAnnotatedString { append(cleaned) }
                    } else {
                        // O texto normalizado remove espaços; a marcação é usada
                        // apenas como sinal visual quando há correspondência exata.
                        val approximate = normalizedHighlight.length.coerceAtMost(cleaned.length - start)
                        buildAnnotatedString {
                            append(cleaned.substring(0, start))
                            withStyle(
                                SpanStyle(
                                    background = Color(0xFFFFD54F),
                                    color = Color.Black
                                )
                            ) {
                                append(cleaned.substring(start, start + approximate))
                            }
                            append(cleaned.substring(start + approximate))
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 26.dp, vertical = 28.dp)
                        .widthIn(max = 760.dp),
                    verticalArrangement = Arrangement.Top
                ) {
                    Text(
                        text = "LEITURA",
                        color = secondary,
                        style = TextStyle(
                            fontSize = 11.sp,
                            letterSpacing = 1.8.sp
                        )
                    )

                    Text(
                        text = annotated,
                        color = foreground,
                        fontFamily = FontFamily.Serif,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * lineHeightMultiplier).sp,
                        textAlign = TextAlign.Justify,
                        modifier = Modifier.padding(top = 18.dp)
                    )
                }

                Text(
                    text = (page + 1).toString(),
                    color = secondary,
                    style = TextStyle(fontSize = 11.sp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                )
            }
        }

        LinearProgressIndicator(
            progress = {
                if (pageTexts.isEmpty()) 0f
                else (pagerState.currentPage + 1f) / pageTexts.size
            },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            color = foreground
        )
    }
}

