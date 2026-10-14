package org.icculus.chunky.morsecar.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.icculus.chunky.morsecar.morse.MorseSynthesizer
import org.icculus.chunky.morsecar.morse.MorseTimeline
import org.icculus.chunky.morsecar.morse.MorseTiming
import org.icculus.chunky.morsecar.settings.PlaybackSettings
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Plays a [MorseTimeline] through a streaming [AudioTrack], synthesizing as it goes.
 *
 * A render thread keeps the track's short buffer full. Writes are non-blocking,
 * so pause and seek never wait on a blocked write; every track call happens
 * under [lock]. [position] reports what is audible, not what has been rendered:
 * the synthesizer marks where each character starts, and the track's playback
 * head says how far playback has got.
 *
 * Listener callbacks arrive on the main thread.
 */
class MorseAudioEngine(private val listener: Listener) {
    interface Listener {
        fun onEnded()
        fun onError(message: String)
    }

    /** The audible character, and how far into its slot playback is. */
    data class Position(val charIndex: Int, val framesIntoChar: Long)

    private class Marker(val frame: Long, val charIndex: Int)

    val sampleRate: Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 48000

    private val main = Handler(Looper.getMainLooper())
    private val lock = ReentrantLock()
    private val wake = lock.newCondition()

    // Everything below is guarded by lock.
    private var track: AudioTrack = createTrack()
    private var timeline: MorseTimeline? = null
    private var synth: MorseSynthesizer? = null
    private var segmentStartIndex = 0
    private var timing: MorseTiming = PlaybackSettings.DEFAULT.timing
    private var toneHz: Double = PlaybackSettings.DEFAULT.toneHz
    private var playing = false
    private var released = false
    private var ended = false
    /** Bumped on every load/seek, so stale end/error callbacks are dropped. */
    private var generation = 0

    private val chunk = ShortArray(CHUNK_FRAMES)
    private val silence = ShortArray(CHUNK_FRAMES)
    private var pendingOffset = 0
    private var pendingCount = 0
    private var pendingIsAudio = true
    /** Silence written after the last tone, so a short final stretch still reaches the speaker. */
    private var paddingLeft = 0

    // Frame counts since the current segment (load or seek) started.
    private var audioFramesWritten = 0L
    private var totalFramesWritten = 0L
    private var playedFrames = 0L
    private var lastHead = 0L
    private val markers = ArrayDeque<Marker>()
    /** Returned once released: the track can't be asked anything then. */
    private var lastPosition = Position(0, 0)
    private var deadTrackRetries = 0

    private val thread = Thread(::renderLoop, "MorseAudio").apply { start() }

    /** Starts over with [timeline] (or nothing) at [startIndex]. */
    fun load(timeline: MorseTimeline?, startIndex: Int) = lock.withLock {
        this.timeline = timeline
        resetSegment(startIndex)
    }

    fun seekTo(index: Int) = lock.withLock { resetSegment(index) }

    fun setPlaying(play: Boolean): Unit = lock.withLock {
        if (released || play == playing) return
        playing = play
        if (play) {
            deadTrackRetries = 0
            if (synth != null && !ended) track.play()
        } else {
            track.pause()
        }
        wake.signalAll()
    }

    /** Takes effect from the next tone or gap. */
    fun setTiming(timing: MorseTiming) = lock.withLock {
        this.timing = timing
        synth?.timing = timing
    }

    fun setToneHz(hz: Double) = lock.withLock {
        toneHz = hz
        synth?.toneHz = hz
    }

    fun position(): Position = lock.withLock {
        if (released) return lastPosition
        val length = timeline?.length ?: 0
        if (ended) return Position(length, 0).also { lastPosition = it }
        updatePlayedFrames()
        val played = minOf(playedFrames, audioFramesWritten)
        while (markers.size >= 2 && markers[1].frame <= played) markers.removeFirst()
        val marker = markers.firstOrNull()
        val position = when {
            marker == null || marker.frame > played -> Position(segmentStartIndex, 0)
            synth?.isFinished == true && played >= audioFramesWritten -> Position(length, 0)
            else -> Position(marker.charIndex, played - marker.frame)
        }
        lastPosition = position
        position
    }

    /** Whether everything has been played (so playing again needs a seek first). */
    val isAtEnd: Boolean get() = lock.withLock { ended }

    fun release() {
        lock.withLock {
            if (released) return
            lastPosition = position()
            released = true
            wake.signalAll()
        }
        thread.join(1000)
        lock.withLock { track.release() }
    }

    private fun resetSegment(startIndex: Int) {
        if (released) return
        track.pause()
        track.flush()
        generation++
        val tl = timeline
        segmentStartIndex = startIndex.coerceIn(0, tl?.length ?: 0)
        synth = tl?.let {
            MorseSynthesizer(it, segmentStartIndex, sampleRate, timing, toneHz).apply {
                characterListener = MorseSynthesizer.CharacterListener { charIndex, frame ->
                    markers.addLast(Marker(frame, charIndex))
                }
            }
        }
        markers.clear()
        ended = false
        pendingCount = 0
        paddingLeft = track.bufferSizeInFrames
        audioFramesWritten = 0
        totalFramesWritten = 0
        playedFrames = 0
        lastHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        if (playing && synth != null) track.play()
        wake.signalAll()
    }

    /** Advances [playedFrames] from the track's 32-bit, wrapping playback head. */
    private fun updatePlayedFrames() {
        val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val delta = (head - lastHead) and 0xFFFFFFFFL
        lastHead = head
        // An advance past everything queued means the head was reset (as some
        // devices do on flush); resynchronize without moving.
        if (delta <= totalFramesWritten - playedFrames) playedFrames += delta
    }

    private fun renderLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        lock.withLock {
            while (!released) {
                val s = synth
                if (!playing || s == null || ended) {
                    wake.await()
                    continue
                }
                if (pendingCount == 0 && !refill(s)) continue
                val source = if (pendingIsAudio) chunk else silence
                val written = track.write(source, pendingOffset, pendingCount, AudioTrack.WRITE_NON_BLOCKING)
                when {
                    written == AudioTrack.ERROR_DEAD_OBJECT -> recreateTrack()
                    written < 0 -> fail("Audio output failed (error $written)")
                    written == 0 -> wake.await(WAIT_MS, TimeUnit.MILLISECONDS)
                    else -> {
                        deadTrackRetries = 0
                        pendingOffset += written
                        pendingCount -= written
                        totalFramesWritten += written
                        if (pendingIsAudio) audioFramesWritten += written
                    }
                }
            }
        }
    }

    /** Queues the next chunk to write. Returns false if there's nothing to write right now. */
    private fun refill(s: MorseSynthesizer): Boolean {
        pendingOffset = 0
        if (!s.isFinished) {
            pendingCount = s.read(chunk)
            pendingIsAudio = true
            return pendingCount > 0
        }
        if (paddingLeft > 0) {
            pendingCount = minOf(paddingLeft, CHUNK_FRAMES)
            paddingLeft -= pendingCount
            pendingIsAudio = false
            return true
        }
        updatePlayedFrames()
        if (playedFrames >= audioFramesWritten) {
            ended = true
            track.pause()
            post { listener.onEnded() }
        } else {
            wake.await(WAIT_MS, TimeUnit.MILLISECONDS)
        }
        return false
    }

    /**
     * The output device or audio server went away (e.g. a Bluetooth route change);
     * start a new track where we were, backing off while the audio server restarts.
     * The old track is released only once a new one exists: a dead track is safe
     * to call, a released one isn't.
     */
    private fun recreateTrack() {
        val resumeAt = position().charIndex
        while (!released) {
            if (++deadTrackRetries > MAX_DEAD_TRACK_RETRIES) {
                fail("Lost the audio output")
                return
            }
            if (deadTrackRetries > 1) wake.await(DEAD_TRACK_BACKOFF_MS, TimeUnit.MILLISECONDS)
            val replacement = try {
                createTrack()
            } catch (e: UnsupportedOperationException) {
                continue
            }
            track.release()
            track = replacement
            resetSegment(resumeAt)
            return
        }
    }

    private fun fail(message: String) {
        playing = false
        track.pause()
        post { listener.onError(message) }
    }

    /** Runs [action] on the main thread unless a load or seek has happened since. */
    private fun post(action: () -> Unit) {
        val expected = generation
        main.post {
            val current = lock.withLock { generation == expected && !released }
            if (current) action()
        }
    }

    private fun createTrack(): AudioTrack {
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val targetBytes = sampleRate * 2 * BUFFER_MS / 1000
        return AudioTrack.Builder()
            .setAudioAttributes(AUDIO_ATTRIBUTES)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBytes, targetBytes))
            .build()
    }

    companion object {
        /** Speech, not music: the system then leaves pausing for prompts to us instead of ducking. */
        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        /** Short, so slider changes are heard almost immediately. */
        private const val BUFFER_MS = 150
        private const val CHUNK_FRAMES = 480
        private const val WAIT_MS = 10L
        private const val MAX_DEAD_TRACK_RETRIES = 5
        private const val DEAD_TRACK_BACKOFF_MS = 200L
    }
}
