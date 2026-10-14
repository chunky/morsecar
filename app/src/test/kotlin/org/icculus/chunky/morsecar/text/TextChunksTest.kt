package org.icculus.chunky.morsecar.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunksTest {
    private fun texts(raw: String, max: Int = 600) = TextChunks.split(raw, max).map { raw.substring(it.start, it.end) }

    @Test
    fun splitsOnBlankLines() {
        assertEquals(listOf("One line\nwrapped.", "Two."), texts("One line\nwrapped.\n\nTwo.\n"))
        assertEquals(listOf("A", "B"), texts("\r\n  A\r\n \r\nB  "))
    }

    @Test
    fun capsLongParagraphsAtWordBoundaries() {
        val raw = "aaa bbb ccc ddd eee"
        val chunks = texts(raw, max = 8)
        assertEquals(listOf("aaa bbb", "ccc ddd", "eee"), chunks)
        assertTrue(chunks.all { it.length <= 8 })
    }

    @Test
    fun hardSplitsWordsLongerThanTheCap() {
        assertEquals(listOf("abcd", "efgh", "ij"), texts("abcdefghij", max = 4))
    }

    @Test
    fun chunksCoverAllNonWhitespaceInOrder() {
        val raw = buildString { repeat(500) { append("word$it "); if (it % 37 == 0) append("\n\n") } }
        val chunks = TextChunks.split(raw, 100)
        assertEquals(raw.filterNot { it.isWhitespace() }, chunks.joinToString("") { raw.substring(it.start, it.end) }.filterNot { it.isWhitespace() })
        assertTrue(chunks.zipWithNext().all { (a, b) -> a.end <= b.start })
    }

    @Test
    fun emptyTextHasNoChunks() {
        assertEquals(emptyList<TextChunk>(), TextChunks.split("  \n\n "))
    }

    @Test
    fun findsChunkForOffset() {
        val chunks = listOf(TextChunk(2, 5), TextChunk(8, 12))
        assertEquals(-1, TextChunks.indexOfChunk(chunks, 0))
        assertEquals(0, TextChunks.indexOfChunk(chunks, 2))
        assertEquals(0, TextChunks.indexOfChunk(chunks, 6))  // between chunks: the one before
        assertEquals(1, TextChunks.indexOfChunk(chunks, 8))
        assertEquals(1, TextChunks.indexOfChunk(chunks, 100))
    }

    @Test
    fun wordAtOffsetOrJustBefore() {
        val raw = "Hello,  world!\n\nNext"
        assertEquals(0..5, TextChunks.wordAt(raw, 0))
        assertEquals(0..5, TextChunks.wordAt(raw, 3))
        assertEquals(0..5, TextChunks.wordAt(raw, 6))    // in the gap: previous word
        assertEquals(8..13, TextChunks.wordAt(raw, 8))
        assertEquals(16..19, TextChunks.wordAt(raw, raw.length))
        assertNull(TextChunks.wordAt("   ", 1))
        assertNull(TextChunks.wordAt("", 0))
    }

    @Test
    fun displayTextUnwrapsLinesWithoutChangingLength() {
        val raw = "one\r\ntwo\nthree"
        val chunk = TextChunks.split(raw).single()
        val display = TextChunks.displayText(raw, chunk)
        assertEquals("one  two three", display)
        assertEquals(chunk.end - chunk.start, display.length)
    }
}
