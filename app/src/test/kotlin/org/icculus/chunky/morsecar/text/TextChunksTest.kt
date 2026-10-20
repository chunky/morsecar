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
    fun displayUnwrapsLines() {
        val raw = "one\r\ntwo\nthree"
        assertEquals("one two three", TextChunks.display(raw, TextChunks.split(raw).single()).text)
    }

    private fun rules(raw: String) = TextMarkup.parse(raw).let { it to BookDisplay(it) }

    @Test
    fun displayShowsMarkupAsStylesAndKeepsOffsetsLinedUp() {
        val raw = "* Sw. and Dan. _hval_. This *big* animal"
        val (markup, rules) = rules(raw)
        val display = TextChunks.display(raw, TextChunks.split(raw).single(), rules, markup)
        assertEquals("Sw. and Dan. hval. This big animal", display.text)
        assertEquals(
            listOf("hval" to MarkupStyle.ITALIC, "big" to MarkupStyle.BOLD),
            display.styles.map { display.text.substring(it.start, it.end) to it.style },
        )
        // The highlight for "hval." (found in raw text) lands on it in the display.
        val word = TextChunks.wordAt(raw, raw.indexOf("hval"), rules)!!
        assertEquals("hval.", display.text.substring(display.indexOf(word.first), display.indexOf(word.last + 1)))
    }

    @Test
    fun italicsAcrossALineBreak() {
        val raw = "—_Webster’s\r\nDictionary._"
        val (markup, rules) = rules(raw)
        val display = TextChunks.display(raw, TextChunks.split(raw).single(), rules, markup)
        assertEquals("—Webster’s Dictionary.", display.text)
        assertEquals("Webster’s Dictionary.", display.styles.single().let { display.text.substring(it.start, it.end) })
    }

    @Test
    fun skipsChunksWithNothingToShow() {
        val raw = "End of chapter.\n\n*       *       *\n\nNext chapter."
        assertEquals(listOf("End of chapter.", "Next chapter."), TextChunks.split(raw, rules = rules(raw).second).map { raw.substring(it.start, it.end) })
    }

    @Test
    fun dashesSeparateWords() {
        val raw = "places—stem for"
        val rules = rules(raw).second
        assertEquals("places", raw.substring(TextChunks.wordAt(raw, 2, rules)!!))
        assertEquals("stem", raw.substring(TextChunks.wordAt(raw, raw.indexOf("stem"), rules)!!))
    }
}
