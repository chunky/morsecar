package org.icculus.chunky.morsecar.settings

import org.icculus.chunky.morsecar.morse.MorsePresets
import org.icculus.chunky.morsecar.morse.MorseTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSettingsTest {
    @Test
    fun recognizesPresets() {
        for (preset in MorsePresets.all) {
            assertEquals(preset, PlaybackSettings(preset.timing, 600.0).preset)
        }
        assertNull(PlaybackSettings(MorseTiming(21.0, 13.0, 9.0), 600.0).preset)
    }

    @Test
    fun describesPresetOrSpeeds() {
        assertEquals("Standard 20 · 600 Hz", PlaybackSettings(MorseTiming.standard(20.0), 600.0).describe())
        assertEquals("21/13.5/9 WPM · 650 Hz", PlaybackSettings(MorseTiming(21.0, 13.5, 9.0), 650.4).describe())
    }

    @Test
    fun cyclesThroughPresetsKeepingTone() {
        var settings = PlaybackSettings(MorseTiming(21.0, 13.0, 9.0), 700.0)
        val seen = List(MorsePresets.all.size) {
            settings = settings.withNextPreset()
            assertEquals(700.0, settings.toneHz, 0.0)
            settings.preset
        }
        assertEquals(MorsePresets.all, seen)
        assertEquals(MorsePresets.all.first(), settings.withNextPreset().preset)
    }

    @Test
    fun clampsToSliderRanges() {
        val clamped = PlaybackSettings(MorseTiming(100.0, 0.5, 20.0), 5000.0).clamped()
        assertEquals(40.0, clamped.timing.characterWpm, 0.0)
        assertEquals(2.0, clamped.timing.letterSpacingWpm, 0.0)
        assertEquals(1000.0, clamped.toneHz, 0.0)
    }

    @Test
    fun presetsFitTheSliderRanges() {
        for (preset in MorsePresets.all) {
            assertEquals(preset.name, preset.timing, PlaybackSettings(preset.timing, 600.0).clamped().timing)
        }
    }
}
