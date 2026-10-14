package org.icculus.chunky.morsecar.morse

/**
 * The three speed sliders. Each is expressed in WPM (PARIS standard), and each
 * determines the length of the "unit" used for its part of the timing:
 *
 *  - [characterWpm]: dits, dahs, and the gaps inside a character (1/3/1 units)
 *  - [letterSpacingWpm]: the gap between characters (3 units)
 *  - [wordSpacingWpm]: the gap between words (7 units) and paragraphs (10 units)
 *
 * All three equal means standard timing; lower spacing speeds give Farnsworth-style timing.
 */
data class MorseTiming(
    val characterWpm: Double,
    val letterSpacingWpm: Double,
    val wordSpacingWpm: Double,
) {
    init {
        require(characterWpm.isValidWpm() && letterSpacingWpm.isValidWpm() && wordSpacingWpm.isValidWpm()) {
            "WPM values must be positive and finite: $this"
        }
    }

    val characterUnitMs: Double get() = unitMs(characterWpm)
    val letterSpacingUnitMs: Double get() = unitMs(letterSpacingWpm)
    val wordSpacingUnitMs: Double get() = unitMs(wordSpacingWpm)

    fun durationMs(units: UnitCounts): Double =
        units.character * characterUnitMs +
            units.letterGap * letterSpacingUnitMs +
            units.wordGap * wordSpacingUnitMs

    companion object {
        const val DIT_UNITS = 1
        const val DAH_UNITS = 3
        const val INTRA_CHARACTER_GAP_UNITS = 1
        const val LETTER_GAP_UNITS = 3
        const val WORD_GAP_UNITS = 7
        const val PARAGRAPH_GAP_UNITS = 10

        /** One unit at [wpm], using the 50-unit word "PARIS ". */
        fun unitMs(wpm: Double): Double = 1200.0 / wpm

        /** The same speed for everything. */
        fun standard(wpm: Double) = MorseTiming(wpm, wpm, wpm)

        /**
         * Characters sent at [characterWpm], with spacing stretched so the overall
         * speed is [effectiveWpm], per the ARRL Farnsworth formula. Both spacing
         * sliders get the same value. If [effectiveWpm] isn't slower than
         * [characterWpm], this is just standard timing.
         */
        fun farnsworth(characterWpm: Double, effectiveWpm: Double): MorseTiming {
            if (effectiveWpm >= characterWpm) return standard(characterWpm)
            val c = characterWpm
            val s = effectiveWpm
            // Seconds of spacing per PARIS word, spread over its 19 spacing units.
            val spacingSecondsPerWord = (60 * c - 37.2 * s) / (s * c)
            val spacingWpm = 1.2 * 19 / spacingSecondsPerWord
            return MorseTiming(c, spacingWpm, spacingWpm)
        }

        private fun Double.isValidWpm() = this > 0 && isFinite()
    }
}

/** A duration measured in each kind of unit; [MorseTiming.durationMs] turns it into time. */
data class UnitCounts(val character: Long, val letterGap: Long, val wordGap: Long) {
    operator fun plus(other: UnitCounts) =
        UnitCounts(character + other.character, letterGap + other.letterGap, wordGap + other.wordGap)

    companion object {
        val ZERO = UnitCounts(0, 0, 0)
    }
}
