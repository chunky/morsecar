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
    fun titleEndsWithTheCurrentWord() {
        val w = WordWindow.around(raw, raw.indexOf("years"), wordsBefore = 2, wordsAfter = 0)!!
        assertEquals("Ishmael. Some years", w.endingWithCurrent(40))
        assertEquals("Some years", w.endingWithCurrent(12))
        assertEquals("years", w.endingWithCurrent(6))
        assertEquals("years", w.endingWithCurrent(1))  // never drops the current word
    }

    @Test
    fun markupIsNotPartOfWords() {
        val raw = "—_Webster’s Dictionary._ Next"
        val w = WordWindow.around(raw, raw.indexOf("Dictionary"), wordsBefore = 1, wordsAfter = 1, rules = BookDisplay(TextMarkup.parse(raw)))!!
        assertEquals(listOf("Webster’s"), w.before)
        assertEquals("Dictionary.", w.current)
        assertEquals("Webster’s Dictionary.", w.endingWithCurrent(40))
    }

    @Test
    fun hiddenCharactersDontSplitWords() {
        val raw = "in Dan. _hvalt_ is * arched"
        val w = WordWindow.around(raw, raw.indexOf("is"), wordsBefore = 2, wordsAfter = 1, rules = BookDisplay(TextMarkup.parse(raw)))!!
        assertEquals(listOf("Dan.", "hvalt"), w.before)
        assertEquals("is", w.current)
        assertEquals(listOf("arched"), w.after)  // a lone "*" isn't a word
    }

    @Test
    fun emptyTextHasNoWindow() {
        assertNull(WordWindow.around("   ", 1))
    }
}
