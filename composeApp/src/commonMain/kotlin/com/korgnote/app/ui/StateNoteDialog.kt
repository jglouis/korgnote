package com.korgnote.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.korgnote.app.viewmodel.CompletionController
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.edit.DraftInsert
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.editor_state_note_closing
import korgnote.composeapp.generated.resources.editor_state_note_field
import korgnote.composeapp.generated.resources.editor_state_note_save
import korgnote.composeapp.generated.resources.editor_state_note_title
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.korgnote.app.viewmodel.LinkNavigator
import com.korgnote.app.viewmodel.NoteLibrary
import com.korgnote.app.viewmodel.OpenNote
import com.korgnote.common.org.DEFAULT_TODO_KEYWORDS
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import kotlinx.datetime.LocalDate

/**
 * The note a keyword's `@` asks for, as Org's note buffer takes it: a few lines written in the same
 * editor as an element, with the same suggestions above it — tags, links, dates. Saving changes the
 * keyword and logs the note under its state line; an empty note logs the line alone. Cancelling
 * changes nothing.
 */
@Composable
fun StateNoteDialog(
    /** The keyword the heading is moving to; null for a closing note, `#+STARTUP: lognotedone`. */
    keyword: String?,
    headingTitle: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    completion: CompletionController,
    todoKeywords: OrgTodoConfig,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        // A note half written is not lost to a tap beside it.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Text(
                if (keyword != null) stringResource(Res.string.editor_state_note_title, keyword)
                else stringResource(Res.string.editor_state_note_closing),
            )
        },
        text = {
            Column {
                Text(headingTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                // As in the element editor: suggestions while typing, and with none, the buttons that
                // begin a link, a date or a checkbox. Not a table, which wants the grid editor.
                CompletionStrip(
                    completion,
                    inserts = completion.inserts.filter { it != DraftInsert.TABLE },
                    clearOfNavigationBar = false,
                )
                Column(Modifier.heightIn(min = 96.dp)) {
                    OrgElementEditor(
                        elementKey = -1,
                        value = value,
                        onValueChange = onValueChange,
                        onCompletionKey = completion::answer,
                        todoKeywords = todoKeywords,
                        label = stringResource(Res.string.editor_state_note_field),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(Res.string.editor_state_note_save)) } },
        dismissButton = { CancelButton(onCancel) },
    )
}

// Previews

@Composable
private fun StateNoteDialogPreview(dark: Boolean) = PreviewTheme(dark) {
    var draft by remember { mutableStateOf(TextFieldValue("Used the rain barrel.")) }
    val scope = rememberCoroutineScope()
    // Completion over an empty, in-memory library: the strip shows its insert buttons.
    val completion = remember {
        val library = NoteLibrary(DocumentStore(InMemoryReplicaStorage()))
        val note = OpenNote()
        val links = LinkNavigator(scope, library, note, open = { _, _ -> }, showMessage = {}, beforeGoingBack = {})
        CompletionController(library, note, links, today = { LocalDate(2025, 9, 15) }, draft = { draft }, replaceDraft = { draft = it })
    }
    StateNoteDialog(
        keyword = "DONE",
        headingTitle = "Water the tomatoes",
        value = draft,
        onValueChange = { draft = it },
        completion = completion,
        todoKeywords = DEFAULT_TODO_KEYWORDS,
        onSave = {},
        onCancel = {},
    )
}

@Preview(name = "Light")
@Composable
private fun StateNoteDialogLightPreview() = StateNoteDialogPreview(dark = false)

@Preview(name = "Dark")
@Composable
private fun StateNoteDialogDarkPreview() = StateNoteDialogPreview(dark = true)
