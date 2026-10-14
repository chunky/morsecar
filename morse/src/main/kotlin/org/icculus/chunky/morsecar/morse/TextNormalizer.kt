package org.icculus.chunky.morsecar.morse

import java.text.Normalizer

/**
 * Text reduced to what can be sent: Morse symbols, [WORD_GAP], and [PARAGRAPH_GAP].
 * There's never a gap at the start or end, and never two gaps in a row.
 *
 * Each character remembers where it came from in the raw text, so saved
 * positions (raw offsets) survive changes to the normalization rules.
 */
class NormalizedText internal constructor(
    val text: String,
    private val rawOffsets: IntArray,
    private val rawLength: Int,
) {
    val length: Int get() = text.length

    /** Offset in the raw text of [index]; [length] maps to the end of the raw text. */
    fun rawOffsetOf(index: Int): Int {
        require(index in 0..length) { "index $index out of 0..$length" }
        return if (index == length) rawLength else rawOffsets[index]
    }

    /** The first character at or after [rawOffset] in the raw text. */
    fun indexForRawOffset(rawOffset: Int): Int {
        var lo = 0
        var hi = length
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (rawOffsets[mid] < rawOffset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        const val WORD_GAP = ' '
        const val PARAGRAPH_GAP = '\n'

        fun isGap(c: Char) = c == WORD_GAP || c == PARAGRAPH_GAP
    }
}

class TextNormalizer(private val alphabet: MorseAlphabet = MorseAlphabet.default) {
    /**
     * Uppercases, maps typographic and accented characters to sendable ones,
     * silently drops anything else, and collapses whitespace. A run of whitespace
     * containing a blank line is a paragraph break; any other run is a word gap,
     * so hard-wrapped lines read as continuous text.
     */
    fun normalize(raw: String): NormalizedText {
        val out = StringBuilder(raw.length)
        var offsets = IntArray(raw.length + 16)
        var pendingGap: Char? = null
        var pendingGapOffset = 0

        fun append(c: Char, rawOffset: Int) {
            if (out.length == offsets.size) offsets = offsets.copyOf(offsets.size * 2)
            offsets[out.length] = rawOffset
            out.append(c)
        }

        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c.isWhitespace()) {
                val start = i
                var lineBreaks = 0
                while (i < raw.length && raw[i].isWhitespace()) {
                    lineBreaks += when (raw[i]) {
                        '\n', '\u0085', '\u2028' -> 1
                        '\r' -> if (raw.getOrNull(i + 1) == '\n') 0 else 1
                        '\u2029', '\u000C' -> 2
                        else -> 0
                    }
                    i++
                }
                val gap = if (lineBreaks >= 2) NormalizedText.PARAGRAPH_GAP else NormalizedText.WORD_GAP
                if (pendingGap == null) {
                    pendingGap = gap
                    pendingGapOffset = start
                } else if (gap == NormalizedText.PARAGRAPH_GAP) {
                    // Whitespace split by dropped characters: the bigger break wins.
                    pendingGap = gap
                }
                continue
            }
            for (sendable in sendableFor(c)) {
                val gap = pendingGap
                if (gap != null && out.isNotEmpty()) append(gap, pendingGapOffset)
                pendingGap = null
                append(sendable, i)
            }
            i++
        }
        return NormalizedText(out.toString(), offsets.copyOf(out.length), raw.length)
    }

    private fun sendableFor(c: Char): String {
        val upper = c.uppercaseChar()
        if (alphabet.isEncodable(upper)) return upper.toString()
        replacements[c]?.let { return it }
        // Accents, ligatures, ellipses, full-width forms, 'ß' -> "SS", and so on.
        return Normalizer.normalize(c.toString(), Normalizer.Form.NFKD)
            .uppercase()
            .filter(alphabet::isEncodable)
    }

    private companion object {
        /** Typographic characters that Unicode decomposition doesn't map to ASCII. */
        val replacements: Map<Char, String> = buildMap {
            "‘’‚‛′`´".forEach { put(it, "'") }
            "“”„‟″«»".forEach { put(it, "\"") }
            "‐‑‒–—―−".forEach { put(it, "-") }
        }
    }
}
