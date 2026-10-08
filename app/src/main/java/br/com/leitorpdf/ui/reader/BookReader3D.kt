package br.com.leitorpdf.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.leitorpdf.reader.ReadingHighlight
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.absoluteValue

private fun cleanBookText(raw: String): String = raw
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

@Composable
private fun BookPage(
    raw: String,
    pageNumber: Int,
    highlights: List<ReadingHighlight>,
    fontSize: Float,
    lineHeightMultiplier: Float,
    backgroundMode: Int,
    zoom: Float,
    margin: Float,
    onTextSelected: (String) -> Unit
) {
    val pageSelection = rememberSelectionState()
    val cleaned = remember(raw) { cleanBookText(raw) }
    val annotated = remember(cleaned, highlights) {
        buildAnnotatedString {
            append(cleaned)
            highlights.forEach { mark ->
                val index = cleaned.indexOf(mark.text, ignoreCase = true)
                if (index >= 0) addStyle(
                    SpanStyle(background = when (mark.color) { "green" -> Color(0xFF81C784); "blue" -> Color(0xFF64B5F6); "pink" -> Color(0xFFF48FB1); else -> Color(0xFFFFD54F) }, color = Color.Black),
                    index, (index + mark.text.length).coerceAtMost(cleaned.length)
                )
            }
        }
    }
    LaunchedEffect(pageSelection.selectedTexts) {
        onTextSelected(pageSelection.selectedTexts.joinToString("\n") { it.text }.trim().take(4000))
    }
    val paper = when (backgroundMode) {
        1 -> Color(0xFFF8EBCB)
        2 -> Color(0xFF242529)
        3 -> Color(0xFF080808)
        else -> Color(0xFFFFFEFA)
    }
    val foreground = when (backgroundMode) {
        1 -> Color(0xFF3E3425)
        2 -> Color(0xFFE9E9EC)
        3 -> Color.White
        4 -> Color(0xFF243447)
        5 -> Color(0xFF243424)
        6 -> Color(0xFF3D2A2E)
        else -> Color(0xFF252525)
    }
    Box(
        Modifier.fillMaxSize().padding(4.dp).clip(RoundedCornerShape(5.dp)).background(paper)
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(
                start = margin.dp, end = margin.dp, top = 28.dp, bottom = 32.dp
            ).widthIn(max = 760.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                "LEITURA  •  " + pageNumber,
                color = foreground.copy(alpha = .58f),
                style = TextStyle(fontSize = 10.sp, letterSpacing = 1.6.sp)
            )
            SelectionContainer(state = pageSelection) {
                Text(
                    annotated,
                    color = foreground,
                    fontFamily = FontFamily.Serif,
                    fontSize = (fontSize * zoom).sp,
                    lineHeight = (fontSize * zoom * lineHeightMultiplier).sp,
                    textAlign = TextAlign.Justify,
                    modifier = Modifier.padding(top = 18.dp)
                )
            }
        }
    }
}

@Composable
fun BookReader3D(
    pageTexts: List<String>,
    selectedPage: Int,
    highlights: List<ReadingHighlight>,
    fontSize: Float,
    lineHeightMultiplier: Float,
    backgroundMode: Int,
    modifier: Modifier = Modifier,
    twoPages: Boolean = false,
    concentration: Boolean = false,
    zoom: Float = 1f,
    margin: Float = 26f,
    onPageChange: (Int) -> Unit = {},
    onTextSelected: (String) -> Unit = {}
) {
    val pageCount = if (twoPages) ((pageTexts.size + 1) / 2).coerceAtLeast(1) else pageTexts.size.coerceAtLeast(1)
    val initial = if (twoPages) ((selectedPage - 1) / 2).coerceIn(0, pageCount - 1) else (selectedPage - 1).coerceIn(0, pageCount - 1)
    val pagerState = rememberPagerState(initialPage = initial, pageCount = { pageCount })

    LaunchedEffect(selectedPage, pageTexts.size, twoPages) {
        val target = if (twoPages) ((selectedPage - 1) / 2).coerceIn(0, pageCount - 1) else (selectedPage - 1).coerceIn(0, pageCount - 1)
        if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
    }
    LaunchedEffect(pagerState, twoPages) {
        snapshotFlow { pagerState.settledPage }.collectLatest { page ->
            onPageChange(if (twoPages) page * 2 + 1 else page + 1)
        }
    }

    val bookBackground = if (concentration) Color.Black else when (backgroundMode) {
        1 -> Color(0xFFE8D8B3)
        2 -> Color(0xFF17181B)
        3 -> Color.Black
        4 -> Color(0xFFDDEAF6)
        5 -> Color(0xFFDDEDDD)
        6 -> Color(0xFFF3DDE2)
        else -> Color(0xFFE8E8EC)
    }

    Box(modifier.fillMaxSize().background(bookBackground)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(top = 42.dp, bottom = 58.dp),
            beyondViewportPageCount = 1,
            pageSpacing = 8.dp
        ) { page ->
            val offset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            val absoluteOffset = offset.absoluteValue
            val start = if (twoPages) page * 2 else page
            val first = pageTexts.getOrNull(start).orEmpty()
            val second = if (twoPages) pageTexts.getOrNull(start + 1).orEmpty() else ""
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    rotationY = (offset * 18f).coerceIn(-18f, 18f)
                    cameraDistance = 28f * density
                    transformOrigin = TransformOrigin(if (offset >= 0f) 1f else 0f, .5f)
                    alpha = 1f - (absoluteOffset * .18f).coerceIn(0f, .18f)
                }
            ) {
                if (twoPages) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        BookPage(first, start + 1, highlights.filter { it.page == start + 1 }, fontSize, lineHeightMultiplier, backgroundMode, zoom, margin / 2f, onTextSelected, Modifier.weight(1f))
                        if (second.isNotBlank()) BookPage(second, start + 2, highlights.filter { it.page == start + 2 }, fontSize, lineHeightMultiplier, backgroundMode, zoom, margin / 2f, onTextSelected, Modifier.weight(1f))
                    }
                } else {
                    BookPage(first, start + 1, highlights.filter { it.page == start + 1 }, fontSize, lineHeightMultiplier, backgroundMode, zoom, margin, onTextSelected, Modifier.fillMaxSize())
                }
            }
        }
        LinearProgressIndicator(
            progress = { if (pageCount <= 0) 0f else (pagerState.currentPage + 1f) / pageCount },
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun BookPage(
    raw: String, pageNumber: Int, highlights: List<ReadingHighlight>, fontSize: Float,
    lineHeightMultiplier: Float, backgroundMode: Int, zoom: Float, margin: Float,
    onTextSelected: (String) -> Unit, modifier: Modifier
) {
    androidx.compose.runtime.key(pageNumber) {
        Box(modifier) {
            BookPage(raw, pageNumber, highlights, fontSize, lineHeightMultiplier, backgroundMode, zoom, margin, onTextSelected)
        }
    }
}
