package com.korgnote.app.viewmodel

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
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
import com.korgnote.common.replica.SyncEngine

/**
 * T8's acceptance at the app level: the app opens, renders and edits notes with the server
 * unreachable, and the edit is still there afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineReplicaTest {

    private val path = "notes.org"
    private val serverText = "* TODO From the server\n"

    /** A repository that can be switched offline, the way a train tunnel does it. */
    private lateinit var repository: FakeSyncApi
    private lateinit var storage: InMemoryReplicaStorage

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeSyncApi(mutableMapOf(path to serverText))
        storage = InMemoryReplicaStorage()
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): NotesViewModel {
        val store = DocumentStore(storage)
        return NotesViewModel(SyncEngine(store, repository), store)
    }

    @Test
    fun anOnlineLoadFillsTheReplica() = runTest {
        viewModel().server.load()
        assertEquals(serverText, DocumentStore(storage).read(path))
    }

    /** The headline: with the server down, the app still opens its notes. */
    @Test
    fun theAppOpensItsNotesWithTheServerUnreachable() = runTest {
        viewModel().server.load()
        repository.online = false

        val offline = viewModel()
        offline.server.load()

        assertEquals(serverText, offline.note.content)
        assertTrue(offline.server.unreachable != null, "the user should be told it is stale")
    }

    @Test
    fun anEditMadeOfflineIsKeptAndStaysDirty() = runTest {
        val online = viewModel()
        online.server.load()
        repository.online = false

        online.note.content = "* TODO Edited on a train\n"
        online.saveCurrentFile()

        val store = DocumentStore(storage)
        assertEquals("* TODO Edited on a train\n", store.read(path))
        assertTrue(store.isDirty(path), "an unpushed edit must stay dirty")
        assertEquals(emptyList(), repository.saves, "nothing reached the server")
    }

    /** Restart: a new view model over the same replica still has the offline edit. */
    @Test
    fun anOfflineEditSurvivesARestart() = runTest {
        val before = viewModel()
        before.server.load()
        repository.online = false
        before.note.content = "* TODO Written offline\n"
        before.saveCurrentFile()

        val afterRestart = viewModel()
        afterRestart.server.load()

        assertEquals("* TODO Written offline\n", afterRestart.note.content)
    }

    /** Coming back online must not silently discard the work done while away. */
    @Test
    fun reconnectingDoesNotOverwriteTheOfflineEdit() = runTest {
        val vm = viewModel()
        vm.server.load()
        repository.online = false
        vm.note.content = "* TODO Written offline\n"
        vm.saveCurrentFile()

        repository.online = true
        vm.server.load()

        assertEquals("* TODO Written offline\n", vm.note.content)
    }
}
