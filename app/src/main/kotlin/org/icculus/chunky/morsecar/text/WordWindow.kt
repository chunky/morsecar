package org.icculus.chunky.morsecar.text

/** The word being sent, with a few words of context on each side, as they appear in the book. */
data class WordWindow(
    val before: List<String>,
    val current: String,
    val after: List<String>,
    /** Raw offset of [current]; identifies the word, so callers can tell when it changes. */
    val currentStart: Int,
) {
    companion object {
        /** Words around [rawOffset] in [raw]; in whitespace, the current word is the one just before. */
        fun around(raw: String, rawOffset: Int, wordsBefore: Int = 4, wordsAfter: Int = 4): WordWindow? {
            val current = TextChunks.wordAt(raw, rawOffset) ?: return null
            val before = ArrayList<String>(wordsBefore)
            var i = current.first - 1
            while (before.size < wordsBefore) {
                while (i >= 0 && raw[i].isWhitespace()) i--
                if (i < 0) break
                val end = i
                while (i > 0 && !raw[i - 1].isWhitespace()) i--
                before.add(0, raw.substring(i, end + 1))
                i--
            }
            val after = ArrayList<String>(wordsAfter)
            var j = current.last + 1
            while (after.size < wordsAfter) {
                while (j < raw.length && raw[j].isWhitespace()) j++
                if (j >= raw.length) break
                val start = j
                while (j < raw.length && !raw[j].isWhitespace()) j++
                after.add(raw.substring(start, j))
            }
            return WordWindow(before, raw.substring(current.first, current.last + 1), after, current.first)
        }
    }
}
