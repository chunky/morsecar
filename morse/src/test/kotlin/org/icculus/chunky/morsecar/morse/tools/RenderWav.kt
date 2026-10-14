package org.icculus.chunky.morsecar.morse.tools

import org.icculus.chunky.morsecar.morse.MorseSynthesizer
import org.icculus.chunky.morsecar.morse.MorseTimeline
import org.icculus.chunky.morsecar.morse.MorseTiming
import org.icculus.chunky.morsecar.morse.TextNormalizer
import org.icculus.chunky.morsecar.morse.WavWriter
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """Usage: ./gradlew :morse:renderWav --args="[options] [text...]"
  --out FILE            output WAV (default morse.wav)
  --in FILE             read text from FILE instead of the command line
  --wpm C/L/W           character / letter-spacing / word-spacing WPM (default 20/20/20)
  --farnsworth C/S      Farnsworth timing: character speed C, effective speed S
  --tone HZ             tone frequency (default 600)
  --rate HZ             sample rate (default 44100)"""

/** Renders text to a WAV file, for listening to the engine without a phone. */
fun main(args: Array<String>) {
    var out = File("morse.wav")
    var input: File? = null
    var timing = MorseTiming.standard(20.0)
    var tone = 600.0
    var rate = 44100
    val words = mutableListOf<String>()

    val it = args.iterator()
    fun value(flag: String) = if (it.hasNext()) it.next() else fail("$flag needs a value")
    while (it.hasNext()) {
        when (val arg = it.next()) {
            "--out" -> out = File(value(arg))
            "--in" -> input = File(value(arg))
            "--wpm" -> timing = value(arg).split('/').map(String::toDouble).let {
                if (it.size != 3) fail("--wpm needs C/L/W")
                MorseTiming(it[0], it[1], it[2])
            }
            "--farnsworth" -> timing = value(arg).split('/').map(String::toDouble).let {
                if (it.size != 2) fail("--farnsworth needs C/S")
                MorseTiming.farnsworth(it[0], it[1])
            }
            "--tone" -> tone = value(arg).toDouble()
            "--rate" -> rate = value(arg).toInt()
            "-h", "--help" -> fail("")
            else -> words.add(arg)
        }
    }
    val raw = input?.readText() ?: words.joinToString(" ").ifEmpty { "PARIS PARIS PARIS" }

    val timeline = MorseTimeline(TextNormalizer().normalize(raw))
    val synth = MorseSynthesizer(timeline, 0, rate, timing, tone)
    val totalMs = timeline.totalMs(timing)
    val samples = ShortArray(Math.round(totalMs * rate / 1000).toInt())
    synth.read(samples)
    out.outputStream().buffered().use { WavWriter.write(it, samples, rate) }
    println("Wrote ${out.absolutePath}: ${"%.1f".format(totalMs / 1000)} s, $timing, $tone Hz")
}

private fun fail(message: String): Nothing {
    if (message.isNotEmpty()) System.err.println(message)
    System.err.println(USAGE)
    exitProcess(1)
}
