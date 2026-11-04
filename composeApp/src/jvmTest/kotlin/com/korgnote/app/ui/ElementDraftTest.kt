package com.korgnote.app.ui

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

/** A keystroke is the editing element's business: what holds the document does not hear of it. */
@OptIn(ExperimentalTestApi::class)
class ElementDraftTest {
    private val text = "* Alpha\n* Beta\n"

    @Test
    fun aKeystrokeReachesTheFieldWithoutComposingWhatHoldsTheDocument() = runComposeUiTest {
        var draft by mutableStateOf(TextFieldValue("* Beta\n"))
        var compositions = 0
        setContent {
            SideEffect { compositions++ }
            OrgDocumentView(
                text = text,
                editingElementIndex = 1,
                elementDraft = { draft },
                onElementDraftChange = { draft = it },
            )
        }
        waitForIdle()
        val before = compositions

        draft = TextFieldValue("* Betamax\n")
        waitForIdle()

        onNodeWithText("* Betamax", substring = true).assertExists()
        assertEquals(before, compositions)
    }

    @Test
    fun whatIsTypedComesBackThroughTheChange() = runComposeUiTest {
        var draft by mutableStateOf(TextFieldValue("* Beta\n"))
        setContent {
            OrgDocumentView(
                text = text,
                editingElementIndex = 1,
                elementDraft = { draft },
                onElementDraftChange = { draft = it },
            )
        }

        onNodeWithText("* Beta", substring = true).performTextInput("X")
        waitForIdle()

        assertEquals("X* Beta\n", draft.text)
    }
}
