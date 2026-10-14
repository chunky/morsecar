package org.icculus.chunky.morsecar.morse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MorseTimingTest {
    @Test
    fun unitAt20WpmIs60Ms() {
        assertEquals(60.0, MorseTiming.unitMs(20.0), 1e-9)
    }

    @Test
    fun farnsworth20Over10SpacesAtAbout5Point5Wpm() {
        val timing = MorseTiming.farnsworth(20.0, 10.0)
        assertEquals(20.0, timing.characterWpm, 0.0)
        assertEquals(5.507, timing.letterSpacingWpm, 0.001)
        assertEquals(timing.letterSpacingWpm, timing.wordSpacingWpm, 0.0)
    }

    @Test
    fun farnsworthNotSlowerThanCharacterSpeedIsStandard() {
        assertEquals(MorseTiming.standard(20.0), MorseTiming.farnsworth(20.0, 20.0))
        assertEquals(MorseTiming.standard(20.0), MorseTiming.farnsworth(20.0, 25.0))
    }

    @Test
    fun rejectsNonPositiveOrInfiniteWpm() {
        assertThrows(IllegalArgumentException::class.java) { MorseTiming(0.0, 20.0, 20.0) }
        assertThrows(IllegalArgumentException::class.java) { MorseTiming(20.0, -1.0, 20.0) }
        assertThrows(IllegalArgumentException::class.java) { MorseTiming(20.0, 20.0, Double.POSITIVE_INFINITY) }
    }

    @Test
    fun durationWeightsEachKindOfUnitByItsOwnSpeed() {
        val timing = MorseTiming(20.0, 10.0, 5.0)  // 60, 120, 240 ms units
        assertEquals(60.0 + 2 * 120.0 + 3 * 240.0, timing.durationMs(UnitCounts(1, 2, 3)), 1e-9)
    }

    @Test
    fun presetsHaveDistinctNames() {
        val names = MorsePresets.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }
}
