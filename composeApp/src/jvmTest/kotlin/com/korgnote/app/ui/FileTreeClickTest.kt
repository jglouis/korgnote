package com.korgnote.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Clicking a row in the drawer must open that file. */
@OptIn(ExperimentalTestApi::class)
class FileTreeClickTest {

    private val tree = Folder(
        name = "Documents",
        path = "",
        children = listOf(
            Folder("journal", "journal", listOf(File("2025-01-01.org", "journal/2025-01-01.org"))),
            File("inbox.org", "inbox.org"),
        ),
    )

    @Test
    fun clickingAFileReportsItsPath() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent { FileTree(root = tree, onFileSelected = { opened += it }) }

        onNodeWithText("inbox.org").performClick()

        assertEquals(listOf("inbox.org"), opened)
    }

    @Test
    fun clickingANestedFileReportsItsFullPath() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent { FileTree(root = tree, onFileSelected = { opened += it }) }

        onNodeWithText("2025-01-01.org").performClick()

        assertEquals(listOf("journal/2025-01-01.org"), opened)
    }

    @Test
    fun clickingAFolderOpensAndClosesItWithoutSelectingAnything() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent { FileTree(root = tree, onFileSelected = { opened += it }) }

        onNodeWithText("journal").performClick()
        onNodeWithText("2025-01-01.org").assertDoesNotExist()

        onNodeWithText("journal").performClick()
        onNodeWithText("2025-01-01.org").performClick()

        assertEquals(listOf("journal/2025-01-01.org"), opened)
    }
}
