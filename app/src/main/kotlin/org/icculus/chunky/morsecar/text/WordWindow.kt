package org.icculus.chunky.morsecar.text

/** The word being sent, with a few words of context on each side, as they appear in the book. */
data class WordWindow(
    val before: List<String>,
    val current: String,
    val after: List<String>,
    /** Raw offset of [current]; identifies the word, so callers can tell when it changes. */
    val currentStart: Int,
) {
    /**
     * The words before and including [current], dropping the earliest ones to fit
     * [maxChars]: [current] is always present and always last, so it survives
     * displays that cut long titles off at the end.
     */
    fun endingWithCurrent(maxChars: Int): String {
        var text = current
        for (word in before.asReversed()) {
            val longer = "$word $text"
            if (longer.length > maxChars) break
            text = longer
        }
        return text
    }

    companion object {
        /**
         * Words around [rawOffset] in [raw], as shown (hidden characters removed);
         * between words, the current word is the one just before.
         */
        fun around(
            raw: String,
            rawOffset: Int,
            wordsBefore: Int = 4,
            wordsAfter: Int = 4,
            rules: WordRules = PlainWords,
        ): WordWindow? {
            val current = TextChunks.wordAt(raw, rawOffset, rules) ?: return null
            fun shown(range: IntRange) = range.filter { rules.isShown(raw, it) }.map { raw[it] }.joinToString("")
            val before = ArrayList<String>(wordsBefore)
            var i = current.first - 1
            while (before.size < wordsBefore) {
                while (i >= 0 && !rules.isWordChar(raw, i)) i--
                if (i < 0) break
                val word = TextChunks.wordAround(raw, i, rules)
                before.add(0, shown(word))
                i = minOf(word.first, i) - 1
                while (i >= 0 && !rules.isSeparator(raw, i)) i--  // past any hidden prefix
            }
            val after = ArrayList<String>(wordsAfter)
            var j = current.last + 1
            while (after.size < wordsAfter) {
                while (j < raw.length && !rules.isWordChar(raw, j)) j++
                if (j >= raw.length) break
                val word = TextChunks.wordAround(raw, j, rules)
                after.add(shown(word))
                j = maxOf(word.last, j) + 1
                while (j < raw.length && !rules.isSeparator(raw, j)) j++  // past any hidden suffix
            }
            return WordWindow(before, shown(current), after, current.first)
        }
    }
}
