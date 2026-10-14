package org.icculus.chunky.morsecar.playback

import androidx.core.net.toUri
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.icculus.chunky.morsecar.library.MediaId
import org.icculus.chunky.morsecar.library.PositionStore
import org.icculus.chunky.morsecar.library.SavedPosition
import org.icculus.chunky.morsecar.settings.PlaybackSettings
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.roundToLong

/**
 * Media3 player for Morse books: wraps [MorseAudioEngine] so the media session,
 * notification, Bluetooth, and Android Auto can drive it.
 *
 * Positions are character offsets; the player's time-based position is derived
 * from the current speeds, so changing a slider changes the duration and
 * position but never what you're hearing. Previous/next (e.g. steering-wheel
 * buttons) skip back/forward 30 s; seek back/forward skip 15 s.
 *
 * Must be used on the main thread.
 */
@OptIn(UnstableApi::class)
class MorsePlayer(
    private val context: Context,
    private val loader: BookLoader,
    private val positions: PositionStore,
    private val nowPlaying: NowPlaying,
    /** Main-thread scope for loading and periodic work; cancelled by the service. */
    private val scope: CoroutineScope,
    /** Longer-lived scope for saving, so a save started at shutdown still finishes. */
    private val saveScope: CoroutineScope,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val engine = MorseAudioEngine(object : MorseAudioEngine.Listener {
        override fun onEnded() = onPlaybackEnded()
        override fun onError(message: String) = onPlaybackError(message, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED)
    })

    private val focus = AudioFocus(context, object : AudioFocus.Listener {
        override fun onFocusLost(transient: Boolean) {
            if (!playWhenReady) return
            if (transient) {
                suppression = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
            } else {
                setPlayWhenReadyInternal(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
            }
            savePosition()
            updateEngine()
            invalidateState()
        }

        override fun onFocusRegained() {
            if (suppression != Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) return
            suppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE
            updateEngine()
            invalidateState()
        }
    })

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            setPlayWhenReadyInternal(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
            savePosition()
            focus.abandon()
            updateEngine()
            invalidateState()
        }
    }
    private var noisyRegistered = false

    private var settings = PlaybackSettings.DEFAULT
    private var mediaItem: MediaItem? = null
    private var book: LoadedBook? = null
    private var playWhenReady = false
    private var playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
    private var playbackState = Player.STATE_IDLE
    private var suppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE
    private var error: PlaybackException? = null
    private var loadFuture: ListenableFuture<Unit>? = null
    private var tickerJob: Job? = null

    private val isActivelyPlaying: Boolean
        get() = playWhenReady && playbackState == Player.STATE_READY && suppression == Player.PLAYBACK_SUPPRESSION_REASON_NONE

    /** Applies new slider values; durations and positions are recomputed from them. */
    fun applySettings(settings: PlaybackSettings) {
        this.settings = settings
        engine.setTiming(settings.timing)
        engine.setToneHz(settings.toneHz)
        invalidateState()
    }

    override fun getState(): State {
        val item = mediaItem
        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(playWhenReady, playWhenReadyReason)
            .setPlaybackSuppressionReason(suppression)
            .setPlayerError(error)
            .setSeekBackIncrementMs(SKIP_SHORT_MS)
            .setSeekForwardIncrementMs(SKIP_SHORT_MS)
            .setAudioAttributes(MEDIA3_AUDIO_ATTRIBUTES)
        if (item == null) {
            builder.setPlaybackState(if (playbackState == Player.STATE_ENDED) Player.STATE_ENDED else Player.STATE_IDLE)
            return builder.build()
        }
        val b = book
        val itemData = MediaItemData.Builder(item.mediaId)
            .setMediaItem(item)
            .setMediaMetadata(
                item.mediaMetadata.buildUpon()
                    .setTitle(b?.title ?: item.mediaMetadata.title)
                    .setArtist(settings.describe())
                    .build(),
            )
            .setDurationUs(b?.let { (it.timeline.totalMs(settings.timing) * 1000).roundToLong() } ?: C.TIME_UNSET)
            .setIsSeekable(b != null)
            .build()
        return builder
            .setPlaybackState(playbackState)
            .setPlaylist(listOf(itemData))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs { currentTimeMs().roundToLong() }
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady && playbackState == Player.STATE_ENDED) {
            // Playing a finished book starts it over.
            val start = book?.startIndex ?: 0
            engine.seekTo(start)
            playbackState = Player.STATE_READY
            book?.let { publishPosition(it, start) }
        }
        setPlayWhenReadyInternal(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        if (!playWhenReady) {
            savePosition()
            focus.abandon()
        }
        updateEngine()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        val item = mediaItem ?: return Futures.immediateVoidFuture()
        // Callers usually prepare right after setting the item, while it's still loading.
        loadFuture?.takeIf { !it.isDone }?.let { return it }
        if (book == null) return load(item, C.TIME_UNSET)  // retry after an error or a stop
        error = null
        if (playbackState == Player.STATE_IDLE) {
            playbackState = if (engine.isAtEnd) Player.STATE_ENDED else Player.STATE_READY
        }
        updateEngine()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        loadFuture?.cancel(false)
        savePosition()
        playbackState = Player.STATE_IDLE
        error = null
        updateEngine()
        focus.abandon()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        savePosition()
        loadFuture?.cancel(false)
        stopTicker()
        setNoisyReceiverRegistered(false)
        focus.abandon()
        engine.release()
        nowPlaying.setBook(null)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        savePosition()
        val item = mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
        if (item == null) {
            loadFuture?.cancel(false)
            mediaItem = null
            book = null
            playbackState = Player.STATE_IDLE
            engine.load(null, 0)
            nowPlaying.setBook(null)
            updateEngine()
            return Futures.immediateVoidFuture()
        }
        return load(item, startPositionMs)
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val b = book ?: return Futures.immediateVoidFuture()
        val target = when (seekCommand) {
            Player.COMMAND_SEEK_TO_PREVIOUS -> indexAtTime(currentTimeMs() - SKIP_LONG_MS)
            Player.COMMAND_SEEK_TO_NEXT -> indexAtTime(currentTimeMs() + SKIP_LONG_MS)
            else -> if (positionMs == C.TIME_UNSET) 0 else indexAtTime(positionMs.toDouble())
        }
        engine.seekTo(target)
        if (playbackState == Player.STATE_ENDED) playbackState = Player.STATE_READY
        publishPosition(b, target)
        savePosition(target)
        updateEngine()
        return Futures.immediateVoidFuture()
    }

    private fun load(item: MediaItem, startPositionMs: Long): ListenableFuture<*> {
        loadFuture?.cancel(false)
        val bookId = MediaId.parse(item.mediaId) as? MediaId.Book
        mediaItem = item
        book = null
        error = null
        engine.load(null, 0)
        nowPlaying.setBook(null)
        if (bookId == null) {
            fail("Not a book: ${item.mediaId}", PlaybackException.ERROR_CODE_BAD_VALUE)
            return Futures.immediateVoidFuture()
        }
        playbackState = Player.STATE_BUFFERING
        updateEngine()
        val future = scope.future {
            try {
                val loaded = loader.load(bookId.uri.toUri(), item.mediaMetadata.title?.toString())
                val start = if (startPositionMs == C.TIME_UNSET) {
                    savedIndex(loaded)
                } else {
                    loaded.timeline.wordStart(loaded.timeline.indexAtTime(startPositionMs.toDouble(), settings.timing))
                }
                book = loaded
                mediaItem = item.buildUpon()
                    .setMediaMetadata(item.mediaMetadata.buildUpon().setTitle(loaded.title).build())
                    .build()
                engine.load(loaded.timeline, start)
                playbackState = Player.STATE_READY
                nowPlaying.setBook(
                    NowPlaying.Book(loaded.id, loaded.title, loaded.raw, loaded.chunks),
                    loaded.normalized.rawOffsetOf(start),
                )
                savePosition(start)
                updateEngine()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                fail("MorseCar no longer has access to this book's folder. Add the folder again.", PlaybackException.ERROR_CODE_IO_NO_PERMISSION, e)
            } catch (e: FileNotFoundException) {
                fail(e.message ?: "The book file is missing", PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, e)
            } catch (e: IOException) {
                fail(e.message ?: "Couldn't read the book", PlaybackException.ERROR_CODE_IO_UNSPECIFIED, e)
            } catch (e: OutOfMemoryError) {
                fail("This book is too big to load", PlaybackException.ERROR_CODE_IO_UNSPECIFIED, e)
            } catch (e: Exception) {
                fail("Couldn't open the book: ${e.message}", PlaybackException.ERROR_CODE_UNSPECIFIED, e)
            }
        }
        loadFuture = future
        return future
    }

    private suspend fun savedIndex(loaded: LoadedBook): Int {
        val saved = positions.snapshot().position(loaded.uri.toString()) ?: return loaded.startIndex
        val index = loaded.normalized.indexForRawOffset(saved.rawOffset)
        // A finished book starts over.
        return if (index >= loaded.normalized.length) loaded.startIndex else loaded.timeline.wordStart(index)
    }

    private fun indexAtTime(timeMs: Double): Int {
        val timeline = book?.timeline ?: return 0
        return timeline.wordStart(timeline.indexAtTime(timeMs, settings.timing))
    }

    /** Playback time of what's audible, at the current speeds. */
    private fun currentTimeMs(): Double {
        val b = book ?: return 0.0
        val (index, framesInto) = engine.position()
        val start = b.timeline.timeAtMs(index, settings.timing)
        if (index >= b.timeline.length) return start
        val slotMs = settings.timing.durationMs(b.timeline.unitsOf(index))
        return start + minOf(framesInto * 1000.0 / engine.sampleRate, slotMs)
    }

    private fun setPlayWhenReadyInternal(value: Boolean, reason: Int) {
        playWhenReady = value
        playWhenReadyReason = reason
        if (!value) suppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE
    }

    /** Starts or stops the engine (and everything that goes with it) to match the player state. */
    private fun updateEngine() {
        if (playWhenReady && playbackState == Player.STATE_READY &&
            suppression == Player.PLAYBACK_SUPPRESSION_REASON_NONE && !focus.request()
        ) {
            setPlayWhenReadyInternal(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        }
        val active = isActivelyPlaying
        engine.setPlaying(active)
        setNoisyReceiverRegistered(active)
        if (active) startTicker() else stopTicker()
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            var sinceSave = 0L
            while (isActive) {
                book?.let { publishPosition(it, engine.position().charIndex) }
                delay(TICK_MS)
                sinceSave += TICK_MS
                if (sinceSave >= SAVE_INTERVAL_MS) {
                    sinceSave = 0
                    savePosition()
                }
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
        book?.let { publishPosition(it, engine.position().charIndex) }
    }

    private fun publishPosition(b: LoadedBook, index: Int) {
        nowPlaying.setRawPosition(b.normalized.rawOffsetOf(index.coerceIn(0, b.normalized.length)))
    }

    private fun savePosition(index: Int = engine.position().charIndex) {
        val b = book ?: return
        val position = SavedPosition(b.normalized.rawOffsetOf(index.coerceIn(0, b.normalized.length)), b.raw.length)
        saveScope.launch { positions.save(b.uri.toString(), b.title, position) }
    }

    private fun onPlaybackEnded() {
        if (playbackState != Player.STATE_READY) return  // stopped or reloaded since
        playbackState = Player.STATE_ENDED
        savePosition()
        updateEngine()
        focus.abandon()
        invalidateState()
    }

    private fun onPlaybackError(message: String, code: Int) {
        fail(message, code)
        invalidateState()
    }

    private fun fail(message: String, code: Int, cause: Throwable? = null) {
        error = PlaybackException(message, cause, code)
        playbackState = Player.STATE_IDLE
        updateEngine()
    }

    private fun setNoisyReceiverRegistered(register: Boolean) {
        if (register == noisyRegistered) return
        noisyRegistered = register
        if (register) {
            ContextCompat.registerReceiver(
                context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } else {
            context.unregisterReceiver(noisyReceiver)
        }
    }

    companion object {
        const val SKIP_SHORT_MS = 15_000L
        const val SKIP_LONG_MS = 30_000L
        private const val TICK_MS = 100L
        private const val SAVE_INTERVAL_MS = 10_000L

        private val MEDIA3_AUDIO_ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        private val COMMANDS = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES,
        ).build()
    }
}
