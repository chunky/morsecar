package org.icculus.chunky.morsecar.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.icculus.chunky.morsecar.text.BookDisplay
import org.icculus.chunky.morsecar.text.TextChunk
import org.icculus.chunky.morsecar.text.TextMarkup

/**
 * The book text and the position being heard, for the phone's scrolling text.
 *
 * Shared in-process rather than through the media session: the whole book is
 * too big to send over Binder, and the position changes several times a second.
 */
class NowPlaying {
    class Book(
        val id: String,
        val title: String,
        val raw: String,
        val chunks: List<TextChunk>,
        val markup: TextMarkup,
        val display: BookDisplay,
    )

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    /** Offset in [Book.raw] of the character being heard. */
    private val _rawPosition = MutableStateFlow(0)
    val rawPosition: StateFlow<Int> = _rawPosition.asStateFlow()

    fun setBook(book: Book?, rawPosition: Int = 0) {
        _book.value = book
        _rawPosition.value = rawPosition
    }

    fun setRawPosition(offset: Int) {
        _rawPosition.value = offset
    }
}
