package com.korgnote.app.viewmodel

import androidx.compose.ui.text.input.TextFieldValue
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A keyword whose `@` asks for a note: the change waits for it, then logs it. */
@OptIn(ExperimentalCoroutinesApi::class)
class StateNoteTest {

    private val path = "tasks.org"
    private val header = "#+TODO: TODO(t) DOING(p!) WAIT(w@/!) | DONE(d!) CANCELED(c@/!)\n"
    private val text = header + "* TODO Call the plumber\n"
    private lateinit var repository: FakeSyncApi
    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * The view model on this test's scheduler: a keyword change refreshes the agenda and the rest
     * after a pause, which only a scheduler the test runs can let pass.
     */
    private suspend fun TestScope.open() {
        repository = FakeSyncApi(mutableMapOf(path to text))
        val store = DocumentStore(InMemoryReplicaStorage())
        viewModel = NotesViewModel(
            SyncEngine(store, repository),
            store,
            now = { LocalDateTime(2026, 11, 1, 10, 30) },
            background = UnconfinedTestDispatcher(testScheduler),
        )
        viewModel.server.load()
        viewModel.onFileSelected(path)
    }

    private val heading get() = viewModel.note.content.indexOf("* ")

    @Test
    fun aNoteIsAskedForAndLoggedUnderTheStateLine() = runTest {
        open()
        viewModel.setTodoAt(heading, "WAIT", this)

        val pending = assertNotNull(viewModel.pendingStateNote, "the change waits for its note")
        assertEquals("WAIT", pending.keyword)
        assertEquals(text, viewModel.note.content, "nothing changed yet")

        viewModel.onStateNoteChanged(TextFieldValue("Waiting for his quote."))
        viewModel.saveStateNote(this)
        advanceUntilIdle()

        assertNull(viewModel.pendingStateNote)
        val expected = header + "* WAIT Call the plumber\n" +
            "- State \"WAIT\"       from \"TODO\"       [2026-11-01 Sun 10:30] \\\\\n" +
            "  Waiting for his quote.\n"
        assertEquals(expected, viewModel.note.content)
        assertEquals(expected, repository.files[path])
    }

    @Test
    fun cancellingLeavesTheKeywordAsItWas() = runTest {
        open()
        viewModel.setTodoAt(heading, "CANCELED", this)
        viewModel.onStateNoteChanged(TextFieldValue("Not needed."))
        viewModel.cancelStateNote()
        advanceUntilIdle()

        assertNull(viewModel.pendingStateNote)
        assertEquals(text, viewModel.note.content)
    }

    @Test
    fun aTimeStampNeedsNoNote() = runTest {
        open()
        viewModel.setTodoAt(heading, "DOING", this)

        assertNull(viewModel.pendingStateNote)
        assertEquals(
            header + "* DOING Call the plumber\n- State \"DOING\"      from \"TODO\"       [2026-11-01 Sun 10:30]\n",
            viewModel.note.content,
        )
    }
}
