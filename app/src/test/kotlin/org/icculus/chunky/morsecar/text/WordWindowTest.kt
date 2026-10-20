package org.icculus.chunky.morsecar.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordWindowTest {
    private val raw = "Call me Ishmael.  Some years ago—never mind\nhow long precisely"

    @Test
    fun wordsEitherSide() {
        val w = WordWindow.around(raw, raw.indexOf("years"), wordsBefore = 2, wordsAfter = 2)!!
        assertEquals(listOf("Ishmael.", "Some"), w.before)
        assertEquals("years", w.current)
        assertEquals(listOf("ago—never", "mind"), w.after)
        assertEquals(raw.indexOf("years"), w.currentStart)
    }

    @Test
    fun stopsAtTheEnds() {
        val first = WordWindow.around(raw, 0)!!
        assertEquals(emptyList<String>(), first.before)
        assertEquals("Call", first.current)
        val last = WordWindow.around(raw, raw.length - 1)!!
        assertEquals("precisely", last.current)
        assertEquals(emptyList<String>(), last.after)
        assertEquals(listOf("mind", "how", "long"), last.before.takeLast(3))
    }

    @Test
    fun inAGapTheCurrentWordIsTheOneBefore() {
        val w = WordWindow.around(raw, raw.indexOf("  Some") + 1)!!
        assertEquals("Ishmael.", w.current)
    }

    @Test
    fun crossesLineBreaks() {
        val w = WordWindow.around(raw, raw.indexOf("how"), wordsBefore = 1, wordsAfter = 1)!!
        assertEquals(listOf("mind"), w.before)
        assertEquals(listOf("long"), w.after)
    }

    @Test
    fun emptyTextHasNoWindow() {
        assertNull(WordWindow.around("   ", 1))
    }
}
