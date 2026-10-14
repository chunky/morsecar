package org.icculus.chunky.morsecar.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaIdTest {
    @Test
    fun roundTrips() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ABooks/document/primary%3ABooks%2Fa.txt"
        for (id in listOf(MediaId.Root, MediaId.Recent, MediaId.Folders, MediaId.Info, MediaId.Folder(uri), MediaId.Book(uri))) {
            assertEquals(id, MediaId.parse(id.value))
        }
    }

    @Test
    fun rejectsUnknownOrEmpty() {
        assertNull(MediaId.parse("nope"))
        assertNull(MediaId.parse("book:"))
        assertNull(MediaId.parse("folder:"))
    }
}
