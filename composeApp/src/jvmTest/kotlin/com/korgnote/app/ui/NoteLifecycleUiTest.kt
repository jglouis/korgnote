package com.korgnote.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The affordances T20 adds: a note's long-press menu in the drawer, and the dialogs it opens. */
@OptIn(ExperimentalTestApi::class)
class NoteLifecycleUiTest {

    private val root = Folder(
        name = "Documents",
        path = "",
        children = listOf(File("inbox.org", "inbox.org"), File("journal.org", "journal.org")),
    )

    @Test
    fun aLongPressOnANoteOffersRenameAndDelete() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val renamed = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        setContent {
            FileTree(
                root = root,
                onFileSelected = { opened += it },
                onRenameFile = { renamed += it },
                onDeleteFile = { deleted += it },
            )
        }

        onNodeWithText("journal.org").performTouchInput { longClick() }
        onNodeWithText("Rename or move").performClick()
        onNodeWithText("inbox.org").performTouchInput { longClick() }
        onNodeWithText("Delete").performClick()

        assertEquals(listOf("journal.org"), renamed)
        assertEquals(listOf("inbox.org"), deleted)
        assertTrue(opened.isEmpty(), "a long-press does not open the note")
    }

    @Test
    fun aLongPressOffersToPinANoteOrUnpinAPinnedOne() = runComposeUiTest {
        val toggled = mutableListOf<String>()
        setContent {
            FileTree(
                root = root,
                onFileSelected = {},
                pinned = setOf("inbox.org"),
                onTogglePin = { toggled += it },
            )
        }

        onNodeWithText("journal.org").performTouchInput { longClick() }
        onNodeWithText("Pin").performClick()
        onNodeWithText("inbox.org").performTouchInput { longClick() }
        onNodeWithText("Unpin").performClick()

        assertEquals(listOf("journal.org", "inbox.org"), toggled)
    }

    @Test
    fun aPinnedNoteOpensOnATapAndUnpinsFromItsLongPress() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val unpinned = mutableListOf<String>()
        setContent {
            PinnedNotes(
                paths = listOf("agenda.org", "work/todo.org"),
                onOpen = { opened += it },
                onUnpin = { unpinned += it },
            )
        }

        onNodeWithText("agenda.org").performClick()
        onNodeWithText("todo.org").performTouchInput { longClick() }
        onNodeWithText("Unpin").performClick()

        assertEquals(listOf("agenda.org"), opened)
        assertEquals(listOf("work/todo.org"), unpinned)
    }

    @Test
    fun theNewNoteDialogShowsTheFileItWillMake() = runComposeUiTest {
        val created = mutableListOf<Pair<String, List<String>>>()
        setContent {
            NewNoteDialog(
                pathFor = { title -> title.takeIf { it.isNotBlank() }?.let { "inbox/${it.lowercase()}.org" } },
                onCreate = { title, tags -> created += title to tags },
                onDismiss = {},
                knownTags = listOf("work", "ecam", "home"),
            )
        }
        onNodeWithText("Create").assertIsNotEnabled()

        onNodeWithText("Title").performTextInput("Idea")
        onNodeWithText("inbox/idea.org").assertExists()
        onNodeWithText("Tags").performTextInput("home e")
        onNodeWithText("work").assertDoesNotExist()
        onNodeWithText("ecam").performClick()
        onNodeWithText("Create").performClick()

        assertEquals(listOf("Idea" to listOf("home", "ecam")), created)
    }

    @Test
    fun theDeleteDialogWarnsAboutUnsyncedChanges() = runComposeUiTest {
        setContent { DeleteNoteDialog("inbox.org", hasUnsyncedChanges = true, onDelete = {}, onDismiss = {}) }

        onNodeWithText("have not reached the server", substring = true).assertExists()
        onNodeWithText("Delete anyway").assertExists()
    }
}
