package org.icculus.chunky.morsecar.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.icculus.chunky.morsecar.library.MediaId
import org.icculus.chunky.morsecar.library.SavedPosition
import kotlin.math.roundToInt

/** Media items for the browse tree that Android Auto (and the session) see. */
object LibraryItems {
    fun root(): MediaItem = browsable(MediaId.Root, "MorseCar", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    fun recent(): MediaItem = browsable(MediaId.Recent, "Recent", MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)

    fun folders(): MediaItem = browsable(MediaId.Folders, "Folders", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    fun folder(uri: String, name: String): MediaItem =
        browsable(MediaId.Folder(uri), name, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    fun book(uri: String, title: String, position: SavedPosition? = null): MediaItem = MediaItem.Builder()
        .setMediaId(MediaId.Book(uri).value)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(position?.let { "${(it.fraction * 100).roundToInt()}% done" })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .build(),
        )
        .build()

    /** A non-interactive row explaining what to do, e.g. when no folders have been added. */
    fun info(message: String): MediaItem = MediaItem.Builder()
        .setMediaId(MediaId.Info.value)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(message)
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .build(),
        )
        .build()

    private fun browsable(id: MediaId, title: String, type: Int): MediaItem = MediaItem.Builder()
        .setMediaId(id.value)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .build(),
        )
        .build()
}
