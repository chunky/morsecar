package org.icculus.chunky.morsecar.playback

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.icculus.chunky.morsecar.library.Library
import org.icculus.chunky.morsecar.library.MediaId
import org.icculus.chunky.morsecar.library.bookTitle
import org.icculus.chunky.morsecar.morse.MorseTimeline
import org.icculus.chunky.morsecar.morse.NormalizedText
import org.icculus.chunky.morsecar.morse.TextNormalizer
import org.icculus.chunky.morsecar.text.BookDisplay
import org.icculus.chunky.morsecar.text.GutenbergText
import org.icculus.chunky.morsecar.text.NotTextException
import org.icculus.chunky.morsecar.text.TextChunk
import org.icculus.chunky.morsecar.text.TextChunks
import org.icculus.chunky.morsecar.text.TextMarkup

/** A book ready to play: its text in every form playback and display need. */
class LoadedBook(
    val uri: Uri,
    val title: String,
    /** From the Project Gutenberg header, when there is one. */
    val author: String?,
    val raw: String,
    val normalized: NormalizedText,
    val timeline: MorseTimeline,
    val chunks: List<TextChunk>,
    /** Italics and bold; their markers are neither keyed nor shown. */
    val markup: TextMarkup,
    val display: BookDisplay,
    /** Where a new or finished book starts: past any Project Gutenberg header. */
    val startIndex: Int,
) {
    val id: String get() = MediaId.Book(uri.toString()).value
}

class BookLoader(private val library: Library) {
    suspend fun load(uri: Uri, fallbackTitle: String?): LoadedBook {
        val raw = library.readText(uri)
        val title = GutenbergText.title(raw) ?: library.displayName(uri)?.let(::bookTitle) ?: fallbackTitle ?: "Untitled"
        return withContext(Dispatchers.Default) {
            val markup = TextMarkup.parse(raw)
            val display = BookDisplay(markup)
            val normalized = TextNormalizer().normalize(raw, markup::isMarker)
            if (normalized.length == 0) throw NotTextException("This file has no text that can be sent in Morse")
            val start = normalized.indexForRawOffset(GutenbergText.bodyStart(raw))
            LoadedBook(
                uri, title, GutenbergText.author(raw), raw, normalized, MorseTimeline(normalized),
                TextChunks.split(raw, rules = display), markup, display,
                startIndex = if (start < normalized.length) start else 0,
            )
        }
    }
}
