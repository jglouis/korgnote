package com.korgnote.app.viewmodel

import com.korgnote.common.org.OrgPriority
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Setting a heading's priority from the open note, as the cookie's menu does. */
@OptIn(ExperimentalCoroutinesApi::class)
class PriorityTest {

    private val path = "tasks.org"
    private val text = "* TODO Write the report\n* TODO [#B] Call Sam\n"
    private lateinit var repository: FakeSyncApi
    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeSyncApi(mutableMapOf(path to text))
        val store = DocumentStore(InMemoryReplicaStorage())
        viewModel = NotesViewModel(SyncEngine(store, repository), store, background = UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aPriorityIsSetSavedAndPushed() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(path)

        viewModel.changes.setPriorityAt(0, OrgPriority.of("A"))
        // Where the second heading is now: the first one's cookie moved it.
        viewModel.changes.setPriorityAt(viewModel.note.content.indexOf("* TODO [#B]"), null)

        // The push is launched in this scope; let it run before looking at what the server has.
        advanceUntilIdle()
        assertEquals("* TODO [#A] Write the report\n* TODO Call Sam\n", viewModel.note.content)
        assertEquals(viewModel.note.content, repository.files[path])
    }
}
