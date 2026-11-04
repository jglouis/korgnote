package com.korgnote.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The confirmation is asked outside the list, so it has to be told what became of its heading. */
@OptIn(ExperimentalTestApi::class)
class DeleteConfirmationTest {
    private val alpha = "* Alpha\n"
    private val beta = "* Beta\n"
    private var shown by mutableStateOf(alpha + beta)

    /** The heading it names is the one it was asked about, or a sync could have it delete another. */
    @Test
    fun itClosesWhenAHeadingAddedAboveMovesAnotherIntoItsPlace() = runComposeUiTest {
        val deleted = mutableListOf<Int>()
        setContent { OrgDocumentView(text = shown, actions = ElementActions(onDeleteSection = { deleted += it })) }
        onNodeWithText("Beta", substring = true).performTouchInput { longClick() }
        onNodeWithText("Delete heading").performClick()

        shown = "* New\n" + alpha + beta
        waitForIdle()

        onNodeWithText("Delete").assertDoesNotExist()
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun itStaysOpenForAnEditThatLeavesItsHeadingWhereItIs() = runComposeUiTest {
        val deleted = mutableListOf<Int>()
        setContent { OrgDocumentView(text = shown, actions = ElementActions(onDeleteSection = { deleted += it })) }
        onNodeWithText("Beta", substring = true).performTouchInput { longClick() }
        onNodeWithText("Delete heading").performClick()

        shown = alpha + "A line under it.\n" + beta
        waitForIdle()
        onNodeWithText("Delete").performClick()

        assertEquals(listOf(1), deleted)
    }

    @Test
    fun itClosesWhenItsHeadingIsGoneAndDoesNotComeBackWithIt() = runComposeUiTest {
        setContent { OrgDocumentView(text = shown, actions = ElementActions(onDeleteSection = {})) }
        onNodeWithText("Beta", substring = true).performTouchInput { longClick() }
        onNodeWithText("Delete heading").performClick()
        onNodeWithText("Delete").assertExists()

        shown = alpha
        waitForIdle()
        onNodeWithText("Delete").assertDoesNotExist()

        shown = alpha + beta
        waitForIdle()
        onNodeWithText("Delete").assertDoesNotExist()
    }
}
