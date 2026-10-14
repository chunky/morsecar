package org.icculus.chunky.morsecar.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.icculus.chunky.morsecar.appGraph
import org.icculus.chunky.morsecar.library.FolderRoot
import org.icculus.chunky.morsecar.library.LibraryEntry
import org.icculus.chunky.morsecar.library.PositionStore
import org.icculus.chunky.morsecar.library.RecentBook
import org.icculus.chunky.morsecar.morse.MorsePreset
import org.icculus.chunky.morsecar.morse.MorseTiming
import org.icculus.chunky.morsecar.playback.LibraryItems
import org.icculus.chunky.morsecar.playback.NowPlaying
import org.icculus.chunky.morsecar.playback.PlaybackService
import org.icculus.chunky.morsecar.settings.PlaybackSettings

data class PlayerUiState(
    val connected: Boolean = false,
    val hasBook: Boolean = false,
    val title: String? = null,
    val isPlaying: Boolean = false,
    /** Playing, or about to be (e.g. paused only for a navigation prompt). */
    val playWhenReady: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
)

sealed interface FolderListing {
    data object Loading : FolderListing
    data class Loaded(val entries: List<LibraryEntry>, val progress: PositionStore.Snapshot) : FolderListing
    data class Failed(val message: String) : FolderListing
}

/**
 * The phone UI's connection to the playback service (through a MediaController,
 * like any other controller), plus settings and library browsing.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.appGraph

    private val controllerFuture = MediaController.Builder(
        app, SessionToken(app, ComponentName(app, PlaybackService::class.java)),
    ).buildAsync()
    private var controller: MediaController? = null
    /** Requests made before the controller connected. */
    private val pendingActions = mutableListOf<(MediaController) -> Unit>()

    private val _player = MutableStateFlow(PlayerUiState())
    val player: StateFlow<PlayerUiState> = _player.asStateFlow()

    val nowPlayingBook: StateFlow<NowPlaying.Book?> = graph.nowPlaying.book
    val rawPosition: StateFlow<Int> = graph.nowPlaying.rawPosition

    /** Slider values not yet saved, so dragging feels immediate. */
    private val draft = MutableStateFlow<PlaybackSettings?>(null)
    private val settingsWrites = Channel<PlaybackSettings>(Channel.CONFLATED)
    val settings: StateFlow<PlaybackSettings> = combine(graph.settings.settings, draft) { saved, pending -> pending ?: saved }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackSettings.DEFAULT)

    /** Books bundled with the app, listed with the folders; null if there are none. */
    val samplesFolder: LibraryEntry.Folder? get() = graph.library.samplesFolder

    val folders: StateFlow<List<FolderRoot>> = graph.library.folders
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _recent = MutableStateFlow<List<RecentBook>>(emptyList())
    val recent: StateFlow<List<RecentBook>> = _recent.asStateFlow()

    /** Folders opened from the library's top level, innermost last; empty at the top level. */
    private val _libraryPath = MutableStateFlow<List<LibraryEntry.Folder>>(emptyList())
    val libraryPath: StateFlow<List<LibraryEntry.Folder>> = _libraryPath.asStateFlow()

    private val _listing = MutableStateFlow<FolderListing>(FolderListing.Loading)
    val listing: StateFlow<FolderListing> = _listing.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private val controllerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    init {
        controllerFuture.addListener({
            try {
                val c = controllerFuture.get().also { it.addListener(controllerListener) }
                controller = c
                pendingActions.forEach { it(c) }
                pendingActions.clear()
                refresh()
            } catch (e: Exception) {
                _player.value = PlayerUiState(error = "Couldn't connect to the player: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(app))

        // The position moves continuously, but the controller only reports changes in state.
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(POSITION_POLL_MS)
            }
        }

        viewModelScope.launch {
            for (s in settingsWrites) {
                graph.settings.update(s)
                if (draft.value == s) draft.value = null
            }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        _player.value = PlayerUiState(
            connected = true,
            hasBook = c.currentMediaItem != null,
            title = c.mediaMetadata.title?.toString() ?: c.currentMediaItem?.mediaMetadata?.title?.toString(),
            isPlaying = c.isPlaying,
            playWhenReady = c.playWhenReady && c.playbackState != Player.STATE_ENDED,
            isLoading = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0,
            error = c.playerError?.message,
        )
    }

    // Playback

    fun playPause() {
        val c = controller ?: return
        if (c.playWhenReady && c.playbackState != Player.STATE_ENDED) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun sendCommand(command: SessionCommand) {
        controller?.sendCustomCommand(command, Bundle.EMPTY)
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun openBook(uri: Uri, title: String) = play(LibraryItems.book(uri.toString(), title))

    /** A voice request from Google Assistant; the service picks the book. Blank means "resume". */
    fun playFromSearch(query: String) = play(
        MediaItem.Builder()
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build())
            .build(),
    )

    private fun play(item: MediaItem) = withController { c ->
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    private fun withController(action: (MediaController) -> Unit) {
        controller?.let(action) ?: pendingActions.add(action)
    }

    // Settings

    fun setTiming(timing: MorseTiming) = setSettings(settings.value.copy(timing = timing))

    fun setToneHz(hz: Double) = setSettings(settings.value.copy(toneHz = hz))

    fun applyPreset(preset: MorsePreset) = setSettings(settings.value.copy(timing = preset.timing))

    private fun setSettings(s: PlaybackSettings) {
        val clamped = s.clamped()
        draft.value = clamped
        settingsWrites.trySend(clamped)
    }

    // Library

    /** Call when the library screen opens. */
    fun refreshLibrary() {
        viewModelScope.launch { _recent.value = graph.positions.snapshot().recent(RECENT_LIMIT) }
        _libraryPath.value.lastOrNull()?.let(::loadListing)
    }

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch {
            try {
                graph.library.addFolder(treeUri)
            } catch (e: SecurityException) {
                _messages.tryEmit("Couldn't get access to that folder")
            }
        }
    }

    fun removeFolder(folder: FolderRoot) {
        viewModelScope.launch { graph.library.removeFolder(folder.treeUri) }
    }

    fun openFolder(folder: LibraryEntry.Folder) {
        _libraryPath.value = _libraryPath.value + folder
        loadListing(folder)
    }

    /** Goes up one folder; returns false at the top level. */
    fun navigateUp(): Boolean {
        val path = _libraryPath.value
        if (path.isEmpty()) return false
        _libraryPath.value = path.dropLast(1)
        path.dropLast(1).lastOrNull()?.let(::loadListing)
        return true
    }

    private fun loadListing(folder: LibraryEntry.Folder) {
        _listing.value = FolderListing.Loading
        viewModelScope.launch {
            _listing.value = try {
                FolderListing.Loaded(graph.library.list(folder.uri), graph.positions.snapshot())
            } catch (e: SecurityException) {
                FolderListing.Failed("MorseCar lost access to this folder. Remove it and add it again.")
            } catch (e: Exception) {
                FolderListing.Failed("Couldn't read this folder: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        controller?.removeListener(controllerListener)
        MediaController.releaseFuture(controllerFuture)
    }

    private companion object {
        const val POSITION_POLL_MS = 250L
        const val RECENT_LIMIT = 10
    }
}
