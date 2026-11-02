package com.korgnote.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.korgnote.app.viewmodel.FakeSyncApi
import com.korgnote.app.viewmodel.NotesViewModel
import com.korgnote.common.org.edit.TableGrid
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** A cell of the table editor takes the element editor's suggestions as it is typed in. */
@OptIn(ExperimentalTestApi::class)
class TableEditorCompletionTest {
    private val path = "notes.org"

    @Test
    fun aLinkIsCompletedInACell() = runComposeUiTest {
        val store = DocumentStore(InMemoryReplicaStorage())
        val viewModel = NotesViewModel(SyncEngine(store, FakeSyncApi(mutableMapOf(path to "* Garden\n"))), store)
        runBlocking { viewModel.server.load() }
        viewModel.onFileSelected(path)
        var saved: TableGrid? = null
        setContent {
            MaterialTheme {
                TableEditorDialog(
                    initial = TableGrid(listOf(listOf("a", "")), emptyList()),
                    onSave = { saved = it },
                    onDismiss = {},
                    cellCompletion = viewModel::completionFor,
                )
            }
        }

        onNodeWithContentDescription("Row 1, column 2").performClick()
        // Nothing typed yet: the strip offers to begin a link or a date.
        onNodeWithText("Link").assertExists()
        onNodeWithText("Date").assertExists()

        onNodeWithContentDescription("Row 1, column 2").performTextInput("[[Gar")
        onNodeWithText("*Garden").performClick()
        onNodeWithText("Save").performClick()

        assertEquals(listOf("a", "[[*Garden]]"), saved!!.rows.single())
    }
}
