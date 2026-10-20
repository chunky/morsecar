package org.icculus.chunky.morsecar.text

import org.icculus.chunky.morsecar.morse.TextNormalizer

/**
 * What the phone and car show of a book: only what's keyed, plus spaces and
 * punctuation dashes. Formatting markers (shown as styling instead), stray
 * markup such as footnote `*`s, and unkeyable symbols are hidden, so the screen
 * matches what's heard.
 */
class BookDisplay(private val markup: TextMarkup) : WordRules {
    override fun isSeparator(raw: String, i: Int): Boolean =
        raw[i].isWhitespace() || TextNormalizer.isWordBreak(raw[i])

    override fun isShown(raw: String, i: Int): Boolean =
        !markup.isMarker(i) && (isSeparator(raw, i) || normalizer.sends(raw[i]))

    private companion object {
        val normalizer = TextNormalizer()
    }
}
