package org.icculus.chunky.morsecar.morse

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToLong

class MorseSynthesizerTest {
    private val sampleRate = 8000
    private val standard20 = MorseTiming.standard(20.0)  // 60 ms units = 480 samples

    private fun timeline(raw: String) = MorseTimeline(TextNormalizer().normalize(raw))

    private fun synth(t: MorseTimeline, timing: MorseTiming = standard20, start: Int = 0) =
        MorseSynthesizer(t, start, sampleRate, timing, 600.0)

    private fun renderAll(s: MorseSynthesizer, chunk: Int = 1000): ShortArray {
        val out = ArrayList<Short>()
        val buffer = ShortArray(chunk)
        while (true) {
            val n = s.read(buffer)
            for (i in 0 until n) out.add(buffer[i])
            if (n < chunk) break
        }
        return out.toShortArray()
    }

    @Test
    fun sampleCountMatchesTimelineExactly() {
        val t = timeline("The quick brown fox.\n\nJumps over 73 lazy dogs!")
        for (timing in listOf(standard20, MorseTiming.farnsworth(20.0, 10.0), MorseTiming(23.7, 11.3, 7.9))) {
            val expected = (t.totalMs(timing) * sampleRate / 1000).roundToLong()
            assertEquals(timing.toString(), expected, renderAll(synth(t, timing)).size.toLong())
        }
    }

    @Test
    fun ditIsToneThenSilence() {
        // "EE": dit (480 samples), letter gap (1440), dit (480).
        val pcm = renderAll(synth(timeline("EE")))
        assertEquals(480 + 1440 + 480, pcm.size)
        assertTrue(pcm.slice(0 until 480).any { abs(it.toInt()) > 10000 })
        assertTrue(pcm.slice(480 until 1920).all { it == 0.toShort() })
        assertTrue(pcm.slice(1920 until 2400).any { abs(it.toInt()) > 10000 })
    }

    @Test
    fun tonesStartAndEndAtZeroToAvoidClicks() {
        val pcm = renderAll(synth(timeline("ET")))
        // Each tone is preceded and followed by silence, so check its first and last samples.
        val toneRanges = listOf(0 until 480, (480 + 1440) until (480 + 1440 + 1440))
        for (range in toneRanges) {
            assertEquals(0, pcm[range.first].toInt())
            assertTrue(abs(pcm[range.last].toInt()) < 200)
        }
    }

    @Test
    fun peakStaysWithinAmplitude() {
        val pcm = renderAll(synth(timeline("TTT")))
        val peak = pcm.maxOf { abs(it.toInt()) }
        assertTrue(peak <= (MorseSynthesizer.DEFAULT_AMPLITUDE * Short.MAX_VALUE).toInt())
        assertTrue(peak > 0.75 * Short.MAX_VALUE)
    }

    @Test
    fun chunkSizeDoesNotChangeOutput() {
        val t = timeline("CQ CQ DE K1ABC")
        assertArrayEquals(renderAll(synth(t), chunk = 4096), renderAll(synth(t), chunk = 7))
    }

    @Test
    fun reportsEachCharacterAtItsFirstSample() {
        val t = timeline("AB C")
        val s = synth(t)
        val starts = mutableListOf<Pair<Int, Long>>()
        s.characterListener = MorseSynthesizer.CharacterListener { i, pos -> starts.add(i to pos) }
        renderAll(s)
        assertEquals((0 until t.length).toList(), starts.map { it.first })
        for ((i, pos) in starts) {
            assertEquals((t.timeAtMs(i, standard20) * sampleRate / 1000).roundToLong(), pos)
        }
        assertEquals(t.length, s.currentCharIndex)
        assertTrue(s.isFinished)
    }

    @Test
    fun startsMidText() {
        val t = timeline("EE")
        assertEquals(480, renderAll(synth(t, start = 1)).size)
    }

    @Test
    fun timingChangeAppliesToTheNextElement() {
        val t = timeline("EE")
        val s = synth(t)
        val first = ShortArray(100)
        s.read(first)  // part way through the first dit
        s.timing = MorseTiming.standard(10.0)  // 120 ms units = 960 samples
        val rest = renderAll(s)
        // The first dit finishes at the old speed; the gap and second dit use the new one.
        assertEquals(480 - 100 + 3 * 960 + 960, rest.size)
    }

    @Test
    fun wavHasHeaderAndData() {
        val bytes = ByteArrayOutputStream().also { WavWriter.write(it, shortArrayOf(1, -1), 8000) }.toByteArray()
        assertEquals(48, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals("data", String(bytes, 36, 4))
    }

    @Test
    fun emptyTextRendersNothing() {
        val s = synth(MorseTimeline(""))
        assertEquals(0, s.read(ShortArray(10)))
        assertTrue(s.isFinished)
    }
}
