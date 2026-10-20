package org.icculus.chunky.morsecar.settings

import org.icculus.chunky.morsecar.morse.MorseTiming
import kotlin.math.abs
import kotlin.math.roundToInt

/** One of the four sliders, for places that can't show a slider (Android Auto). */
enum class SpeedSetting(val id: String, val label: String) {
    CHARACTER("letters", "Letters"),
    LETTER_GAP("letter_gaps", "Letter gaps"),
    WORD_GAP("word_gaps", "Word gaps"),
    TONE("tone", "Tone"),
    ;

    val isSpacing: Boolean get() = this == LETTER_GAP || this == WORD_GAP

    companion object {
        fun fromId(id: String): SpeedSetting? = entries.firstOrNull { it.id == id }
    }
}

/** The sliders as pick-lists: the values offered for each, and applying a choice. */
object SpeedMenu {
    private val CHARACTER_CHOICES = listOf(5.0, 8.0, 10.0, 12.0, 15.0, 18.0, 20.0, 22.0, 25.0, 28.0, 30.0, 35.0, 40.0)
    private val SPACING_CHOICES = listOf(2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 15.0, 18.0, 20.0, 25.0, 30.0, 40.0)
    private val TONE_CHOICES = (400..1000 step 50).map { it.toDouble() }

    fun choices(setting: SpeedSetting): List<Double> = when (setting) {
        SpeedSetting.CHARACTER -> CHARACTER_CHOICES
        SpeedSetting.LETTER_GAP, SpeedSetting.WORD_GAP -> SPACING_CHOICES
        SpeedSetting.TONE -> TONE_CHOICES
    }

    fun currentValue(setting: SpeedSetting, settings: PlaybackSettings): Double = when (setting) {
        SpeedSetting.CHARACTER -> settings.timing.characterWpm
        SpeedSetting.LETTER_GAP -> settings.timing.letterSpacingWpm
        SpeedSetting.WORD_GAP -> settings.timing.wordSpacingWpm
        SpeedSetting.TONE -> settings.toneHz
    }

    fun valueLabel(setting: SpeedSetting, value: Double): String =
        if (setting == SpeedSetting.TONE) "${value.roundToInt()} Hz" else "${formatWpm(value)} WPM"

    fun isCurrent(setting: SpeedSetting, value: Double, settings: PlaybackSettings): Boolean =
        abs(currentValue(setting, settings) - value) < 0.05

    /** Whether a gap setting already equals the letter speed (standard, non-Farnsworth timing). */
    fun matchesLetters(setting: SpeedSetting, settings: PlaybackSettings): Boolean =
        setting.isSpacing && isCurrent(setting, settings.timing.characterWpm, settings)

    /** [settings] with [setting] set to [value]; a null value sets a gap to the letter speed. */
    fun apply(setting: SpeedSetting, value: Double?, settings: PlaybackSettings): PlaybackSettings {
        val t = settings.timing
        val v = value ?: t.characterWpm
        return when (setting) {
            SpeedSetting.CHARACTER -> settings.copy(timing = MorseTiming(v, t.letterSpacingWpm, t.wordSpacingWpm))
            SpeedSetting.LETTER_GAP -> settings.copy(timing = MorseTiming(t.characterWpm, v, t.wordSpacingWpm))
            SpeedSetting.WORD_GAP -> settings.copy(timing = MorseTiming(t.characterWpm, t.letterSpacingWpm, v))
            SpeedSetting.TONE -> settings.copy(toneHz = v)
        }.clamped()
    }
}

fun formatWpm(wpm: Double): String = "%.1f".format(wpm).removeSuffix(".0")
