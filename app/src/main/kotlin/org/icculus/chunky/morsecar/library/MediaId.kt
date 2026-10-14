package org.icculus.chunky.morsecar.library

/**
 * IDs for the media browse tree shared by the phone and Android Auto. Folder and
 * book IDs carry a content:// document URI as a string.
 */
sealed interface MediaId {
    val value: String

    data object Root : MediaId { override val value = "root" }
    data object Recent : MediaId { override val value = "recent" }
    data object Folders : MediaId { override val value = "folders" }
    data object Info : MediaId { override val value = "info" }

    data class Folder(val uri: String) : MediaId {
        override val value get() = "$FOLDER_PREFIX$uri"
    }

    data class Book(val uri: String) : MediaId {
        override val value get() = "$BOOK_PREFIX$uri"
    }

    companion object {
        private const val FOLDER_PREFIX = "folder:"
        private const val BOOK_PREFIX = "book:"

        fun parse(value: String): MediaId? = when {
            value == Root.value -> Root
            value == Recent.value -> Recent
            value == Folders.value -> Folders
            value == Info.value -> Info
            value.startsWith(FOLDER_PREFIX) -> Folder(value.removePrefix(FOLDER_PREFIX)).takeIf { it.uri.isNotEmpty() }
            value.startsWith(BOOK_PREFIX) -> Book(value.removePrefix(BOOK_PREFIX)).takeIf { it.uri.isNotEmpty() }
            else -> null
        }
    }
}
