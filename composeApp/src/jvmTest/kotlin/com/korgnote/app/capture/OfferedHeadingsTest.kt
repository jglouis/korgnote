package com.korgnote.app.capture

import com.korgnote.app.data.NoteTemplateSettings
import com.korgnote.app.viewmodel.FakeSyncApi
import com.korgnote.app.viewmodel.NotesViewModel
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The headings left beside the captures, for what captures without being able to read the notes:
 * iOS's share extension. Coming back to the app before the notes are read must not tell it there are
 * none, nor must an app whose queue nothing else reads work them out for nobody.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfferedHeadingsTest {
    private class Folder(override val readByOthers: Boolean) : CaptureQueueFolder {
        val files = mutableMapOf<String, String>()
        var writes = 0
        override suspend fun names() = files.keys.toList()
        override suspend fun read(name: String) = files[name]
        override suspend fun write(name: String, text: String) {
            writes++
            files[name] = text
        }
        override suspend fun delete(name: String) {
            files.remove(name)
        }
    }

    private fun viewModelOver(folder: Folder): NotesViewModel {
        val store = DocumentStore(InMemoryReplicaStorage())
        return NotesViewModel(
            SyncEngine(store, FakeSyncApi(mutableMapOf("a.org" to "* Heading\n"))),
            store,
            captures = CaptureFiler(CaptureQueue(folder), { "" }, { NoteTemplateSettings() }),
            background = UnconfinedTestDispatcher(),
        )
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun nothingIsOfferedBeforeTheNotesAreRead() = runTest {
        val folder = Folder(readByOthers = true)
        val viewModel = viewModelOver(folder)
        folder.write("headings.json", "[\"what the app offered last time\"]")
        folder.writes = 0

        viewModel.fileCaptures()

        assertEquals(0, folder.writes)
    }

    @Test
    fun theHeadingsAreOfferedOnceTheyAre() = runTest {
        val folder = Folder(readByOthers = true)
        val viewModel = viewModelOver(folder)
        viewModel.server.load()

        viewModel.fileCaptures()

        assertTrue(folder.files.values.single().contains("Heading"), folder.files.toString())
    }
}
