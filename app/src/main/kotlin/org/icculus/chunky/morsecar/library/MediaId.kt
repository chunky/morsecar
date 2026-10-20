package org.icculus.chunky.morsecar.library

import org.icculus.chunky.morsecar.settings.SpeedSetting

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

    /** The Android Auto "Speed" tab: presets and the four settings. */
    data object Speed : MediaId { override val value = "speed" }

    /** The list of values for one setting. */
    data class Setting(val setting: SpeedSetting) : MediaId {
        override val value get() = "$SETTING_PREFIX${setting.id}"
    }

    /** Choosing a value for a setting; a null [choice] sets a gap to the letter speed. */
    data class SetValue(val setting: SpeedSetting, val choice: Double?) : MediaId {
        override val value get() = "$SET_PREFIX${setting.id}:${choice ?: MATCH_LETTERS}"
    }

    data class Preset(val name: String) : MediaId {
        override val value get() = "$PRESET_PREFIX$name"
    }

    companion object {
        private const val FOLDER_PREFIX = "folder:"
        private const val BOOK_PREFIX = "book:"
        private const val SETTING_PREFIX = "setting:"
        private const val SET_PREFIX = "set:"
        private const val PRESET_PREFIX = "preset:"
        private const val MATCH_LETTERS = "match"

        fun parse(value: String): MediaId? = when {
            value == Root.value -> Root
            value == Recent.value -> Recent
            value == Folders.value -> Folders
            value == Info.value -> Info
            value == Speed.value -> Speed
            value.startsWith(SETTING_PREFIX) -> SpeedSetting.fromId(value.removePrefix(SETTING_PREFIX))?.let(::Setting)
            value.startsWith(SET_PREFIX) -> parseSetValue(value.removePrefix(SET_PREFIX))
            value.startsWith(PRESET_PREFIX) -> Preset(value.removePrefix(PRESET_PREFIX)).takeIf { it.name.isNotEmpty() }
            value.startsWith(FOLDER_PREFIX) -> Folder(value.removePrefix(FOLDER_PREFIX)).takeIf { it.uri.isNotEmpty() }
            value.startsWith(BOOK_PREFIX) -> Book(value.removePrefix(BOOK_PREFIX)).takeIf { it.uri.isNotEmpty() }
            else -> null
        }

        private fun parseSetValue(rest: String): SetValue? {
            val setting = SpeedSetting.fromId(rest.substringBefore(':')) ?: return null
            val choice = rest.substringAfter(':', "")
            if (choice == MATCH_LETTERS) return if (setting.isSpacing) SetValue(setting, null) else null
            return choice.toDoubleOrNull()?.let { SetValue(setting, it) }
        }
    }
}
