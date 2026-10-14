package org.icculus.chunky.morsecar.library

import androidx.core.net.toUri
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.icculus.chunky.morsecar.text.BookText
import org.icculus.chunky.morsecar.text.GutenbergText
import org.icculus.chunky.morsecar.text.NotTextException
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** A folder the user picked; [uri] is the document URI of its top level. */
data class FolderRoot(val treeUri: Uri, val name: String) {
    val uri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
}

sealed interface LibraryEntry {
    val uri: Uri
    val name: String

    data class Folder(override val uri: Uri, override val name: String) : LibraryEntry
    data class Book(override val uri: Uri, override val name: String, val knownTitle: String? = null) : LibraryEntry {
        /** The book's own title if known, else the file name without ".txt". */
        val title: String get() = knownTitle ?: bookTitle(name)
    }
}

fun bookTitle(fileName: String): String = fileName.removeSuffix(".txt").removeSuffix(".TXT")

/**
 * Folders picked through the Storage Access Framework, and the books in them,
 * plus the sample books bundled in the APK (`asset:` URIs).
 * Queries DocumentsContract directly: DocumentFile does one query per file
 * attribute, which is slow for folders with many books.
 */
class Library(private val context: Context, private val store: DataStore<Preferences>) {
    private val resolver: ContentResolver get() = context.contentResolver

    /** The bundled samples, shown alongside the user's folders; null if the APK has none. */
    val samplesFolder: LibraryEntry.Folder? by lazy {
        if (sampleFileNames().isEmpty()) null else LibraryEntry.Folder(SAMPLES_URI, "Samples")
    }

    val folders: Flow<List<FolderRoot>> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            prefs[FOLDERS].orEmpty()
                .map { it.toUri() }
                .map { FolderRoot(it, prefs[nameKey(it)] ?: it.lastPathSegment.orEmpty()) }
                .sortedBy { it.name.lowercase() }
        }

    /** Keeps access to [treeUri] across reboots and adds it to the folder list. */
    suspend fun addFolder(treeUri: Uri) {
        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val name = withContext(Dispatchers.IO) { queryName(root) } ?: treeUri.lastPathSegment.orEmpty()
        store.edit {
            it[FOLDERS] = it[FOLDERS].orEmpty() + treeUri.toString()
            it[nameKey(treeUri)] = name
        }
    }

    suspend fun removeFolder(treeUri: Uri) {
        try {
            resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Already gone.
        }
        store.edit {
            it[FOLDERS] = it[FOLDERS].orEmpty() - treeUri.toString()
            it.remove(nameKey(treeUri))
        }
    }

    /** Subfolders, then text files, in [folderUri] (a document URI within a picked tree). */
    suspend fun list(folderUri: Uri): List<LibraryEntry> = withContext(Dispatchers.IO) {
        if (folderUri == SAMPLES_URI) return@withContext listSamples()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            folderUri, DocumentsContract.getDocumentId(folderUri),
        )
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val entries = mutableListOf<LibraryEntry>()
        val cursor = resolver.query(childrenUri, projection, null, null, null)
            ?: throw IOException("Couldn't read this folder")
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                val mime = it.getString(2)
                if (name.startsWith(".")) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(folderUri, id)
                when {
                    mime == Document.MIME_TYPE_DIR -> entries.add(LibraryEntry.Folder(uri, name))
                    mime == "text/plain" || name.endsWith(".txt", ignoreCase = true) ->
                        entries.add(LibraryEntry.Book(uri, name))
                }
            }
        }
        entries.sortedWith(compareBy<LibraryEntry> { it is LibraryEntry.Book }.thenBy { it.name.lowercase() })
    }

    suspend fun displayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        if (uri == SAMPLES_URI) return@withContext samplesFolder?.name
        if (uri.isSample) return@withContext uri.lastPathSegment
        try {
            queryName(uri)
        } catch (_: Exception) {
            null
        }
    }

    /** The book's text. Throws [IOException] (including [NotTextException]) or [SecurityException]. */
    suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = try {
            if (uri.isSample) {
                context.assets.open(uri.assetPath).use { it.readAtMost(BookText.MAX_BYTES + 1) }
            } else {
                resolver.openInputStream(uri)?.use { it.readAtMost(BookText.MAX_BYTES + 1) }
            }
                ?: throw FileNotFoundException("Couldn't open the book")
        } catch (e: FileNotFoundException) {
            throw FileNotFoundException("The book file is missing or was moved").apply { initCause(e) }
        }
        BookText.decode(bytes)
    }

    /** Reads up to [limit] bytes; enough to tell whether a file is too big without reading all of it. */
    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun sampleFileNames(): List<String> =
        context.assets.list(SAMPLES_ASSET_DIR).orEmpty().filter { it.endsWith(".txt", ignoreCase = true) }.sorted()

    /** Titles come from the Gutenberg header when there is one; the start of each file is enough. */
    private fun listSamples(): List<LibraryEntry> = sampleFileNames().map { name ->
        val head = context.assets.open("$SAMPLES_ASSET_DIR/$name").use { String(it.readAtMost(SAMPLE_HEADER_BYTES), Charsets.UTF_8) }
        LibraryEntry.Book(sampleUri(name), name, GutenbergText.title(head))
    }.sortedBy { it.title.lowercase() }

    private fun queryName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    companion object {
        private const val SAMPLES_ASSET_DIR = "samples"
        private const val SAMPLE_HEADER_BYTES = 8192
        private const val SAMPLE_SCHEME = "asset"

        val SAMPLES_URI: Uri = Uri.Builder().scheme(SAMPLE_SCHEME).path("/$SAMPLES_ASSET_DIR").build()

        private fun sampleUri(name: String): Uri =
            Uri.Builder().scheme(SAMPLE_SCHEME).path("/$SAMPLES_ASSET_DIR/$name").build()

        private val Uri.isSample: Boolean get() = scheme == SAMPLE_SCHEME
        private val Uri.assetPath: String get() = path.orEmpty().removePrefix("/")

        private val FOLDERS = stringSetPreferencesKey("folders")
        private fun nameKey(treeUri: Uri) = stringPreferencesKey("folder_name|$treeUri")
    }
}
