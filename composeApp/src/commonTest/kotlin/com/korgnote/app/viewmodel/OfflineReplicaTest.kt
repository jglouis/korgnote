package com.korgnote.app.viewmodel

import com.korgnote.app.data.OrgRepository
import com.korgnote.common.fs.Folder
import com.korgnote.common.fs.TreeUtils
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

/**
 * T8's acceptance at the app level: the app opens, renders and edits notes with the server
 * unreachable, and the edit is still there afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineReplicaTest {

    private val path = "notes.org"
    private val serverText = "* TODO From the server\n"

    /** A repository that can be switched offline, the way a train tunnel does it. */
    private class FlakyRepository(private val files: MutableMap<String, String>) : OrgRepository {
        var online = true
        val saves = mutableListOf<Pair<String, String>>()

        private fun requireOnline() {
            if (!online) throw RuntimeException("Connection refused")
        }

        override suspend fun fetchAllContents(): Map<String, String> {
            requireOnline()
            return files.toMap()
        }

        override suspend fun fetchTree(): Folder {
            requireOnline()
            return TreeUtils.buildFromPaths(files.keys.sorted(), rootName = "Org")
        }

        override suspend fun fetchRawContent(path: String): String {
            requireOnline()
            return files.getValue(path)
        }

        override suspend fun saveContent(path: String, content: String) {
            requireOnline()
            files[path] = content
            saves.add(path to content)
        }
    }

    private lateinit var repository: FlakyRepository
    private lateinit var storage: InMemoryReplicaStorage

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FlakyRepository(mutableMapOf(path to serverText))
        storage = InMemoryReplicaStorage()
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = FileTreeViewModel(repository, DocumentStore(storage))

    @Test
    fun anOnlineLoadFillsTheReplica() = runTest {
        viewModel().loadFromServer()
        assertEquals(serverText, DocumentStore(storage).read(path))
    }

    /** The headline: with the server down, the app still opens its notes. */
    @Test
    fun theAppOpensItsNotesWithTheServerUnreachable() = runTest {
        viewModel().loadFromServer()
        repository.online = false

        val offline = viewModel()
        offline.loadFromServer()

        assertEquals(serverText, offline.selectedFileContent)
        assertTrue(offline.lastErrorMessage.isNotBlank(), "the user should be told it is stale")
    }

    @Test
    fun anEditMadeOfflineIsKeptAndStaysDirty() = runTest {
        val online = viewModel()
        online.loadFromServer()
        repository.online = false

        online.onContentChanged("* TODO Edited on a train\n")
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
        before.loadFromServer()
        repository.online = false
        before.onContentChanged("* TODO Written offline\n")
        before.saveCurrentFile()

        val afterRestart = viewModel()
        afterRestart.loadFromServer()

        assertEquals("* TODO Written offline\n", afterRestart.selectedFileContent)
    }

    /** Coming back online must not silently discard the work done while away. */
    @Test
    fun reconnectingDoesNotOverwriteTheOfflineEdit() = runTest {
        val vm = viewModel()
        vm.loadFromServer()
        repository.online = false
        vm.onContentChanged("* TODO Written offline\n")
        vm.saveCurrentFile()

        repository.online = true
        vm.loadFromServer()

        assertEquals("* TODO Written offline\n", vm.selectedFileContent)
    }
}
