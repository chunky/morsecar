package org.icculus.chunky.morsecar.morse

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Renders a [MorseTimeline] to 16-bit mono PCM, starting at [startIndex], one
 * buffer at a time.
 *
 * [timing] and [toneHz] may be changed between (or during, from another thread)
 * [read] calls; each element uses the values in effect when it starts, so a
 * change never alters a tone or gap partway through. Element boundaries are
 * computed from the accumulated exact time, so rounding to whole samples never
 * drifts.
 */
class MorseSynthesizer(
    timeline: MorseTimeline,
    startIndex: Int,
    val sampleRate: Int,
    timing: MorseTiming,
    toneHz: Double,
    private val rampMs: Double = DEFAULT_RAMP_MS,
    private val amplitude: Double = DEFAULT_AMPLITUDE,
) {
    /** Called when the first element of each character starts being rendered. */
    fun interface CharacterListener {
        fun onCharacterStart(charIndex: Int, samplePosition: Long)
    }

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(toneHz > 0 && toneHz < sampleRate / 2.0) { "toneHz must be between 0 and Nyquist" }
        require(amplitude in 0.0..1.0) { "amplitude must be in 0..1" }
    }

    @Volatile var timing: MorseTiming = timing

    @Volatile var toneHz: Double = toneHz
        set(value) {
            require(value > 0 && value < sampleRate / 2.0) { "toneHz must be between 0 and Nyquist" }
            field = value
        }

    var characterListener: CharacterListener? = null

    /** Samples rendered so far. */
    var samplePosition: Long = 0
        private set

    /** The character currently being rendered, or the timeline length once finished. */
    var currentCharIndex: Int = startIndex
        private set

    val isFinished: Boolean get() = samplePosition >= elementEndSample && !elements.hasNext()

    private val elements = timeline.elements(startIndex)
    private val endIndex = timeline.length
    private var element: MorseElement? = null
    private var elapsedMs = 0.0
    private var elementStartSample = 0L
    private var elementEndSample = 0L
    private var elementToneHz = 0.0
    private var lastNotifiedChar = -1

    /**
     * Fills `out[offset until offset + length]` and returns how many samples were
     * written, which is less than [length] only at the end of the text.
     */
    fun read(out: ShortArray, offset: Int = 0, length: Int = out.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= out.size) { "bad offset/length" }
        var written = 0
        while (written < length) {
            val current = element?.takeIf { samplePosition < elementEndSample } ?: nextElement() ?: break
            val count = min((length - written).toLong(), elementEndSample - samplePosition).toInt()
            val at = offset + written
            if (current.isTone) {
                renderTone(out, at, count)
            } else {
                out.fill(0, at, at + count)
            }
            samplePosition += count
            written += count
        }
        return written
    }

    private fun nextElement(): MorseElement? {
        while (elements.hasNext()) {
            val next = elements.next()
            if (next.charIndex != lastNotifiedChar) {
                lastNotifiedChar = next.charIndex
                currentCharIndex = next.charIndex
                characterListener?.onCharacterStart(next.charIndex, samplePosition)
            }
            elapsedMs += next.durationMs(timing)
            elementStartSample = samplePosition
            elementEndSample = (elapsedMs * sampleRate / 1000).roundToLong()
            elementToneHz = toneHz
            element = next
            if (elementEndSample > samplePosition) return next
        }
        element = null
        currentCharIndex = endIndex
        return null
    }

    private fun renderTone(out: ShortArray, at: Int, count: Int) {
        val toneSamples = elementEndSample - elementStartSample
        val ramp = min((rampMs * sampleRate / 1000).roundToLong(), toneSamples / 2).coerceAtLeast(1)
        val radiansPerSample = 2 * PI * elementToneHz / sampleRate
        val peak = amplitude * Short.MAX_VALUE
        for (k in 0 until count) {
            val s = samplePosition - elementStartSample + k
            // Raised-cosine edges: start and end at zero so keying doesn't click.
            val fromEdge = min(s, toneSamples - 1 - s)
            val envelope = if (fromEdge >= ramp) 1.0 else 0.5 - 0.5 * cos(PI * fromEdge / ramp)
            out[at + k] = (peak * envelope * sin(radiansPerSample * s)).toInt().toShort()
        }
    }

    companion object {
        const val DEFAULT_RAMP_MS = 5.0
        const val DEFAULT_AMPLITUDE = 0.8
    }
}
