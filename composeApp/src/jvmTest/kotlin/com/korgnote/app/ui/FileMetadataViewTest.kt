package com.korgnote.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

/** What reading mode makes of the lines above a note's first heading that are not prose. */
@OptIn(ExperimentalTestApi::class)
class FileMetadataViewTest {

    private val text = """
        :PROPERTIES:
        :ID:       3f2a9c1e-7b4d-4e0a-9f61-2c8d5b7a0e14
        :END:
        #+TITLE: Garden
        #+TODO: TODO NEXT | DONE CANCELLED
        * NEXT Prune the roses
    """.trimIndent() + "\n"

    @Test
    fun theNotesOwnDrawerIsLabelledWithItsId() = runComposeUiTest {
        setContent { OrgDocumentView(text = text, onEditElement = {}) }

        onNodeWithText("3f2a9c1e-7b4d-4e0a-9f61-2c8d5b7a0e14").assertExists()
        onNodeWithText(":PROPERTIES:").assertDoesNotExist()
    }

    @Test
    fun aDrawerUnderAHeadingKeepsItsName() = runComposeUiTest {
        setContent {
            OrgDocumentView(text = "* Heading\n:PROPERTIES:\n:ID: abc\n:END:\n", onEditElement = {})
        }

        onNodeWithText(":PROPERTIES:").assertExists()
    }

    /** Org reads `#+SEQ_TODO:` as `#+TODO:`, and so do the headings: the chips and the heading agree. */
    @Test
    fun aSeqTodoLineDeclaresWhatItsHeadingsUse() = runComposeUiTest {
        setContent { OrgDocumentView(text = "#+SEQ_TODO: NEXT SHIPPED\n* SHIPPED Release\n", onEditElement = {}) }

        onNodeWithContentDescription("Keywords: NEXT, done: SHIPPED").assertExists()
    }

    @Test
    fun theTodoLineShowsItsKeywordsRatherThanItsSyntax() = runComposeUiTest {
        setContent { OrgDocumentView(text = text, onEditElement = {}) }

        onNodeWithText("#+TODO: TODO NEXT | DONE CANCELLED").assertDoesNotExist()
        onNodeWithContentDescription("Keywords: TODO, NEXT, done: DONE, CANCELLED").assertExists()
        onNodeWithText("CANCELLED").assertExists()
    }
}
