package org.icculus.chunky.morsecar.settings

import org.icculus.chunky.morsecar.morse.MorseTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedMenuTest {
    private val farnsworth = PlaybackSettings(MorseTiming.farnsworth(20.0, 10.0), 600.0)

    @Test
    fun appliesEachSetting() {
        assertEquals(15.0, SpeedMenu.apply(SpeedSetting.CHARACTER, 15.0, farnsworth).timing.characterWpm, 0.0)
        assertEquals(8.0, SpeedMenu.apply(SpeedSetting.LETTER_GAP, 8.0, farnsworth).timing.letterSpacingWpm, 0.0)
        assertEquals(12.0, SpeedMenu.apply(SpeedSetting.WORD_GAP, 12.0, farnsworth).timing.wordSpacingWpm, 0.0)
        assertEquals(750.0, SpeedMenu.apply(SpeedSetting.TONE, 750.0, farnsworth).toneHz, 0.0)
    }

    @Test
    fun matchLettersSetsGapToLetterSpeed() {
        val s = SpeedMenu.apply(SpeedSetting.WORD_GAP, null, farnsworth)
        assertEquals(20.0, s.timing.wordSpacingWpm, 0.0)
        assertTrue(SpeedMenu.matchesLetters(SpeedSetting.WORD_GAP, s))
        assertFalse(SpeedMenu.matchesLetters(SpeedSetting.LETTER_GAP, s))
    }

    @Test
    fun choicesFitTheSliderRanges() {
        for (setting in SpeedSetting.entries) {
            for (v in SpeedMenu.choices(setting)) {
                assertEquals("$setting $v", v, SpeedMenu.currentValue(setting, SpeedMenu.apply(setting, v, farnsworth)), 0.0)
            }
        }
    }

    @Test
    fun labelsAndCurrent() {
        assertEquals("5.5 WPM", SpeedMenu.valueLabel(SpeedSetting.LETTER_GAP, 5.507))
        assertEquals("20 WPM", SpeedMenu.valueLabel(SpeedSetting.CHARACTER, 20.0))
        assertEquals("600 Hz", SpeedMenu.valueLabel(SpeedSetting.TONE, 600.0))
        assertTrue(SpeedMenu.isCurrent(SpeedSetting.CHARACTER, 20.0, farnsworth))
        assertFalse(SpeedMenu.isCurrent(SpeedSetting.LETTER_GAP, 5.0, farnsworth))
    }
}
