package org.icculus.chunky.morsecar.morse

data class MorsePreset(val name: String, val timing: MorseTiming)

/** Preset buttons for the speed sliders. They don't change the tone frequency. */
object MorsePresets {
    val all: List<MorsePreset> = listOf(
        MorsePreset("Standard 20", MorseTiming.standard(20.0)),
        MorsePreset("Standard 15", MorseTiming.standard(15.0)),
        MorsePreset("Farnsworth 20/10", MorseTiming.farnsworth(20.0, 10.0)),
        MorsePreset("Farnsworth 18/12", MorseTiming.farnsworth(18.0, 12.0)),
    )
}
