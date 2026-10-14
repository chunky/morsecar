package org.icculus.chunky.morsecar.playback

import androidx.core.net.toUri
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import org.icculus.chunky.morsecar.MainActivity
import org.icculus.chunky.morsecar.appGraph
import org.icculus.chunky.morsecar.library.LibraryEntry
import org.icculus.chunky.morsecar.library.MediaId
import org.icculus.chunky.morsecar.library.bookTitle
import org.icculus.chunky.morsecar.settings.PlaybackSettings

/**
 * Hosts the media session that the phone UI, the notification, Bluetooth, and
 * Android Auto all connect to, plus the browse tree Android Auto shows:
 * Recent and Folders tabs, then folders and books.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { appGraph }
    private lateinit var player: MorsePlayer
    private var session: MediaLibrarySession? = null
    private val searchResults = HashMap<String, List<MediaItem>>()

    override fun onCreate() {
        super.onCreate()
        player = MorsePlayer(this, BookLoader(graph.library), graph.positions, graph.nowPlaying, scope, graph.appScope)
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(PlaybackCommands.buttons(PlaybackSettings.DEFAULT))
            .build()
        scope.launch {
            graph.settings.settings.collect { settings ->
                player.applySettings(settings)
                session?.setMediaButtonPreferences(PlaybackCommands.buttons(settings))
            }
        }
        scope.launch { restoreLastBook() }
    }

    /** Loads (paused) whatever was playing last, unless something else was chosen meanwhile. */
    private suspend fun restoreLastBook() {
        val item = lastBookItem() ?: return
        if (player.currentMediaItem != null) return
        player.setMediaItem(item)
        player.prepare()
    }

    private suspend fun lastBookItem(): MediaItem? {
        val snapshot = graph.positions.snapshot()
        val uri = snapshot.currentBook ?: return null
        return LibraryItems.book(uri, snapshot.title(uri) ?: "Last book", snapshot.position(uri))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private fun skipBy(deltaMs: Long) {
        val duration = player.duration
        var target = (player.currentPosition + deltaMs).coerceAtLeast(0)
        if (duration != C.TIME_UNSET) target = target.coerceAtMost(duration)
        player.seekTo(target)
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .apply { PlaybackCommands.all.forEach(::add) }
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val skip = PlaybackCommands.skipMs(customCommand)
            when {
                skip != null -> skipBy(skip)
                customCommand.customAction == PlaybackCommands.NEXT_PRESET.customAction ->
                    graph.appScope.launch { graph.settings.cyclePreset() }
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(LibraryItems.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val children = try {
                children(parentId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                listOf(LibraryItems.info("MorseCar lost access to this folder. Add it again on your phone."))
            } catch (e: Exception) {
                listOf(LibraryItems.info("Couldn't read this folder"))
            } ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            LibraryResult.ofItemList(children.page(page, pageSize), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val item = when (val id = MediaId.parse(mediaId)) {
                MediaId.Root -> LibraryItems.root()
                MediaId.Recent -> LibraryItems.recent()
                MediaId.Folders -> LibraryItems.folders()
                is MediaId.Book -> resolveBook(id)
                is MediaId.Folder -> LibraryItems.folder(id.uri, graph.library.displayName(id.uri.toUri()) ?: "Folder")
                MediaId.Info, null -> null
            }
            item?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        /**
         * Controllers send only IDs (e.g. Android Auto) or a voice search query
         * ("play Moby Dick on MorseCar"); fill in the details for the player.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future {
            mediaItems.mapNotNull { item ->
                val bookId = MediaId.parse(item.mediaId) as? MediaId.Book
                val query = item.requestMetadata.searchQuery
                when {
                    bookId != null -> resolveBook(bookId)
                    query == null -> null
                    query.isBlank() -> lastBookItem()  // "play MorseCar"
                    else -> searchBooks(query).firstOrNull()
                }
            }.ifEmpty { throw UnsupportedOperationException("No matching books") }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val results = searchBooks(query)
            searchResults[query] = results
            session.notifySearchResultChanged(browser, query, results.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val results = searchResults[query] ?: searchBooks(query)
            LibraryResult.ofItemList(results.page(page, pageSize), params)
        }

        /** "Play" from Bluetooth or Android Auto when MorseCar isn't running: resume the last book. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val item = lastBookItem() ?: throw UnsupportedOperationException("Nothing to resume")
            // An unset start position makes the player use the saved position.
            MediaSession.MediaItemsWithStartPosition(listOf(item), 0, C.TIME_UNSET)
        }
    }

    /** Recent books, then books in the picked folders, whose titles contain every word of [query]. */
    private suspend fun searchBooks(query: String): List<MediaItem> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun matches(title: String) = title.lowercase().let { t -> words.all { it in t } }
        val snapshot = graph.positions.snapshot()
        val found = LinkedHashMap<String, MediaItem>()
        for (book in snapshot.recent(RECENT_LIMIT)) {
            if (matches(book.title)) found[book.uri] = LibraryItems.book(book.uri, book.title, book.position)
        }
        // Breadth-first through the folders, bounded so a huge tree can't stall the car's UI.
        val queue = ArrayDeque(
            (listOfNotNull(graph.library.samplesFolder?.uri) + graph.library.folders.first().map { it.uri }).map { it to 0 },
        )
        var visited = 0
        while (queue.isNotEmpty() && visited < SEARCH_MAX_FOLDERS && found.size < SEARCH_MAX_RESULTS) {
            val (folder, depth) = queue.removeFirst()
            visited++
            val entries = try {
                graph.library.list(folder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue  // an unreadable folder shouldn't stop the search
            }
            for (entry in entries) {
                when (entry) {
                    is LibraryEntry.Folder -> if (depth < SEARCH_MAX_DEPTH) queue.addLast(entry.uri to depth + 1)
                    is LibraryEntry.Book -> if (matches(entry.title)) {
                        val uri = entry.uri.toString()
                        found.getOrPut(uri) { LibraryItems.book(uri, entry.title, snapshot.position(uri)) }
                    }
                }
            }
        }
        return found.values.toList()
    }

    private fun <T> List<T>.page(page: Int, pageSize: Int): List<T> {
        val from = (page.toLong() * pageSize).coerceIn(0, size.toLong()).toInt()
        val to = (from.toLong() + pageSize).coerceIn(from.toLong(), size.toLong()).toInt()
        return subList(from, to)
    }

    private suspend fun resolveBook(id: MediaId.Book): MediaItem {
        val snapshot = graph.positions.snapshot()
        val title = snapshot.title(id.uri) ?: graph.library.displayName(id.uri.toUri())?.let(::bookTitle) ?: "Book"
        return LibraryItems.book(id.uri, title, snapshot.position(id.uri))
    }

    /** Children of a browse-tree node, or null if [parentId] isn't one. */
    private suspend fun children(parentId: String): List<MediaItem>? = when (val id = MediaId.parse(parentId)) {
        MediaId.Root -> listOf(LibraryItems.recent(), LibraryItems.folders())
        MediaId.Recent -> graph.positions.snapshot().recent(RECENT_LIMIT)
            .map { LibraryItems.book(it.uri, it.title, it.position) }
            .ifEmpty { listOf(LibraryItems.info("Books you play will appear here")) }
        MediaId.Folders -> (
            listOfNotNull(graph.library.samplesFolder?.let { LibraryItems.folder(it.uri.toString(), it.name) }) +
                graph.library.folders.first().map { LibraryItems.folder(it.uri.toString(), it.name) }
            ).ifEmpty { listOf(LibraryItems.info("Add a folder in MorseCar on your phone")) }
        is MediaId.Folder -> {
            val snapshot = graph.positions.snapshot()
            graph.library.list(id.uri.toUri()).map { entry ->
                when (entry) {
                    is LibraryEntry.Folder -> LibraryItems.folder(entry.uri.toString(), entry.name)
                    is LibraryEntry.Book -> LibraryItems.book(entry.uri.toString(), entry.title, snapshot.position(entry.uri.toString()))
                }
            }.ifEmpty { listOf(LibraryItems.info("No .txt books in this folder")) }
        }
        is MediaId.Book, MediaId.Info, null -> null
    }

    private companion object {
        const val RECENT_LIMIT = 20
        const val SEARCH_MAX_FOLDERS = 200
        const val SEARCH_MAX_DEPTH = 4
        const val SEARCH_MAX_RESULTS = 50
    }
}
