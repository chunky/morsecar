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
            val markup = TextMarkup.parse(raw)
            val rules = BookDisplay(markup)
            val normalized = TextNormalizer().normalize(raw, markup::isMarker)
            assertTrue("${file.name}: no sendable text", normalized.length > 0)
            val start = normalized.indexForRawOffset(GutenbergText.bodyStart(raw))
            assertTrue("${file.name}: body start past the end", start < normalized.length)
            val timeline = MorseTimeline(normalized)
            val hours = timeline.totalMs(MorseTiming.standard(20.0)) / 3_600_000
            assertTrue("${file.name}: underscores keyed", '_' !in normalized.text)
            val chunks = TextChunks.split(raw, rules = rules)
            assertTrue("${file.name}: no display text", chunks.isNotEmpty())
            for (chunk in chunks) {
                val shown = TextChunks.display(raw, chunk, rules, markup).text
                assertTrue("${file.name}: markup shown in \"$shown\"", shown.none { it == '_' || it == '*' })
                assertTrue("${file.name}: empty chunk at ${chunk.start}", shown.isNotBlank())
            }
            println("${file.name}: \"${GutenbergText.title(raw) ?: "(no Gutenberg title)"}\", " +
                "${normalized.length} sendable chars, ${chunks.size} paragraphs, " +
                "${markup.spans.count { it.style == MarkupStyle.ITALIC }} italic and " +
                "${markup.spans.count { it.style == MarkupStyle.BOLD }} bold spans, " +
                "%.1f h at 20 WPM".format(hours))
        }
    }
}
