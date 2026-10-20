package org.icculus.chunky.morsecar.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GutenbergTextTest {
    private val book = "The Project Gutenberg eBook of Moby Dick; Or, The Whale\r\n\r\n" +
        "Title: Moby Dick; Or, The Whale\r\n\r\nAuthor: Herman Melville\r\n\r\n" +
        "*** START OF THE PROJECT GUTENBERG EBOOK MOBY DICK; OR, THE WHALE ***\r\n" +
        "\r\nCall me Ishmael.\r\n"

    @Test
    fun findsTitle() {
        assertEquals("Moby Dick; Or, The Whale", GutenbergText.title(book))
    }

    @Test
    fun findsAuthor() {
        assertEquals("Herman Melville", GutenbergText.author(book))
        assertNull(GutenbergText.author("Author: Nobody\nNo Gutenberg header here."))
    }

    @Test
    fun findsBodyStart() {
        assertEquals("\r\nCall me Ishmael.\r\n", book.substring(GutenbergText.bodyStart(book)))
    }

    @Test
    fun acceptsOlderMarkerWording() {
        val old = "Title: X\n*** START OF THIS PROJECT GUTENBERG EBOOK X ***\nBody"
        assertEquals("X", GutenbergText.title(old))
        assertEquals("Body", old.substring(GutenbergText.bodyStart(old)))
    }

    @Test
    fun ordinaryTextIsLeftAlone() {
        val plain = "Title: Not a header\nJust a story."
        assertNull(GutenbergText.title(plain))
        assertEquals(0, GutenbergText.bodyStart(plain))
    }
}
