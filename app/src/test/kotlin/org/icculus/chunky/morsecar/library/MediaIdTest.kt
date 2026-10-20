package org.icculus.chunky.morsecar.library

import org.icculus.chunky.morsecar.settings.SpeedSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaIdTest {
    @Test
    fun roundTrips() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ABooks/document/primary%3ABooks%2Fa.txt"
        val ids = listOf(
            MediaId.Root, MediaId.Recent, MediaId.Folders, MediaId.Info, MediaId.Folder(uri), MediaId.Book(uri),
            MediaId.Speed, MediaId.Setting(SpeedSetting.TONE), MediaId.SetValue(SpeedSetting.CHARACTER, 17.5),
            MediaId.SetValue(SpeedSetting.WORD_GAP, null), MediaId.Preset("Farnsworth 20/10"),
        )
        for (id in ids) {
            assertEquals(id, MediaId.parse(id.value))
        }
    }

    @Test
    fun rejectsUnknownOrEmpty() {
        assertNull(MediaId.parse("nope"))
        assertNull(MediaId.parse("book:"))
        assertNull(MediaId.parse("folder:"))
        assertNull(MediaId.parse("setting:volume"))
        assertNull(MediaId.parse("set:letters:fast"))
        assertNull(MediaId.parse("set:tone:match"))  // only gaps can match the letter speed
    }
}
