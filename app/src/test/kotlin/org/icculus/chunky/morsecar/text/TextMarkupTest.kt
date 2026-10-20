package org.icculus.chunky.morsecar.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMarkupTest {
    /** Each span as its text and style, e.g. "hval:ITALIC". */
    private fun spans(raw: String) = TextMarkup.parse(raw).spans.map { "${raw.substring(it.start, it.end)}:${it.style}" }

    private fun markers(raw: String): String {
        val m = TextMarkup.parse(raw)
        return raw.indices.joinToString("") { if (m.isMarker(it)) "^" else " " }.trimEnd()
    }

    @Test
    fun gutenbergItalics() {
        assertEquals(listOf("hval:ITALIC"), spans("Sw. and Dan. _hval_. This animal"))
        assertEquals(listOf("Hackluyt.:ITALIC"), spans("  —_Hackluyt._\r\n"))
        assertEquals(listOf("Walw-ian:ITALIC"), spans("A.S. _Walw-ian_, to roll"))
        assertEquals("             ^    ^", markers("Sw. and Dan. _hval_. This"))
    }

    @Test
    fun italicsSpanLineBreaksButNotParagraphs() {
        assertEquals(listOf("Webster’s\r\nDictionary.:ITALIC"), spans("—_Webster’s\r\nDictionary._"))
        assertEquals(emptyList<String>(), spans("_one paragraph\r\n\r\nanother_"))
    }

    @Test
    fun boldForms() {
        assertEquals(
            listOf("a:BOLD", "b:BOLD", "c:BOLD", "d:BOLD"),
            spans("*a* then **b** then __c__ then =d= end"),
        )
    }

    @Test
    fun boldInsideItalics() {
        assertEquals(listOf("very *loud* words:ITALIC", "loud:BOLD"), spans("_very *loud* words_"))
    }

    @Test
    fun ignoresThingsThatArentFormatting() {
        assertEquals(emptyList<String>(), spans("snake_case and file_name_here"))
        assertEquals(emptyList<String>(), spans("a = b and c=d"))
        assertEquals(emptyList<String>(), spans("* Sw. and Dan. footnote"))
        assertEquals(emptyList<String>(), spans("*       *       *"))
        assertEquals(emptyList<String>(), spans("___ a line ___"))
        assertFalse(TextMarkup.parse("a = b").isMarker(2))
    }

    @Test
    fun findsSpansInARange() {
        val raw = "_a_ x _b_ y _c_"
        val m = TextMarkup.parse(raw)
        assertEquals(listOf("b"), m.spansIn(raw.indexOf("b"), raw.indexOf("b") + 1).map { raw.substring(it.start, it.end) })
        assertEquals(3, m.spansIn(0, raw.length).size)
        assertTrue(m.spansIn(raw.indexOf("x"), raw.indexOf("x") + 1).isEmpty())
    }
}
