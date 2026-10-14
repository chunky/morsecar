package org.icculus.chunky.morsecar.text

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

class NotTextException(message: String) : IOException(message)

/** Turns a book file's bytes into text, guessing the encoding. */
object BookText {
    /** Larger files are refused rather than risk running out of memory. */
    const val MAX_BYTES = 16 * 1024 * 1024

    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    /**
     * Honors a UTF-8 or UTF-16 byte-order mark; otherwise uses UTF-8 if the bytes
     * are valid UTF-8, and Windows-1252 (a superset of Latin-1) if not.
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size > MAX_BYTES) throw NotTextException("File is larger than ${MAX_BYTES / (1024 * 1024)} MB")
        when {
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.startsWith(0xFF, 0xFE) -> return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.startsWith(0xFE, 0xFF) -> return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (looksBinary(bytes)) throw NotTextException("This doesn't look like a text file")
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, WINDOWS_1252)
        }
    }

    /** NUL bytes don't appear in real text files without a UTF-16 byte-order mark. */
    private fun looksBinary(bytes: ByteArray): Boolean {
        val sample = minOf(bytes.size, 8192)
        for (i in 0 until sample) if (bytes[i] == 0.toByte()) return true
        return false
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
}
