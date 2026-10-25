package com.korgnote.app.viewmodel

import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.files.FileOpenResult
import com.korgnote.app.files.FileOpener
import com.korgnote.app.files.covering
import com.korgnote.app.files.decodeFileLinkMappings
import com.korgnote.app.files.encodeFileLinkMappings
import com.korgnote.app.ui.fileLinkMappingsError
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Opening linked files outside the notes, through the folder mappings set on a device. */
class FileLinksTest {

    private val phoneSync = FileLinkMapping("~/Sync", "content://tree/primary%3ASync")

    /** Remembers what it was asked to open, and answers [result]. */
    private class RecordingOpener(var result: FileOpenResult = FileOpenResult.Opened) : FileOpener {
        val opened = mutableListOf<String>()
        override suspend fun open(folder: String, below: List<String>): FileOpenResult {
            opened += "$folder | ${below.joinToString("/")}"
            return result
        }
        override suspend fun openAsWritten(path: String): FileOpenResult {
            opened += "as written | $path"
            return result
        }
    }

    private fun links(opener: FileOpener, vararg mappings: FileLinkMapping) =
        FileLinks(TestScope(), { mappings.toList() }, opener, showMessage = {})

    @Test
    fun aMappedFolderStandsForTheOneTheLinkNames() = runTest {
        val opener = RecordingOpener()
        assertEquals("", links(opener, phoneSync).openNow("file:~/Sync/bills/invoice.pdf::3"))
        assertEquals(listOf("content://tree/primary%3ASync | bills/invoice.pdf"), opener.opened)
    }

    @Test
    fun theLongestMappingWins() {
        val photos = FileLinkMapping("~/Sync/photos/", "/sdcard/DCIM")
        assertEquals(photos to listOf("a.jpg"), listOf(phoneSync, photos).covering("~/Sync/photos/a.jpg"))
        assertEquals(phoneSync to listOf("a.pdf"), listOf(phoneSync, photos).covering("~/Sync/./a.pdf"))
    }

    @Test
    fun aMappingCoversWholeFoldersOnly() {
        assertNull(listOf(phoneSync).covering("~/Syncthing/a.pdf"))
        assertNull(listOf(phoneSync).covering("/home/me/Sync/a.pdf"))
        // Climbing out of the folder is not inside it.
        assertNull(listOf(phoneSync).covering("~/Sync/../secret.pdf"))
        assertNotNull(listOf(FileLinkMapping("/home/me", "/x")).covering("/home/me/a.pdf"))
    }

    @Test
    fun anUnmappedPathIsOpenedAsWrittenWhereThatMeansSomething() = runTest {
        val opener = RecordingOpener()
        assertEquals("", links(opener, phoneSync).openNow("~/Documents/a.pdf"))
        assertEquals(listOf("as written | ~/Documents/a.pdf"), opener.opened)
    }

    @Test
    fun eachFailureSaysWhatToDo() = runTest {
        val opener = RecordingOpener(FileOpenResult.NeedsMapping)
        val links = links(opener, phoneSync)
        assertEquals(
            "No folder on this device is set for ~/Documents/a.pdf. Choose one in Settings, under File links.",
            links.openNow("file:~/Documents/a.pdf"),
        )
        opener.result = FileOpenResult.NotFound
        assertEquals(
            "~/Sync/a.pdf is not on this device, in the folder set for ~/Sync.",
            links.openNow("file:~/Sync/a.pdf"),
        )
        opener.result = FileOpenResult.NoApp
        assertEquals("No app on this device opens a.xyz.", links.openNow("file:~/Sync/a.xyz"))
        opener.result = FileOpenResult.Unsupported
        assertEquals("Files cannot be opened from links on this device.", links.openNow("file:~/Sync/a.pdf"))
    }

    @Test
    fun mappingsAreStoredAndReadBack() {
        val mappings = listOf(phoneSync, FileLinkMapping("/home/me/Pictures", "/sdcard/Pictures"))
        assertEquals(mappings, decodeFileLinkMappings(encodeFileLinkMappings(mappings)))
        assertEquals(emptyList(), decodeFileLinkMappings(null))
    }

    @Test
    fun settingsRefuseAHalfFilledOrRelativeMapping() {
        assertNull(fileLinkMappingsError(listOf(phoneSync, FileLinkMapping(" ", ""))))
        assertNotNull(fileLinkMappingsError(listOf(FileLinkMapping("~/Sync", ""))))
        assertNotNull(fileLinkMappingsError(listOf(FileLinkMapping("Sync", "/sdcard/Sync"))))
    }
}
