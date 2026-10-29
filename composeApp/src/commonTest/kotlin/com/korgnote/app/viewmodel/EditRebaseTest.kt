package com.korgnote.app.viewmodel

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An element being edited while a sync brings in an edit of the same note made elsewhere. Found on
 * a phone: a heading being added could not be saved at all once Emacs had touched the note, and
 * discarding it showed the note as it was before Emacs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditRebaseTest {

    private val path = "notes.org"
    private val original = "* TODO First\nbody one\n* TODO Second\nbody two\n"

    private lateinit var api: FakeSyncApi
    private lateinit var vm: NotesViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        api = FakeSyncApi(mutableMapOf(path to original))
        val store = DocumentStore(InMemoryReplicaStorage())
        vm = NotesViewModel(SyncEngine(store, api) { "20261029-120000" }, store)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun type(text: String) = vm.editor.onDraftChanged(TextFieldValue(text, TextRange(text.length)))

    /** An edit made in Emacs, arriving while the phone's element is open. */
    private suspend fun editedElsewhere(text: String) {
        api.files[path] = text
        vm.server.load()
    }

    @Test
    fun aHeadingAddedWhileTheNoteChangedElsewhereIsSavedWithBoth() = runTest {
        vm.server.load()
        vm.editor.addAtEnd()
        type("* Third, from the phone")
        editedElsewhere(original.replace("body one", "body one, from Emacs"))

        vm.editor.commit()

        assertNull(vm.editor.editingIndex)
        assertTrue(vm.note.content.contains("from Emacs"), vm.note.content)
        assertTrue(vm.note.content.contains("* Third, from the phone"), vm.note.content)
        assertTrue(api.files[path]!!.contains("from Emacs") && api.files[path]!!.contains("Third"), "the server has both")
    }

    /** What the phone hit: a merge line by line called this a conflict, the two changes touching. */
    @Test
    fun aHeadingAddedAtTheEndSurvivesAnEditOfTheLastLine() = runTest {
        vm.server.load()
        vm.editor.addAtEnd()
        type("* Third, from the phone")
        editedElsewhere(original.replace("body two", "body two, from Emacs"))

        vm.editor.commit()

        assertEquals(original.replace("body two", "body two, from Emacs") + "* Third, from the phone\n", vm.note.content)
    }

    @Test
    fun aHeadingAddedInTheMiddleGoesBeforeTheHeadingThatFollowedIt() = runTest {
        vm.server.load()
        vm.editor.addAfter(0)
        type("* Between, from the phone")
        editedElsewhere(original.replace("body one", "body one, from Emacs"))

        vm.editor.commit()

        assertEquals(
            "* TODO First\nbody one, from Emacs\n* Between, from the phone\n* TODO Second\nbody two\n",
            vm.note.content,
        )
    }

    @Test
    fun theNoteStaysStillUnderAnOpenElement() = runTest {
        vm.server.load()
        vm.editor.addAtEnd()
        type("* Third")
        val onScreen = vm.note.content

        editedElsewhere(original.replace("body one", "body one, from Emacs"))

        assertEquals(onScreen, vm.note.content)
        assertEquals(2, vm.editor.editingIndex)
    }

    @Test
    fun discardingAHeadingAddedWhileTheNoteChangedShowsTheNoteAsItIsNow() = runTest {
        vm.server.load()
        vm.editor.addAtEnd()
        type("* Third, from the phone")
        val emacs = original.replace("body one", "body one, from Emacs")
        editedElsewhere(emacs)

        vm.editor.cancel()

        assertEquals(emacs, vm.note.content)
    }

    @Test
    fun discardingAHeadingAddedRemovesItWhenNothingElseChanged() = runTest {
        vm.server.load()
        vm.editor.addAtEnd()
        type("* Third")

        vm.editor.cancel()

        assertEquals(original, vm.note.content)
    }

    @Test
    fun anEditSurvivesAHeadingAddedAboveItElsewhere() = runTest {
        vm.server.load()
        vm.editor.beginEdit(1)
        type("* DONE Second\nbody two\n")
        editedElsewhere("* From Emacs\n$original")

        vm.editor.commit()

        assertEquals("* From Emacs\n* TODO First\nbody one\n* DONE Second\nbody two\n", vm.note.content)
    }

    @Test
    fun anEditOfALineChangedElsewhereIsStillRefused() = runTest {
        vm.server.load()
        vm.editor.beginEdit(0)
        type("* DONE First\nbody one\n")
        editedElsewhere(original.replace("TODO First", "WAITING First"))

        vm.editor.commit()

        assertEquals(0, vm.editor.editingIndex)
        assertTrue(vm.lastErrorMessage.contains("also changed elsewhere"), vm.lastErrorMessage)
    }
}
