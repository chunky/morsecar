package org.icculus.chunky.morsecar.playback

import androidx.core.net.toUri
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.icculus.chunky.morsecar.library.MediaId
import org.icculus.chunky.morsecar.library.PositionStore
import org.icculus.chunky.morsecar.library.SavedPosition
import org.icculus.chunky.morsecar.morse.NormalizedText
import org.icculus.chunky.morsecar.settings.PlaybackSettings
import org.icculus.chunky.morsecar.text.WordWindow
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
 * buttons) skip back/forward 5 words; seek back/forward skip 2 words.
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
    /** The book's title drawn as artwork, for Android Auto, the notification, and the lock screen. */
    private var artwork: ByteArray? = null
    private var artworkJob: Job? = null
    /** The last two completed words, shown as the title line in Android Auto. */
    private var sendingText: String? = null
    private var sendingWordStart = -1
    private var lastWordIndex = -1
    private var lastPlayedWords = ArrayDeque<String>(3)
    private var pendingRandomSeek = false
    private var released = false
    private val mainHandler = Handler(Looper.getMainLooper())
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
                    .setTitle(sendingText ?: b?.title ?: item.mediaMetadata.title)
                    .setAlbumTitle(b?.title ?: item.mediaMetadata.title)
                    .setArtist(settings.describe())
                    .apply { artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
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
        released = true
        clearArtwork()
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
            clearArtwork()
            playbackState = Player.STATE_IDLE
            engine.load(null, 0)
            nowPlaying.setBook(null)
            updateEngine()
            return Futures.immediateVoidFuture()
        }
        val current = mediaItem
        if (current != null && item.mediaId == current.mediaId && startPositionMs == C.TIME_UNSET) {
            // Same book (e.g. tapped again in Android Auto, or a speed change from there): keep going.
            loadFuture?.takeIf { !it.isDone }?.let { return it }
            if (book != null) return Futures.immediateVoidFuture()
        }
        return load(item, startPositionMs)
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val b = book ?: return Futures.immediateVoidFuture()
        val target = when (seekCommand) {
            Player.COMMAND_SEEK_BACK -> skipWords(b, -SKIP_SHORT_WORDS)
            Player.COMMAND_SEEK_FORWARD -> skipWords(b, SKIP_SHORT_WORDS)
            Player.COMMAND_SEEK_TO_PREVIOUS -> skipWords(b, -SKIP_LONG_WORDS)
            Player.COMMAND_SEEK_TO_NEXT -> skipWords(b, SKIP_LONG_WORDS)
            else -> if (positionMs == C.TIME_UNSET) 0 else indexAtTime(positionMs.toDouble())
        }
        engine.seekTo(target)
        lastWordIndex = b.timeline.wordStart(target)
        pendingRandomSeek = false
        lastPlayedWords.clear()
        if (playbackState == Player.STATE_ENDED) playbackState = Player.STATE_READY
        publishPosition(b, target)
        savePosition(target)
        updateEngine()
        return Futures.immediateVoidFuture()
    }

    /** Skip forward or backward by [wordCount] words (negative to go back). */
    private fun skipWords(b: LoadedBook, wordCount: Int): Int {
        val current = engine.position().charIndex
        var offset = current
        val raw = b.raw
        val rules = b.display

        if (wordCount > 0) {
            // Skip forward
            repeat(wordCount) {
                // Move past current word
                while (offset < raw.length && !rules.isSeparator(raw, offset)) offset++
                // Move past separators to next word
                while (offset < raw.length && rules.isSeparator(raw, offset)) offset++
            }
        } else {
            // Skip backward
            repeat(-wordCount) {
                // Move to before current position
                offset = (offset - 1).coerceAtLeast(0)
                // Move past any separators
                while (offset > 0 && rules.isSeparator(raw, offset)) offset--
                // Move to start of this word
                while (offset > 0 && !rules.isSeparator(raw, offset - 1)) offset--
            }
        }

        return b.timeline.wordStart(offset.coerceIn(0, b.normalized.length))
    }

    private fun load(item: MediaItem, startPositionMs: Long): ListenableFuture<*> {
        loadFuture?.cancel(false)
        val bookId = MediaId.parse(item.mediaId) as? MediaId.Book
        mediaItem = item
        book = null
        error = null
        clearArtwork()
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
                lastWordIndex = loaded.timeline.wordStart(start)
                pendingRandomSeek = false
                lastPlayedWords.clear()
                playbackState = Player.STATE_READY
                nowPlaying.setBook(
                    NowPlaying.Book(loaded.id, loaded.title, loaded.raw, loaded.chunks, loaded.markup, loaded.display),
                )
                publishPosition(loaded, start)
                renderArtwork(loaded)
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
                val b = book
                val pos = engine.position()
                val currentIndex = pos.charIndex
                b?.let { publishPosition(it, currentIndex) }

                // Track word completions and handle random mode
                if (b != null && currentIndex < b.timeline.length) {
                    val currentWordStart = b.timeline.wordStart(currentIndex)
                    val isInGap = currentIndex < b.normalized.length && NormalizedText.isGap(b.normalized.text[currentIndex])

                    // Calculate if we're near the end of the gap (80% through)
                    // This ensures the audio buffer has drained before we seek
                    val isNearEndOfGap = if (isInGap) {
                        val gapDurationMs = settings.timing.durationMs(b.timeline.unitsOf(currentIndex))
                        val gapDurationFrames = (gapDurationMs * engine.sampleRate / 1000).toLong()
                        pos.framesIntoChar > (gapDurationFrames * 0.8).toLong()
                    } else {
                        false
                    }

                    // Detect when we've completed a word and moved to the next
                    if (lastWordIndex >= 0 && currentWordStart != lastWordIndex) {
                        // Extract the completed word and add it to history
                        val wordEnd = findWordEnd(b, lastWordIndex)
                        if (wordEnd > lastWordIndex) {
                            val rawStart = b.normalized.rawOffsetOf(lastWordIndex)
                            val rawEnd = b.normalized.rawOffsetOf(wordEnd)
                            val word = b.raw.substring(rawStart, rawEnd.coerceAtMost(b.raw.length)).trim()
                            if (word.isNotEmpty()) {
                                lastPlayedWords.addLast(word)
                                if (lastPlayedWords.size > 3) lastPlayedWords.removeFirst()
                                updateSendingTextFromHistory()
                            }
                        }

                        // In random mode, mark that we need to seek
                        if (settings.randomWords) {
                            pendingRandomSeek = true
                        }

                        lastWordIndex = currentWordStart
                    }

                    // Only seek when we're near the end of a gap to minimize buffer flush impact
                    if (pendingRandomSeek && isNearEndOfGap) {
                        lastWordIndex = seekToRandomWord(b)
                        pendingRandomSeek = false
                    }
                }

                delay(TICK_MS)
                sinceSave += TICK_MS
                if (sinceSave >= SAVE_INTERVAL_MS) {
                    sinceSave = 0
                    savePosition()
                }
            }
        }
    }

    private fun findWordEnd(b: LoadedBook, wordStart: Int): Int {
        var end = wordStart
        while (end < b.normalized.length && !NormalizedText.isGap(b.normalized.text[end])) {
            end++
        }
        return end
    }

    private suspend fun seekToRandomWord(b: LoadedBook): Int {
        // Pause briefly to let the audio buffer drain completely, preventing clicks
        val wasPlaying = playWhenReady && playbackState == Player.STATE_READY
        if (wasPlaying) {
            engine.setPlaying(false)
            delay(50) // Let hardware buffer drain
        }

        val randomIndex = (0 until b.normalized.length).random()
        val wordStart = b.timeline.wordStart(randomIndex)
        engine.seekTo(wordStart)
        publishPosition(b, wordStart)

        if (wasPlaying) {
            engine.setPlaying(true)
        }

        return wordStart
    }

    private fun updateSendingTextFromHistory() {
        if (lastPlayedWords.isEmpty()) {
            sendingText = null
            return
        }

        // Show the last 2 words played
        val words = lastPlayedWords.takeLast(2)
        val text = words.joinToString(" ")
        if (text.length <= MAX_SENDING_TEXT_CHARS) {
            sendingText = text
        } else {
            // If too long, just show the last word
            sendingText = words.last().take(MAX_SENDING_TEXT_CHARS)
        }

        mainHandler.post { if (!released) invalidateState() }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
        book?.let { publishPosition(it, engine.position().charIndex) }
    }

    private fun publishPosition(b: LoadedBook, index: Int) {
        val rawOffset = b.normalized.rawOffsetOf(index.coerceIn(0, b.normalized.length))
        nowPlaying.setRawPosition(rawOffset)
        updateSendingText(b, rawOffset)
    }

    /** Updates the title line when the word being sent changes (every second or few). */
    private fun updateSendingText(b: LoadedBook, rawOffset: Int) {
        val window = WordWindow.around(b.raw, rawOffset, wordsBefore = 2, wordsAfter = 0, rules = b.display) ?: return
        if (window.currentStart == sendingWordStart) return
        sendingWordStart = window.currentStart
        sendingText = window.endingBeforeCurrent(MAX_SENDING_TEXT_CHARS)
        // Posted, since this can run inside a handle* method, where the state is being updated.
        mainHandler.post { if (!released) invalidateState() }
    }

    private fun renderArtwork(b: LoadedBook) {
        artworkJob?.cancel()
        artworkJob = scope.launch {
            val png = withContext(Dispatchers.Default) { BookArtwork.render(b.title, b.author) }
            if (book !== b) return@launch
            artwork = png
            invalidateState()
        }
    }

    private fun clearArtwork() {
        artworkJob?.cancel()
        artwork = null
        sendingText = null
        sendingWordStart = -1
        lastPlayedWords.clear()
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
        private const val SKIP_SHORT_WORDS = 2
        private const val SKIP_LONG_WORDS = 3
        private const val TICK_MS = 100L
        private const val SAVE_INTERVAL_MS = 10_000L
        /** Short enough not to be cut off on the car screen; the current word is always kept. */
        private const val MAX_SENDING_TEXT_CHARS = 24

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
