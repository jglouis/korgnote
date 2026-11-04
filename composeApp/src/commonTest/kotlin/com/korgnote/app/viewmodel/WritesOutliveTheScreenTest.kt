package com.korgnote.app.viewmodel

import androidx.compose.ui.text.input.TextFieldValue
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Turning the phone recreates the activity, which cancels whatever the screen launched. A change the
 * screen has already shown must reach the replica all the same: the view model and its text survive
 * the turn, and the next sync would otherwise show the note without it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WritesOutliveTheScreenTest {
    private val path = "notes.org"
    private val text = "* TODO First\n- [ ] one\n"

    private lateinit var storage: StallingReplicaStorage
    private lateinit var store: DocumentStore
    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        storage = StallingReplicaStorage()
        store = DocumentStore(storage)
        viewModel = NotesViewModel(
            SyncEngine(store, FakeSyncApi(mutableMapOf(path to text))),
            store,
            background = UnconfinedTestDispatcher(main.scheduler),
        )
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** What `rememberCoroutineScope` gives: on the main thread, and gone when the activity is. */
    private fun composition() = CoroutineScope(Dispatchers.Main + Job())

    private suspend fun TestScope.opened() {
        viewModel.server.load()
        advanceUntilIdle()
    }

    @Test
    fun aTickSurvivesTheScreenGoingAwayMidWrite() = runTest {
        opened()
        val composition = composition()
        val disk = storage.stallNextWrite(path)

        composition.launch { viewModel.changes.toggleCheckboxAt(text.indexOf("- [ ]")) }
        runCurrent()
        composition.cancel()
        disk.complete(Unit)
        advanceUntilIdle()

        assertEquals(text.replace("[ ]", "[X]"), store.read(path))
    }

    @Test
    fun aSavedElementSurvivesTheScreenGoingAwayMidWrite() = runTest {
        opened()
        viewModel.editor.beginEdit(0)
        viewModel.editor.onDraftChanged(TextFieldValue("* DONE First\n- [ ] one\n"))
        val composition = composition()
        val disk = storage.stallNextRead(path)

        composition.launch { viewModel.editor.commit() }
        runCurrent()
        composition.cancel()
        disk.complete(Unit)
        advanceUntilIdle()

        assertEquals("* DONE First\n- [ ] one\n", store.read(path))
    }
}
