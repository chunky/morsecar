package org.icculus.chunky.morsecar.text

import java.util.BitSet

enum class MarkupStyle { ITALIC, BOLD }

/**
 * Plain-text formatting in a book: Project Gutenberg's `_italics_`, and bold as
 * `*bold*`, `**bold**`, `__bold__`, or `=bold=`. The marker characters are
 * shown as styling and never keyed.
 *
 * Markers count only as a pair wrapping text within one paragraph: the opener
 * at the start of a word, the closer at the end of one. So `snake_case`, a lone
 * footnote `*`, or `a = b` aren't mistaken for formatting.
 */
class TextMarkup private constructor(private val markers: BitSet, val spans: List<Span>) {
    /** Styled raw text `[start, end)`, not including its markers. Sorted by [start]. */
    data class Span(val start: Int, val end: Int, val style: MarkupStyle)

    /** Whether the raw character at [i] is a formatting marker. */
    fun isMarker(i: Int): Boolean = markers.get(i)

    /** Spans overlapping raw `[start, end)`. */
    fun spansIn(start: Int, end: Int): List<Span> {
        // Spans don't nest within one style, and are short, so a window from the
        // first span that could reach [start] is enough.
        var lo = 0
        var hi = spans.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (spans[mid].start < start - MAX_SPAN) lo = mid + 1 else hi = mid
        }
        val result = ArrayList<Span>()
        for (k in lo until spans.size) {
            val s = spans[k]
            if (s.start >= end) break
            if (s.end > start) result.add(s)
        }
        return result
    }

    companion object {
        /** Longest formatted run; an unmatched marker can't capture a whole book. */
        private const val MAX_SPAN = 2000

        private val MARKER_CHARS = setOf('_', '*', '=')

        val NONE = TextMarkup(BitSet(), emptyList())

        fun parse(raw: String): TextMarkup {
            val markers = BitSet()
            val spans = ArrayList<Span>()
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c !in MARKER_CHARS || markers.get(i)) {
                    i++
                    continue
                }
                val run = runLength(raw, i)
                if (run > 2 || !canOpen(raw, i, run)) {
                    i += run
                    continue
                }
                val close = findClose(raw, i + run, c, run)
                if (close < 0) {
                    i += run
                    continue
                }
                markers.set(i, i + run)
                markers.set(close, close + run)
                spans.add(Span(i + run, close, styleOf(c, run)))
                // Carry on inside the span, so e.g. bold within italics is found too.
                i += run
            }
            spans.sortBy { it.start }
            return TextMarkup(markers, spans)
        }

        private fun styleOf(c: Char, run: Int): MarkupStyle =
            if (c == '_' && run == 1) MarkupStyle.ITALIC else MarkupStyle.BOLD

        private fun runLength(raw: String, i: Int): Int {
            var j = i
            while (j < raw.length && raw[j] == raw[i]) j++
            return j - i
        }

        private fun canOpen(raw: String, i: Int, run: Int): Boolean {
            val before = raw.getOrNull(i - 1)
            val after = raw.getOrNull(i + run) ?: return false
            return (before == null || !before.isLetterOrDigit()) && !after.isWhitespace()
        }

        private fun canClose(raw: String, j: Int, run: Int): Boolean {
            val after = raw.getOrNull(j + run)
            return !raw[j - 1].isWhitespace() && (after == null || !after.isLetterOrDigit())
        }

        /** The matching closer after [from], or -1 if none before the paragraph ends. */
        private fun findClose(raw: String, from: Int, c: Char, run: Int): Int {
            var j = from
            val limit = minOf(raw.length, from + MAX_SPAN)
            while (j < limit) {
                val ch = raw[j]
                if (ch == '\n' && startsBlankLine(raw, j + 1)) return -1
                if (ch == c) {
                    val r = runLength(raw, j)
                    if (r == run && j > from && canClose(raw, j, run)) return j
                    j += r
                    continue
                }
                j++
            }
            return -1
        }

        private fun startsBlankLine(raw: String, from: Int): Boolean {
            var k = from
            while (k < raw.length && (raw[k] == ' ' || raw[k] == '\t' || raw[k] == '\r')) k++
            return k >= raw.length || raw[k] == '\n'
        }
    }
}
