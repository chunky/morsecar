package org.icculus.chunky.morsecar.text

/** A displayable piece of the raw book text: `raw.substring(start, end)`. */
data class TextChunk(val start: Int, val end: Int)

/**
 * How text divides into words. Separators split words; hidden characters are
 * transparent: they don't split a word (`_hval_.` is one word), they just
 * aren't part of what's shown, and a "word" of only hidden characters isn't one.
 */
interface WordRules {
    fun isSeparator(raw: String, i: Int): Boolean
    fun isShown(raw: String, i: Int): Boolean
    fun isWordChar(raw: String, i: Int): Boolean = !isSeparator(raw, i) && isShown(raw, i)
}

/** Whitespace-separated words, everything shown. */
object PlainWords : WordRules {
    override fun isSeparator(raw: String, i: Int) = raw[i].isWhitespace()
    override fun isShown(raw: String, i: Int) = true
}

/** A style in [DisplayText] coordinates, `[start, end)`. */
data class DisplayStyle(val start: Int, val end: Int, val style: MarkupStyle)

/** A chunk as shown on screen, with hidden characters removed; remembers where each came from. */
class DisplayText internal constructor(val text: String, private val rawOffsets: IntArray) {
    /** Italics and bold from the book's markup. */
    var styles: List<DisplayStyle> = emptyList()
        internal set

    /** Index in [text] of the first shown character at or after [rawOffset] in the raw text. */
    fun indexOf(rawOffset: Int): Int {
        var lo = 0
        var hi = rawOffsets.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (rawOffsets[mid] < rawOffset) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/**
 * Splits raw book text into paragraphs for display, and finds the word being
 * sent. Chunks are capped in length so even a book with no blank lines is
 * shown as many small pieces rather than one enormous one.
 */
object TextChunks {
    const val DEFAULT_MAX_LENGTH = 600

    /** Paragraphs and pieces of them; ones with no words to show (e.g. a `* * *` divider) are skipped. */
    fun split(raw: String, maxLength: Int = DEFAULT_MAX_LENGTH, rules: WordRules = PlainWords): List<TextChunk> {
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
                if ((start until trimmedEnd).any { rules.isWordChar(raw, it) }) chunks.add(TextChunk(start, trimmedEnd))
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
     * The word at [offset], trimmed of hidden characters at its ends. Between
     * words, this is the word just before, so the highlight stays put during the
     * gap after a word.
     */
    fun wordAt(raw: String, offset: Int, rules: WordRules = PlainWords): IntRange? {
        var i = offset.coerceAtMost(raw.length - 1)
        while (i >= 0 && !rules.isWordChar(raw, i)) i--
        if (i < 0) return null
        return wordAround(raw, i, rules)
    }

    /** The word containing [i] (a shown, non-separator character), trimmed of hidden characters. */
    internal fun wordAround(raw: String, i: Int, rules: WordRules): IntRange {
        var start = i
        while (start > 0 && !rules.isSeparator(raw, start - 1)) start--
        var end = i
        while (end + 1 < raw.length && !rules.isSeparator(raw, end + 1)) end++
        while (!rules.isShown(raw, start)) start++
        while (!rules.isShown(raw, end)) end--
        return start..end
    }

    /**
     * The chunk as shown: only characters [rules] shows, line breaks and runs of
     * whitespace as single spaces, trimmed, with [markup]'s styles.
     */
    fun display(raw: String, chunk: TextChunk, rules: WordRules = PlainWords, markup: TextMarkup = TextMarkup.NONE): DisplayText {
        val text = StringBuilder(chunk.end - chunk.start)
        val offsets = IntArray(chunk.end - chunk.start)
        for (i in chunk.start until chunk.end) {
            val c = raw[i]
            if (!rules.isShown(raw, i)) continue
            if (c.isWhitespace()) {
                if (text.isEmpty() || text.last() == ' ') continue
                offsets[text.length] = i
                text.append(' ')
            } else {
                offsets[text.length] = i
                text.append(c)
            }
        }
        val length = text.trimEnd().length
        val display = DisplayText(text.substring(0, length), offsets.copyOf(length))
        display.styles = markup.spansIn(chunk.start, chunk.end).mapNotNull { span ->
            val start = display.indexOf(span.start)
            val end = display.indexOf(span.end)
            if (end > start) DisplayStyle(start, end, span.style) else null
        }
        return display
    }
}
