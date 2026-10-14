package org.icculus.chunky.morsecar.text

import org.icculus.chunky.morsecar.morse.MorseTimeline
import org.icculus.chunky.morsecar.morse.MorseTiming
import org.icculus.chunky.morsecar.morse.TextNormalizer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every file in the top-level samples/ folder (bundled into the APK) must load and play. */
class SamplesTest {
    private val samples: List<File> =
        File("../samples").listFiles { f -> f.name.endsWith(".txt", ignoreCase = true) }.orEmpty().sortedBy { it.name }

    @Test
    fun samplesLoadAndPlay() {
        for (file in samples) {
            val raw = BookText.decode(file.readBytes())
            val normalized = TextNormalizer().normalize(raw)
            assertTrue("${file.name}: no sendable text", normalized.length > 0)
            val start = normalized.indexForRawOffset(GutenbergText.bodyStart(raw))
            assertTrue("${file.name}: body start past the end", start < normalized.length)
            val timeline = MorseTimeline(normalized)
            val hours = timeline.totalMs(MorseTiming.standard(20.0)) / 3_600_000
            val chunks = TextChunks.split(raw)
            assertTrue("${file.name}: no display text", chunks.isNotEmpty())
            println("${file.name}: \"${GutenbergText.title(raw) ?: "(no Gutenberg title)"}\", " +
                "${normalized.length} sendable chars, ${chunks.size} paragraphs, " +
                "%.1f h at 20 WPM".format(hours))
        }
    }
}
