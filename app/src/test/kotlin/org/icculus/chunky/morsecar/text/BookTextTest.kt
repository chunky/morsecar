package org.icculus.chunky.morsecar.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BookTextTest {
    @Test
    fun decodesUtf8() {
        assertEquals("café “quoted”", BookText.decode("café “quoted”".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun stripsUtf8ByteOrderMark() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray()
        assertEquals("hi", BookText.decode(bytes))
    }

    @Test
    fun decodesUtf16WithByteOrderMark() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hé".toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "hé".toByteArray(Charsets.UTF_16BE)
        assertEquals("hé", BookText.decode(le))
        assertEquals("hé", BookText.decode(be))
    }

    @Test
    fun fallsBackToWindows1252ForInvalidUtf8() {
        // "café" and curly quotes in Windows-1252.
        val bytes = byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte(), ' '.code.toByte(), 0x93.toByte(), 'x'.code.toByte(), 0x94.toByte())
        assertEquals("café “x”", BookText.decode(bytes))
    }

    @Test
    fun rejectsBinary() {
        assertThrows(NotTextException::class.java) { BookText.decode(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)) }
    }

    @Test
    fun rejectsHugeFiles() {
        assertThrows(NotTextException::class.java) { BookText.decode(ByteArray(BookText.MAX_BYTES + 1) { 'a'.code.toByte() }) }
    }

    @Test
    fun emptyIsEmpty() {
        assertEquals("", BookText.decode(ByteArray(0)))
    }
}
