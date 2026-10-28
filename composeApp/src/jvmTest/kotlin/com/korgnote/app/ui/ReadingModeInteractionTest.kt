package com.korgnote.app.ui

import androidx.compose.ui.test.onChildren
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.link.OrgBacklink
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading mode's gestures (T24): a tap edits, unless it lands on something that has a tap of its
 * own; a long-press offers the ways of adding, moving and restarring a heading; a chevron folds a
 * heading's subtree.
 */
@OptIn(ExperimentalTestApi::class)
class ReadingModeInteractionTest {

    // Elements: 0 Project, 1 Step one, 2 Other
    private val text = """
        * Project
        - [ ] Buy milk
        ** Step one
        Step body.
        * Other
    """.trimIndent() + "\n"

    @Test
    fun aTapOnTextEditsThatElement() = runComposeUiTest {
        val edited = mutableListOf<Int>()
        setContent { OrgDocumentView(text = text, onEditElement = { edited += it }) }

        onNodeWithText("Step body.").performClick()

        assertEquals(listOf(1), edited)
    }

    @Test
    fun aTapOnACheckboxTogglesItAndDoesNotEdit() = runComposeUiTest {
        val edited = mutableListOf<Int>()
        val toggled = mutableListOf<Int>()
        setContent {
            OrgDocumentView(text = text, onEditElement = { edited += it }, onToggleCheckbox = { toggled += it })
        }

        onAllNodes(isToggleable()).onFirst().performClick()

        assertEquals(listOf(text.indexOf("- [ ]")), toggled)
        assertTrue(edited.isEmpty(), "the checkbox's tap is its own")
    }

    /** The keyword is an inner target, like a link: a tap offers the keywords and edits nothing. */
    @Test
    fun aTapOnATodoKeywordOffersTheKeywordsAndDoesNotEdit() = runComposeUiTest {
        val todoText = "* Intro\n* TODO Write the report\nBody.\n"
        val edited = mutableListOf<Int>()
        val picked = mutableListOf<Pair<Int, String?>>()
        setContent {
            OrgDocumentView(
                text = todoText,
                onEditElement = { edited += it },
                onSetTodo = { offset, keyword -> picked += offset to keyword },
                todoKeywords = OrgTodoConfig(todo = linkedSetOf("TODO", "NEXT", "WAITING"), done = linkedSetOf("DONE", "CANCELLED")),
            )
        }

        onNodeWithText("TODO Write the report", useUnmergedTree = true).onChildren().onFirst().performClick()

        assertTrue(picked.isEmpty(), "a tap opens the choice rather than making one")
        assertTrue(edited.isEmpty(), "the keyword's tap is its own")
        listOf("NEXT", "WAITING", "DONE", "CANCELLED", "No keyword").forEach { onNodeWithText(it).assertExists() }

        onNodeWithText("WAITING").performClick()

        assertEquals(listOf<Pair<Int, String?>>(todoText.indexOf("* TODO") to "WAITING"), picked)
        onNodeWithText("CANCELLED").assertDoesNotExist()
    }

    @Test
    fun noKeywordClearsIt() = runComposeUiTest {
        val todoText = "* TODO Write the report\n"
        val picked = mutableListOf<Pair<Int, String?>>()
        setContent { OrgDocumentView(text = todoText, onSetTodo = { offset, keyword -> picked += offset to keyword }) }

        onNodeWithText("TODO Write the report", useUnmergedTree = true).onChildren().onFirst().performClick()
        onNodeWithText("No keyword").performClick()

        assertEquals(listOf<Pair<Int, String?>>(0 to null), picked)
    }

    /** A capitalised first word is parsed as a keyword, but only declared keywords are tappable. */
    @Test
    fun anUndeclaredCapitalisedWordIsNotTappable() = runComposeUiTest {
        val edited = mutableListOf<Int>()
        val picked = mutableListOf<Int>()
        setContent {
            OrgDocumentView(text = "* API design\n", onEditElement = { edited += it }, onSetTodo = { offset, _ -> picked += offset })
        }

        onNodeWithText("API design", useUnmergedTree = true).onChildren().assertCountEquals(0)
        onNodeWithText("API design").performClick()

        assertTrue(picked.isEmpty())
        assertEquals(listOf(0), edited)
    }

    @Test
    fun aLongPressOffersBothWaysOfAddingAHeading() = runComposeUiTest {
        val below = mutableListOf<Int>()
        val sub = mutableListOf<Int>()
        val edited = mutableListOf<Int>()
        setContent {
            OrgDocumentView(
                text = text,
                onEditElement = { edited += it },
                onAddHeadingBelow = { below += it },
                onAddSubheading = { sub += it },
            )
        }

        onNodeWithText("Step body.").performTouchInput { longClick() }
        onNodeWithText("Add heading below").performClick()
        onNodeWithText("Step body.").performTouchInput { longClick() }
        onNodeWithText("Add sub-heading").performClick()

        assertEquals(listOf(1), below)
        assertEquals(listOf(1), sub)
        assertTrue(edited.isEmpty(), "a long-press opens the menu, not the editor")
    }

    @Test
    fun aLongPressOffersOnlyTheMovesThatApply() = runComposeUiTest {
        val moved = mutableListOf<Pair<Int, Boolean>>()
        val shifted = mutableListOf<Pair<Int, Int>>()
        setContent {
            OrgDocumentView(
                text = text,
                onMoveSection = { index, up -> moved += index to up },
                onShiftSection = { index, delta -> shifted += index to delta },
            )
        }

        // Project is the first level-1 heading: it can only go down, or deeper.
        onNodeWithText("Project").performTouchInput { longClick() }
        onNodeWithText("Move up").assertIsNotEnabled()
        onNodeWithText("Promote").assertIsNotEnabled()
        onNodeWithText("Move down").assertIsEnabled().performClick()
        onNodeWithText("Step body.").performTouchInput { longClick() }
        onNodeWithText("Promote").assertIsEnabled().performClick()

        assertEquals(listOf(0 to false), moved)
        assertEquals(listOf(1 to -1), shifted)
    }

    @Test
    fun collapsingAHeadingHidesItsBodyAndDescendants() = runComposeUiTest {
        var folded by mutableStateOf(emptySet<Int>())
        setContent {
            OrgDocumentView(
                modifier = Modifier.fillMaxSize(),
                text = text,
                foldedElements = folded,
                onToggleFold = { folded = if (it in folded) folded - it else folded + it },
            )
        }
        // Other has nothing under it, so it has nothing to fold.
        assertEquals(2, onAllNodesWithContentDescription("Collapse", substring = true).fetchSemanticsNodes().size)

        onNodeWithContentDescription("Collapse Project").performClick()

        assertEquals(setOf(0), folded)
        assertEquals(0, onAllNodes(isToggleable()).fetchSemanticsNodes().size, "the body is hidden")
        onNodeWithText("Step body.").assertDoesNotExist()
        onNodeWithContentDescription("Expand Project").performClick()
        onNodeWithText("Step body.").assertExists()
    }

    @Test
    fun deletingAHeadingIsConfirmedAndSaysWhatGoesWithIt() = runComposeUiTest {
        val deleted = mutableListOf<Int>()
        setContent { OrgDocumentView(text = text, onDeleteSection = { deleted += it }) }

        onNodeWithText("Project").performTouchInput { longClick() }
        onNodeWithText("Delete heading").performClick()
        onNodeWithText("with 1 sub-heading", substring = true).assertExists()
        assertTrue(deleted.isEmpty(), "nothing is deleted before it is confirmed")

        onNodeWithText("Delete").performClick()
        assertEquals(listOf(0), deleted)
    }

    @Test
    fun cancellingADeleteKeepsTheHeading() = runComposeUiTest {
        val deleted = mutableListOf<Int>()
        setContent { OrgDocumentView(text = text, onDeleteSection = { deleted += it }) }

        onNodeWithText("Step body.").performTouchInput { longClick() }
        onNodeWithText("Delete heading").performClick()
        onNodeWithText("Cancel").performClick()

        assertTrue(deleted.isEmpty())
    }

    /** A link is an inner target: a tap follows it, through the app's URI handler, and edits nothing. */
    @Test
    fun aTapOnANoteLinkFollowsItInsteadOfEditing() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val edited = mutableListOf<Int>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }
        setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                OrgDocumentView(text = "* Links\n[[id:plan-id][the plan]]\n", onEditElement = { edited += it })
            }
        }

        // The element row merges its text for accessibility, so the link's own clickable node — the
        // one a finger on the link hits — is found in the unmerged tree, under its paragraph.
        onNodeWithText("the plan", useUnmergedTree = true).onChildren().onFirst().performClick()

        assertEquals(listOf("${NOTE_LINK_URI_PREFIX}id:plan-id"), opened)
        assertTrue(edited.isEmpty(), "following a link is not editing")
    }

    @Test
    fun backlinksAreListedAfterTheNoteAndOpenWhenTapped() = runComposeUiTest {
        val backlink = OrgBacklink(path = "daily/today.org", offset = 12, context = "Work")
        val opened = mutableListOf<OrgBacklink>()
        setContent {
            OrgDocumentView(
                modifier = Modifier.fillMaxSize(),
                text = text,
                backlinks = listOf(backlink),
                onOpenBacklink = { opened += it },
            )
        }

        onNodeWithText("Linked from (1)").assertExists()
        onNodeWithText("Work").performClick()

        assertEquals(listOf(backlink), opened)
    }

    /** Beside the note's own keywords, and only there: it is not a heading's button. */
    @Test
    fun theDetailsButtonSitsBesideTheNotesKeywordsAndOpensTheForm() = runComposeUiTest {
        val noteText = "#+TITLE: Plans\n#+filetags: :work:\n\n* First\nBody.\n* Second\n"
        val edited = mutableListOf<Int>()
        var opened = 0
        setContent { OrgDocumentView(text = noteText, onEditElement = { edited += it }, onEditDetails = { opened++ }) }

        onAllNodesWithContentDescription("Note details").assertCountEquals(1)
        onNodeWithContentDescription("Note details").performClick()

        assertEquals(1, opened)
        assertTrue(edited.isEmpty(), "the button's tap is its own")
    }

    /** A note opening on a heading still has a way to give itself a title. */
    @Test
    fun aNoteWithNoKeywordsStillHasTheDetailsButton() = runComposeUiTest {
        var opened = 0
        setContent { OrgDocumentView(text = text, onEditDetails = { opened++ }) }

        onAllNodesWithContentDescription("Note details").assertCountEquals(1)
        onNodeWithContentDescription("Note details").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun theDetailsButtonStepsAsideWhileAnElementIsEdited() = runComposeUiTest {
        setContent { OrgDocumentView(text = "#+TITLE: Plans\n* First\n", editingElementIndex = 1, onEditDetails = {}) }

        onAllNodesWithContentDescription("Note details").assertCountEquals(0)
    }

    /** A table is edited as a grid: its tap is its own, like a checkbox's. */
    @Test
    fun aTapOnATableOpensItAsAGridAndDoesNotEdit() = runComposeUiTest {
        val tableText = "* Shopping\n| Item | Qty |\n| Milk | 2 |\n"
        val edited = mutableListOf<Int>()
        val tables = mutableListOf<Int>()
        setContent {
            OrgDocumentView(text = tableText, onEditElement = { edited += it }, onEditTable = { tables += it })
        }

        onNodeWithText("Milk").performClick()

        assertEquals(listOf(tableText.indexOf("| Item")), tables)
        assertTrue(edited.isEmpty(), "the table's tap is its own")
    }
}
