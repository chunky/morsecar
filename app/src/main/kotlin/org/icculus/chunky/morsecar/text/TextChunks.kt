package org.icculus.chunky.morsecar.text

/** A displayable piece of the raw book text: `raw.substring(start, end)`. */
data class TextChunk(val start: Int, val end: Int)

/**
 * Splits raw book text into paragraphs for display, and finds the word being
 * sent. Chunks are capped in length so even a book with no blank lines is
 * shown as many small pieces rather than one enormous one.
 */
object TextChunks {
    const val DEFAULT_MAX_LENGTH = 600

    fun split(raw: String, maxLength: Int = DEFAULT_MAX_LENGTH): List<TextChunk> {
        require(maxLength > 0)
        val chunks = ArrayList<TextChunk>()
        var i = 0
        while (i < raw.length) {
            // Skip whitespace between paragraphs.
            while (i < raw.length && raw[i].isWhitespace()) i++
            if (i >= raw.length) break
            val paragraphEnd = findParagraphEnd(raw, i)
            var start = i
            while (start < paragraphEnd) {
                var end = paragraphEnd
                if (end - start > maxLength) {
                    // Break at the last whitespace that keeps the chunk short enough.
                    end = start + maxLength
                    var j = end
                    while (j > start && !raw[j].isWhitespace()) j--
                    if (j > start) end = j
                }
                var trimmedEnd = end
                while (trimmedEnd > start && raw[trimmedEnd - 1].isWhitespace()) trimmedEnd--
                chunks.add(TextChunk(start, trimmedEnd))
                start = end
                while (start < paragraphEnd && raw[start].isWhitespace()) start++
            }
            i = paragraphEnd
        }
        return chunks
    }

    /** End of the paragraph starting at [start]: the first blank line, or the end of the text. */
    private fun findParagraphEnd(raw: String, start: Int): Int {
        var i = start
        while (i < raw.length) {
            if (raw[i] == '\n' || raw[i] == '\r') {
                // A blank line is a line break followed by only whitespace and another line break.
                var j = i + 1
                if (raw[i] == '\r' && raw.getOrNull(j) == '\n') j++
                while (j < raw.length && raw[j].isWhitespace() && raw[j] != '\n' && raw[j] != '\r') j++
                if (j < raw.length && (raw[j] == '\n' || raw[j] == '\r')) return i
            }
            i++
        }
        return raw.length
    }

    /** The chunk containing [offset], or the nearest one before it; -1 if there are none before it. */
    fun indexOfChunk(chunks: List<TextChunk>, offset: Int): Int {
        var lo = 0
        var hi = chunks.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (chunks[mid].start <= offset) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /**
     * The whitespace-delimited word at [offset]. In whitespace, this is the word
     * just before it, so the highlight stays put during the gap after a word.
     */
    fun wordAt(raw: String, offset: Int): IntRange? {
        var i = offset.coerceAtMost(raw.length - 1)
        while (i >= 0 && raw[i].isWhitespace()) i--
        if (i < 0) return null
        var start = i
        while (start > 0 && !raw[start - 1].isWhitespace()) start--
        var end = i
        while (end + 1 < raw.length && !raw[end + 1].isWhitespace()) end++
        return start..end
    }

    /** The chunk's text with hard line wraps turned into spaces; same length, so offsets still line up. */
    fun displayText(raw: String, chunk: TextChunk): String =
        raw.substring(chunk.start, chunk.end).replace('\n', ' ').replace('\r', ' ')
}
