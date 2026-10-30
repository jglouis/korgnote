package com.korgnote.app.viewmodel

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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A save brings up to date the `[n/m]` cookies over what it changed, and no others: the rest of the
 * note stays as it was, byte for byte, as an element edit promises. A cookie elsewhere may count
 * differently on purpose — Emacs with other settings — and rewriting it is noise in every sync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsOnSaveTest {

    private val path = "projects.org"

    private val text = """
        * Garden [0/1]
        ** TODO Dig
        * Kitchen [5/9]
        ** DONE Paint
    """.trimIndent() + "\n"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun TestScope.openedNote(): NotesViewModel {
        val store = DocumentStore(InMemoryReplicaStorage())
        val viewModel = NotesViewModel(
            SyncEngine(store, FakeSyncApi(mutableMapOf(path to text))),
            store,
            background = UnconfinedTestDispatcher(testScheduler),
        )
        viewModel.server.load()
        viewModel.onFileSelected(path)
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun aSaveUpdatesTheCookieOverTheEditAndNoOther() = runTest {
        val viewModel = openedNote()

        viewModel.note.content = text.replace("** TODO Dig", "** DONE Dig")
        viewModel.saveCurrentFile()

        assertEquals(
            text.replace("** TODO Dig", "** DONE Dig").replace("[0/1]", "[1/1]"),
            viewModel.note.content,
        )
    }

    @Test
    fun aKeywordChangedFromTheMenuUpdatesTheCookieOverItAndNoOther() = runTest {
        val viewModel = openedNote()

        viewModel.setTodoAt(text.indexOf("** TODO Dig"), "DONE")

        val headings = viewModel.note.content.lines().filter { it.startsWith("* ") }
        assertEquals(listOf("* Garden [1/1]", "* Kitchen [5/9]"), headings)
    }
}
