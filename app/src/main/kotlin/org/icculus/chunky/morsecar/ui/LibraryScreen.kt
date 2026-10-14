package org.icculus.chunky.morsecar.ui

import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.icculus.chunky.morsecar.library.FolderRoot
import org.icculus.chunky.morsecar.library.LibraryEntry
import org.icculus.chunky.morsecar.library.SavedPosition
import kotlin.math.roundToInt

/** Choosing a book: recent books and picked folders, then folders and books within them. */
@Composable
fun LibraryScreen(vm: MainViewModel, onClose: () -> Unit) {
    val path by vm.libraryPath.collectAsStateWithLifecycle()
    val goBack = { if (!vm.navigateUp()) onClose() }
    BackHandler(onBack = goBack)

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = goBack) { Text("Back") }
            Text(
                path.lastOrNull()?.name ?: "Choose a book",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (path.isEmpty()) {
            LibraryTop(vm, onBookChosen = onClose)
        } else {
            FolderContents(vm, onBookChosen = onClose)
        }
    }
}

@Composable
private fun LibraryTop(vm: MainViewModel, onBookChosen: () -> Unit) {
    val folders by vm.folders.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf<FolderRoot?>(null) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addFolder(uri)
    }

    LazyColumn(Modifier.fillMaxSize()) {
        if (recent.isNotEmpty()) {
            sectionHeader("Continue listening")
            items(recent, key = { "recent:" + it.uri }) { book ->
                EntryRow(book.title, progressText(book.position)) {
                    vm.openBook(book.uri.toUri(), book.title)
                    onBookChosen()
                }
            }
        }
        sectionHeader("Folders")
        vm.samplesFolder?.let { samples ->
            item(key = "samples") {
                EntryRow(samples.name, "Books that come with MorseCar") { vm.openFolder(samples) }
            }
        }
        if (folders.isEmpty()) {
            item { Text("Add a folder of .txt books from your phone.", modifier = Modifier.padding(vertical = 8.dp)) }
        }
        items(folders, key = { "folder:" + it.treeUri }) { folder ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    EntryRow(folder.name, null) { vm.openFolder(LibraryEntry.Folder(folder.uri, folder.name)) }
                }
                TextButton(onClick = { confirmRemove = folder }) { Text("Remove") }
            }
        }
        item {
            Button(onClick = { pickFolder.launch(null) }, modifier = Modifier.padding(vertical = 12.dp)) {
                Text("Add folder")
            }
        }
    }

    confirmRemove?.let { folder ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${folder.name}?") },
            text = { Text("MorseCar will stop showing this folder. The files themselves aren't touched.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeFolder(folder)
                    confirmRemove = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FolderContents(vm: MainViewModel, onBookChosen: () -> Unit) {
    val listing by vm.listing.collectAsStateWithLifecycle()
    when (val l = listing) {
        FolderListing.Loading -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        is FolderListing.Failed -> Text(l.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        is FolderListing.Loaded -> LazyColumn(Modifier.fillMaxSize()) {
            if (l.entries.isEmpty()) item { Text("No .txt books here.", modifier = Modifier.padding(vertical = 8.dp)) }
            items(l.entries, key = { it.uri.toString() }) { entry ->
                when (entry) {
                    is LibraryEntry.Folder -> EntryRow("${entry.name}/", "Folder") { vm.openFolder(entry) }
                    is LibraryEntry.Book -> EntryRow(entry.title, l.progress.position(entry.uri.toString())?.let(::progressText)) {
                        vm.openBook(entry.uri, entry.title)
                        onBookChosen()
                    }
                }
            }
        }
    }
}

private fun LazyListScope.sectionHeader(title: String) {
    item(key = "header:$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun EntryRow(title: String, detail: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}

private fun progressText(position: SavedPosition): String = "${(position.fraction * 100).roundToInt()}% done"
