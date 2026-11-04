package com.korgnote.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.TextUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A list item in reading mode is drawn from what the parser made of it. Printing it back and reading
 * that again would turn an indented `* item`, a list item to the parser, into a heading.
 */
@OptIn(ExperimentalTestApi::class)
class ReadingListItemTest {

    /** The sizes the text of the node showing [shown] is drawn with; a heading is what has any. */
    private fun ComposeUiTest.fontSizesOf(shown: String): List<TextUnit> =
        onNodeWithText(shown).fetchSemanticsNode().config[SemanticsProperties.Text]
            .flatMap { text -> text.spanStyles.map { it.item.fontSize } }
            .filter { it != TextUnit.Unspecified }

    @Test
    fun anIndentedStarItemKeepsItsBulletAndIsNoHeading() = runComposeUiTest {
        setContent { OrgDocumentView(text = "- a\n  * item\n") }

        assertTrue(fontSizesOf("* item").isEmpty(), "drawn with a heading's size")
    }

    @Test
    fun anIndentedStarDescriptionItemIsNoHeading() = runComposeUiTest {
        setContent { OrgDocumentView(text = "- a\n  * term :: words\n") }

        assertTrue(fontSizesOf("* term :: words").isEmpty(), "drawn with a heading's size")
    }

    @Test
    fun aDashItemReadsAsItDidBefore() = runComposeUiTest {
        setContent { OrgDocumentView(text = "- *bold* word\n- term :: words\n") }

        onAllNodesWithText("- bold word").assertCountEquals(1)
        onAllNodesWithText("- term :: words").assertCountEquals(1)
    }

    /** Neither the list nor the keyword may go missing, indented or not. */
    @Test
    fun anIndentedKeywordUnderAnItemIsStillShownInTheList() = runComposeUiTest {
        setContent {
            OrgDocumentView(text = "- Run this\n  #+RESULTS:\n  : 3\n- [ ] Then\n  #+CAPTION: The words\n  | a |\n- Last\n")
        }

        onAllNodesWithText("- Run this").assertCountEquals(1)
        onAllNodesWithText("#+RESULTS:").assertCountEquals(1)
        onAllNodesWithText("The words").assertCountEquals(1)
        onAllNodesWithText("- Last").assertCountEquals(1)
    }

    @Test
    fun aKeywordIndentedOnlyAsFarAsTheBulletIsStillShown() = runComposeUiTest {
        setContent { OrgDocumentView(text = "  - a\n  #+RESULTS:\n  - b\n") }

        onAllNodesWithText("#+RESULTS:", substring = true).assertCountEquals(1)
        onAllNodesWithText("- a", substring = true).assertCountEquals(1)
        onAllNodesWithText("- b", substring = true).assertCountEquals(1)
    }
}
