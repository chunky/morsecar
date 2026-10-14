package org.icculus.chunky.morsecar.morse

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {
    private val normalizer = TextNormalizer()

    private fun norm(raw: String) = normalizer.normalize(raw).text

    @Test
    fun uppercases() {
        assertEquals("HELLO WORLD", norm("Hello world"))
    }

    @Test
    fun mapsTypographicPunctuation() {
        assertEquals("\"IT'S\" - HE SAID...", norm("“It’s” — he said…"))
    }

    @Test
    fun stripsAccentsAndExpandsLigatures() {
        assertEquals("CAFE NAIVE FINE STRASSE", norm("café naïve ﬁne straße"))
    }

    @Test
    fun silentlyDropsUnencodableCharacters() {
        assertEquals("AB C", norm("a*b # c"))
        assertEquals("HI", norm("hi 😀"))
    }

    @Test
    fun collapsesWhitespaceAndTrimsEnds() {
        assertEquals("A B", norm("  a \t  b  "))
    }

    @Test
    fun singleNewlineIsAWordGap() {
        assertEquals("ONE TWO", norm("one\ntwo"))
        assertEquals("ONE TWO", norm("one\r\ntwo"))
    }

    @Test
    fun blankLineIsAParagraphGap() {
        assertEquals("ONE\nTWO", norm("one\n\ntwo"))
        assertEquals("ONE\nTWO", norm("one\r\n  \r\ntwo"))
        assertEquals("ONE\nTWO", norm("one\r\rtwo"))
        assertEquals("ONE\nTWO", norm("one two"))
    }

    @Test
    fun gapsMergeAcrossDroppedCharacters() {
        // A Gutenberg-style section divider between paragraphs.
        assertEquals("END\nSTART", norm("end\n\n*   *   *\n\nstart"))
        assertEquals("A B", norm("a * b"))
    }

    @Test
    fun emptyAndUnsendableInputGiveEmptyText() {
        assertEquals("", norm(""))
        assertEquals("", norm("  \n\n * "))
    }

    @Test
    fun mapsBackToRawOffsets() {
        val raw = "  Hi,\n\nyou…"
        val n = normalizer.normalize(raw)
        assertEquals("HI,\nYOU...", n.text)
        assertEquals(2, n.rawOffsetOf(0))   // H
        assertEquals(5, n.rawOffsetOf(3))   // paragraph gap starts at the first '\n'
        assertEquals(7, n.rawOffsetOf(4))   // Y
        assertEquals(10, n.rawOffsetOf(7))  // all three dots come from '…'
        assertEquals(10, n.rawOffsetOf(9))
        assertEquals(raw.length, n.rawOffsetOf(n.length))
    }

    @Test
    fun findsIndexForRawOffset() {
        val n = normalizer.normalize("ab  cd")
        assertEquals(0, n.indexForRawOffset(0))
        assertEquals(2, n.indexForRawOffset(2))  // the gap
        assertEquals(3, n.indexForRawOffset(3))  // inside the collapsed gap -> next char
        assertEquals(3, n.indexForRawOffset(4))
        assertEquals(n.length, n.indexForRawOffset(6))
    }

    @Test
    fun usesTheGivenAlphabet() {
        val tiny = TextNormalizer(MorseAlphabet.fromJson("""{"symbols": {"E": ".", "T": "-"}}"""))
        assertEquals("TE TET", tiny.normalize("the test").text)
    }
}
