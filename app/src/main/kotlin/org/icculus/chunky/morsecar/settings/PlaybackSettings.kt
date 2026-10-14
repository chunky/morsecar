package org.icculus.chunky.morsecar.settings

import org.icculus.chunky.morsecar.morse.MorsePreset
import org.icculus.chunky.morsecar.morse.MorsePresets
import org.icculus.chunky.morsecar.morse.MorseTiming
import kotlin.math.abs
import kotlin.math.roundToInt

/** Everything the sliders control. */
data class PlaybackSettings(val timing: MorseTiming, val toneHz: Double) {
    /** The preset these speeds match, if any. */
    val preset: MorsePreset? get() = MorsePresets.all.firstOrNull { it.timing.isCloseTo(timing) }

    /** Short description for the media notification and Android Auto. */
    fun describe(): String {
        val speeds = preset?.name ?: with(timing) {
            "${characterWpm.format()}/${letterSpacingWpm.format()}/${wordSpacingWpm.format()} WPM"
        }
        return "$speeds · ${toneHz.roundToInt()} Hz"
    }

    fun clamped() = PlaybackSettings(
        MorseTiming(
            timing.characterWpm.coerceIn(CHARACTER_WPM_RANGE),
            timing.letterSpacingWpm.coerceIn(SPACING_WPM_RANGE),
            timing.wordSpacingWpm.coerceIn(SPACING_WPM_RANGE),
        ),
        toneHz.coerceIn(TONE_HZ_RANGE),
    )

    /** The next preset after the current one, keeping the tone; wraps around. */
    fun withNextPreset(): PlaybackSettings {
        val presets = MorsePresets.all
        val next = presets[(presets.indexOf(preset) + 1) % presets.size]
        return copy(timing = next.timing)
    }

    companion object {
        val CHARACTER_WPM_RANGE = 5.0..40.0
        val SPACING_WPM_RANGE = 2.0..40.0
        val TONE_HZ_RANGE = 400.0..1000.0

        val DEFAULT = PlaybackSettings(MorseTiming.farnsworth(20.0, 10.0), 600.0)

        private fun MorseTiming.isCloseTo(other: MorseTiming) =
            abs(characterWpm - other.characterWpm) < 0.05 &&
                abs(letterSpacingWpm - other.letterSpacingWpm) < 0.05 &&
                abs(wordSpacingWpm - other.wordSpacingWpm) < 0.05

        private fun Double.format(): String =
            if (abs(this - roundToInt()) < 0.05) roundToInt().toString() else "%.1f".format(this)
    }
}
