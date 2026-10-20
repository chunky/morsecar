package org.icculus.chunky.morsecar.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.icculus.chunky.morsecar.playback.NowPlaying
import org.icculus.chunky.morsecar.text.DisplayText
import org.icculus.chunky.morsecar.text.MarkupStyle
import org.icculus.chunky.morsecar.text.TextChunks
import kotlin.math.abs

/**
 * The book's text, scrolling to keep the word being sent about a third of the
 * way down, with that word highlighted. Scrolling by hand stops the following
 * until "Follow along" is tapped.
 */
@Composable
fun BookTextView(book: NowPlaying.Book, rawPosition: Int, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    var following by remember(book.id) { mutableStateOf(true) }
    val layouts = remember(book.id) { HashMap<Int, TextLayoutResult>() }
    val displays = remember(book.id) { HashMap<Int, DisplayText>() }
    val displayOf = { i: Int -> displays.getOrPut(i) { TextChunks.display(book.raw, book.chunks[i], book.display, book.markup) } }
    val chunkIndex = TextChunks.indexOfChunk(book.chunks, rawPosition).coerceAtLeast(0)
    val word = TextChunks.wordAt(book.raw, rawPosition, book.display)
    val highlight = SpanStyle(
        background = MaterialTheme.colorScheme.primaryContainer,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) following = false
        }
    }
    LaunchedEffect(book.id, chunkIndex, word?.first, following) {
        if (following && book.chunks.isNotEmpty()) scrollToWord(listState, layouts, displayOf(chunkIndex), chunkIndex, word)
    }

    Box(modifier) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(book.chunks) { i, chunk ->
                val wordHere = word?.takeIf { i == chunkIndex && it.first < chunk.end && it.last >= chunk.start }
                ChunkText(displayOf(i), wordHere, highlight, onLayout = { layouts[i] = it })
            }
        }
        if (!following) {
            Button(
                onClick = { following = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) { Text("Follow along") }
        }
    }
}

@Composable
private fun ChunkText(
    display: DisplayText,
    word: IntRange?,
    highlight: SpanStyle,
    onLayout: (TextLayoutResult) -> Unit,
) {
    val text = if (word == null && display.styles.isEmpty()) {
        AnnotatedString(display.text)
    } else {
        buildAnnotatedString {
            append(display.text)
            for (s in display.styles) {
                addStyle(
                    when (s.style) {
                        MarkupStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                        MarkupStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    },
                    s.start, s.end,
                )
            }
            if (word != null) {
                val start = display.indexOf(word.first).coerceIn(0, display.text.length)
                val end = display.indexOf(word.last + 1).coerceIn(start, display.text.length)
                addStyle(highlight, start, end)
            }
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (word == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        onTextLayout = onLayout,
    )
}

/** Scrolls so the line holding [word] sits a third of the way down the view. */
private suspend fun scrollToWord(
    listState: LazyListState,
    layouts: Map<Int, TextLayoutResult>,
    display: DisplayText,
    chunkIndex: Int,
    word: IntRange?,
) {
    if (listState.layoutInfo.visibleItemsInfo.none { it.index == chunkIndex }) {
        listState.scrollToItem(chunkIndex)
        withFrameNanos { }  // let the chunk lay out so its lines can be measured
    }
    val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == chunkIndex } ?: return
    val layout = layouts[chunkIndex]
    val lineTop = if (layout != null && word != null) {
        val textLength = layout.layoutInput.text.length
        val offset = display.indexOf(word.first).coerceIn(0, (textLength - 1).coerceAtLeast(0))
        layout.getLineTop(layout.getLineForOffset(offset))
    } else {
        0f
    }
    val target = listState.layoutInfo.viewportSize.height / 3f
    val delta = item.offset + lineTop - target
    if (abs(delta) >= 1f) listState.animateScrollBy(delta)
}
