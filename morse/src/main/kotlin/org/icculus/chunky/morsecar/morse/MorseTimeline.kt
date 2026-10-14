package org.icculus.chunky.morsecar.morse

enum class UnitKind { CHARACTER, LETTER_GAP, WORD_GAP }

/** A tone or a silence, belonging to the character at [charIndex]. */
data class MorseElement(val charIndex: Int, val isTone: Boolean, val units: Int, val kind: UnitKind) {
    fun durationMs(timing: MorseTiming): Double = units * when (kind) {
        UnitKind.CHARACTER -> timing.characterUnitMs
        UnitKind.LETTER_GAP -> timing.letterSpacingUnitMs
        UnitKind.WORD_GAP -> timing.wordSpacingUnitMs
    }
}

/**
 * Maps between positions in normalized text and playback time.
 *
 * Each character owns a slot: its own sound plus the gap that follows it (a
 * letter gap if another character follows; nothing before a word or paragraph
 * gap, which are slots of their own). Slots are measured in [UnitCounts], which
 * don't depend on speed, so the timeline is built once per book and every query
 * takes the current [MorseTiming]. Prefix sums are kept only every
 * [CHECKPOINT_INTERVAL] characters to keep memory small for whole books.
 */
class MorseTimeline(val text: String, private val alphabet: MorseAlphabet = MorseAlphabet.default) {
    init {
        val bad = text.indexOfFirst { !NormalizedText.isGap(it) && !alphabet.isEncodable(it) }
        require(bad < 0) { "Unencodable character '${text[bad]}' at $bad; normalize the text first" }
    }

    constructor(normalized: NormalizedText, alphabet: MorseAlphabet = MorseAlphabet.default) :
        this(normalized.text, alphabet)

    val length: Int get() = text.length

    // Units before each checkpoint, flattened as (character, letterGap, wordGap) triples.
    private val checkpoints: LongArray = buildCheckpoints()

    private fun buildCheckpoints(): LongArray {
        val count = length / CHECKPOINT_INTERVAL + 1
        val result = LongArray(count * 3)
        var character = 0L
        var letterGap = 0L
        var wordGap = 0L
        for (i in 0 until length) {
            if (i % CHECKPOINT_INTERVAL == 0) {
                val k = i / CHECKPOINT_INTERVAL * 3
                result[k] = character
                result[k + 1] = letterGap
                result[k + 2] = wordGap
            }
            val c = text[i]
            when (c) {
                NormalizedText.WORD_GAP -> wordGap += MorseTiming.WORD_GAP_UNITS
                NormalizedText.PARAGRAPH_GAP -> wordGap += MorseTiming.PARAGRAPH_GAP_UNITS
                else -> {
                    character += alphabet.characterUnits(c)
                    if (isFollowedBySymbol(i)) letterGap += MorseTiming.LETTER_GAP_UNITS
                }
            }
        }
        if (length % CHECKPOINT_INTERVAL == 0) {
            val k = length / CHECKPOINT_INTERVAL * 3
            result[k] = character
            result[k + 1] = letterGap
            result[k + 2] = wordGap
        }
        return result
    }

    private fun isFollowedBySymbol(index: Int): Boolean {
        val next = text.getOrNull(index + 1) ?: return false
        return !NormalizedText.isGap(next)
    }

    /** The units in [index]'s slot. */
    fun unitsOf(index: Int): UnitCounts {
        require(index in 0 until length) { "index $index out of 0 until $length" }
        return when (val c = text[index]) {
            NormalizedText.WORD_GAP -> UnitCounts(0, 0, MorseTiming.WORD_GAP_UNITS.toLong())
            NormalizedText.PARAGRAPH_GAP -> UnitCounts(0, 0, MorseTiming.PARAGRAPH_GAP_UNITS.toLong())
            else -> UnitCounts(
                alphabet.characterUnits(c).toLong(),
                if (isFollowedBySymbol(index)) MorseTiming.LETTER_GAP_UNITS.toLong() else 0,
                0,
            )
        }
    }

    /** Total units of all slots before [index]. */
    fun unitsBefore(index: Int): UnitCounts {
        require(index in 0..length) { "index $index out of 0..$length" }
        val checkpoint = index / CHECKPOINT_INTERVAL
        var units = checkpointUnits(checkpoint)
        for (i in checkpoint * CHECKPOINT_INTERVAL until index) units += unitsOf(i)
        return units
    }

    private fun checkpointUnits(checkpoint: Int): UnitCounts {
        val k = checkpoint * 3
        return UnitCounts(checkpoints[k], checkpoints[k + 1], checkpoints[k + 2])
    }

    /** When [index]'s slot starts, measured from the start of the text. */
    fun timeAtMs(index: Int, timing: MorseTiming): Double = timing.durationMs(unitsBefore(index))

    fun totalMs(timing: MorseTiming): Double = timeAtMs(length, timing)

    /**
     * The character whose slot contains [timeMs]: 0 for negative times, [length]
     * for times at or past the end.
     */
    fun indexAtTime(timeMs: Double, timing: MorseTiming): Int {
        if (timeMs <= 0 || length == 0) return 0
        // Last checkpoint starting at or before timeMs.
        var lo = 0
        var hi = checkpoints.size / 3 - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (timing.durationMs(checkpointUnits(mid)) <= timeMs) lo = mid else hi = mid - 1
        }
        // Accumulate whole units and convert each time, exactly as timeAtMs does,
        // so that indexAtTime(timeAtMs(i)) == i despite floating-point rounding.
        var index = lo * CHECKPOINT_INTERVAL
        var units = checkpointUnits(lo)
        while (index < length) {
            val end = units + unitsOf(index)
            if (timing.durationMs(end) > timeMs) break
            units = end
            index++
        }
        return index
    }

    /** The character [deltaMs] of playback away from the start of [fromIndex]'s slot. */
    fun seek(fromIndex: Int, deltaMs: Double, timing: MorseTiming): Int =
        indexAtTime(timeAtMs(fromIndex, timing) + deltaMs, timing)

    /**
     * The first character of the word containing [index], so playback can start
     * on a word boundary. Gaps and [length] are returned unchanged.
     */
    fun wordStart(index: Int): Int {
        require(index in 0..length) { "index $index out of 0..$length" }
        if (index == length || NormalizedText.isGap(text[index])) return index
        var i = index
        while (i > 0 && !NormalizedText.isGap(text[i - 1])) i--
        return i
    }

    /** Tones and silences from the start of [fromIndex]'s slot to the end of the text. */
    fun elements(fromIndex: Int = 0): Iterator<MorseElement> {
        require(fromIndex in 0..length) { "fromIndex $fromIndex out of 0..$length" }
        return iterator {
            for (i in fromIndex until length) {
                when (val c = text[i]) {
                    NormalizedText.WORD_GAP ->
                        yield(MorseElement(i, false, MorseTiming.WORD_GAP_UNITS, UnitKind.WORD_GAP))
                    NormalizedText.PARAGRAPH_GAP ->
                        yield(MorseElement(i, false, MorseTiming.PARAGRAPH_GAP_UNITS, UnitKind.WORD_GAP))
                    else -> {
                        val pattern = alphabet.patternFor(c)!!
                        pattern.forEachIndexed { m, mark ->
                            if (m > 0) {
                                yield(MorseElement(i, false, MorseTiming.INTRA_CHARACTER_GAP_UNITS, UnitKind.CHARACTER))
                            }
                            val units = if (mark == '.') MorseTiming.DIT_UNITS else MorseTiming.DAH_UNITS
                            yield(MorseElement(i, true, units, UnitKind.CHARACTER))
                        }
                        if (isFollowedBySymbol(i)) {
                            yield(MorseElement(i, false, MorseTiming.LETTER_GAP_UNITS, UnitKind.LETTER_GAP))
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val CHECKPOINT_INTERVAL = 256
    }
}
