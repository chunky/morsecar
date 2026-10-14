package org.icculus.chunky.morsecar.morse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class MorseTimelineTest {
    private val standard20 = MorseTiming.standard(20.0)

    private fun timeline(raw: String) = MorseTimeline(TextNormalizer().normalize(raw))

    @Test
    fun parisIsFiftyUnits() {
        val t = timeline("PARIS PARIS")
        assertEquals(3000.0, t.timeAtMs(6, standard20), 1e-9)  // 50 units * 60 ms
    }

    @Test
    fun farnsworth20Over10SendsParisInSixSeconds() {
        val t = timeline("PARIS PARIS")
        assertEquals(6000.0, t.timeAtMs(6, MorseTiming.farnsworth(20.0, 10.0)), 1e-6)
    }

    @Test
    fun letterSlotsIncludeTheFollowingLetterGapOnly() {
        val t = timeline("EE E")
        assertEquals(UnitCounts(1, 3, 0), t.unitsOf(0))  // E then letter gap
        assertEquals(UnitCounts(1, 0, 0), t.unitsOf(1))  // E before a word gap
        assertEquals(UnitCounts(0, 0, 7), t.unitsOf(2))
        assertEquals(UnitCounts(1, 0, 0), t.unitsOf(3))  // last E
    }

    @Test
    fun paragraphGapIsLongerThanWordGap() {
        val t = timeline("E\n\nE")
        assertEquals(UnitCounts(0, 0, 10), t.unitsOf(1))
    }

    @Test
    fun rejectsUnnormalizedText() {
        assertThrows(IllegalArgumentException::class.java) { MorseTimeline("hello") }
        assertThrows(IllegalArgumentException::class.java) { MorseTimeline("A\tB") }
    }

    @Test
    fun emptyTimeline() {
        val t = MorseTimeline("")
        assertEquals(0.0, t.totalMs(standard20), 0.0)
        assertEquals(0, t.indexAtTime(1000.0, standard20))
        assertEquals(false, t.elements().hasNext())
    }

    @Test
    fun elementsAddUpToEachSlot() {
        val t = timeline("Hello, world.\n\nIt's 73!")
        val byChar = t.elements().asSequence().groupBy { it.charIndex }
        assertEquals((0 until t.length).toList(), byChar.keys.sorted())
        for ((i, elements) in byChar) {
            val units = elements.fold(UnitCounts.ZERO) { acc, e ->
                acc + when (e.kind) {
                    UnitKind.CHARACTER -> UnitCounts(e.units.toLong(), 0, 0)
                    UnitKind.LETTER_GAP -> UnitCounts(0, e.units.toLong(), 0)
                    UnitKind.WORD_GAP -> UnitCounts(0, 0, e.units.toLong())
                }
            }
            assertEquals("char $i", t.unitsOf(i), units)
        }
    }

    @Test
    fun elementsForALetter() {
        val elements = timeline("AN").elements().asSequence().toList()
        assertEquals(
            listOf(
                MorseElement(0, true, 1, UnitKind.CHARACTER),
                MorseElement(0, false, 1, UnitKind.CHARACTER),
                MorseElement(0, true, 3, UnitKind.CHARACTER),
                MorseElement(0, false, 3, UnitKind.LETTER_GAP),
                MorseElement(1, true, 3, UnitKind.CHARACTER),
                MorseElement(1, false, 1, UnitKind.CHARACTER),
                MorseElement(1, true, 1, UnitKind.CHARACTER),
            ),
            elements,
        )
    }

    @Test
    fun elementsCanStartMidText() {
        val first = timeline("AN").elements(1).next()
        assertEquals(1, first.charIndex)
    }

    /** Long enough to cross many checkpoints, compared against a straightforward sum. */
    @Test
    fun checkpointedQueriesMatchNaiveSums() {
        val random = Random(1234)
        val words = listOf("THE", "QUICK", "BROWN", "FOX", "JUMPS", "73", "CQ", "DE", "K1ABC")
        val text = buildString {
            while (length < 5000) {
                append(words.random(random))
                append(if (random.nextInt(20) == 0) '\n' else ' ')
            }
            append("END")
        }
        val t = MorseTimeline(text)
        val timing = MorseTiming(18.0, 9.0, 6.5)
        val naiveStart = DoubleArray(t.length + 1)
        for (i in 0 until t.length) naiveStart[i + 1] = naiveStart[i] + timing.durationMs(t.unitsOf(i))

        for (i in listOf(0, 1, 255, 256, 257, 511, 512, 1000, t.length - 1, t.length) + List(200) { random.nextInt(t.length + 1) }) {
            assertEquals("timeAt($i)", naiveStart[i], t.timeAtMs(i, timing), 1e-6)
        }
        for (i in List(200) { random.nextInt(t.length) } + listOf(0, 255, 256, t.length - 1)) {
            // Start (exactly), middle, and just before the end of each slot all land in that slot.
            val start = t.timeAtMs(i, timing)
            val end = t.timeAtMs(i + 1, timing)
            assertEquals(i, t.indexAtTime(start, timing))
            assertEquals(i, t.indexAtTime((start + end) / 2, timing))
            assertEquals(i, t.indexAtTime(end - 1e-3, timing))
        }
        assertEquals(t.length, t.indexAtTime(t.totalMs(timing), timing))
    }

    @Test
    fun seekMovesByTimeAndClampsAtTheEnds() {
        val t = timeline("THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG")
        val total = t.totalMs(standard20)
        val middle = t.indexAtTime(total / 2, standard20)

        val back = t.seek(middle, -2000.0, standard20)
        val backStart = t.timeAtMs(back, standard20)
        val target = t.timeAtMs(middle, standard20) - 2000.0
        // The target time falls inside the slot we landed on.
        assert(backStart <= target && target < backStart + standard20.durationMs(t.unitsOf(back)))

        assertEquals(0, t.seek(middle, -1_000_000.0, standard20))
        assertEquals(t.length, t.seek(middle, 1_000_000.0, standard20))
        assertEquals(middle, t.seek(middle, 0.0, standard20))
    }

    @Test
    fun wordStartBacksUpToTheStartOfTheWord() {
        val t = MorseTimeline("CQ DE\nK1ABC")
        assertEquals(0, t.wordStart(0))
        assertEquals(0, t.wordStart(1))
        assertEquals(2, t.wordStart(2))   // a gap stays put
        assertEquals(3, t.wordStart(4))
        assertEquals(6, t.wordStart(10))  // after a paragraph gap
        assertEquals(t.length, t.wordStart(t.length))
    }

    @Test
    fun timeScalesWithSpeedButPositionsDoNot() {
        val t = timeline("CQ CQ DE K1ABC")
        assertEquals(2 * t.totalMs(MorseTiming.standard(20.0)), t.totalMs(MorseTiming.standard(10.0)), 1e-6)
    }
}
