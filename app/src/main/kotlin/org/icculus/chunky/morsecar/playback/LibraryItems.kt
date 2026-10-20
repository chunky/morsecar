package org.icculus.chunky.morsecar.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.icculus.chunky.morsecar.library.MediaId
import org.icculus.chunky.morsecar.library.SavedPosition
import org.icculus.chunky.morsecar.morse.MorsePreset
import org.icculus.chunky.morsecar.settings.PlaybackSettings
import org.icculus.chunky.morsecar.settings.SpeedMenu
import org.icculus.chunky.morsecar.settings.SpeedSetting
import org.icculus.chunky.morsecar.settings.formatWpm
import kotlin.math.roundToInt

/** Media items for the browse tree that Android Auto (and the session) see. */
object LibraryItems {
    fun root(): MediaItem = browsable(MediaId.Root, "MorseCar", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    fun recent(): MediaItem = browsable(MediaId.Recent, "Recent", MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)

    fun folders(): MediaItem = browsable(MediaId.Folders, "Folders", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    fun speed(): MediaItem = browsable(MediaId.Speed, "Speed", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    /** "Letters: 20 WPM", opening the list of values. */
    fun setting(setting: SpeedSetting, settings: PlaybackSettings): MediaItem {
        val current = SpeedMenu.valueLabel(setting, SpeedMenu.currentValue(setting, settings))
        val detail = if (SpeedMenu.matchesLetters(setting, settings)) "same as letters" else null
        return browsable(MediaId.Setting(setting), "${setting.label}: $current", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, detail)
    }

    fun preset(preset: MorsePreset, settings: PlaybackSettings): MediaItem =
        choice(MediaId.Preset(preset.name), preset.name, settings.preset == preset, "Preset")

    fun value(setting: SpeedSetting, value: Double, settings: PlaybackSettings): MediaItem =
        choice(MediaId.SetValue(setting, value), SpeedMenu.valueLabel(setting, value), SpeedMenu.isCurrent(setting, value, settings), null)

    fun matchLetters(setting: SpeedSetting, settings: PlaybackSettings): MediaItem =
        choice(
            MediaId.SetValue(setting, null), "Same as letters", SpeedMenu.matchesLetters(setting, settings),
            "${formatWpm(settings.timing.characterWpm)} WPM, no Farnsworth spacing",
        )

    /** A setting choice. Playable, because tapping is the only action Auto offers on a list item. */
    private fun choice(id: MediaId, label: String, selected: Boolean, detail: String?): MediaItem = MediaItem.Builder()
        .setMediaId(id.value)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(if (selected) "✓ $label" else label)
                .setSubtitle(detail)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()

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

    private fun browsable(id: MediaId, title: String, type: Int, subtitle: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(id.value)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .build(),
        )
        .build()
}
