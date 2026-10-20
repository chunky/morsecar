package org.icculus.chunky.morsecar.text

/**
 * Project Gutenberg's plain-text books start with a license header. These find
 * the real title, and where the book itself begins.
 */
object GutenbergText {
    /** How far into a file to look for the header. */
    private const val HEADER_LIMIT = 20_000

    private val TITLE = Regex("""^Title:[ \t]*(\S.*?)[ \t]*\r?$""", RegexOption.MULTILINE)
    private val AUTHOR = Regex("""^Author:[ \t]*(\S.*?)[ \t]*\r?$""", RegexOption.MULTILINE)
    private val START = Regex(
        """^\*\*\*[ \t]*START OF (?:THE|THIS) PROJECT GUTENBERG E-?BOOK.*$""",
        setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE),
    )

    /** The "Title:" line of the header, or null if this isn't a Gutenberg book. */
    fun title(raw: String): String? = headerField(raw, TITLE)

    /** The "Author:" line of the header, or null. */
    fun author(raw: String): String? = headerField(raw, AUTHOR)

    private fun headerField(raw: String, field: Regex): String? {
        val header = raw.take(HEADER_LIMIT)
        val start = START.find(header) ?: return null
        return field.find(header.substring(0, start.range.first))?.groupValues?.get(1)
    }

    /** Offset of the line after "*** START OF THE PROJECT GUTENBERG EBOOK ...", or 0. */
    fun bodyStart(raw: String): Int {
        val match = START.find(raw.take(HEADER_LIMIT)) ?: return 0
        var i = match.range.last + 1
        if (raw.getOrNull(i) == '\r') i++
        if (raw.getOrNull(i) == '\n') i++
        return i
    }
}
