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
import korgnote.composeapp.generated.resources.cancel
import korgnote.composeapp.generated.resources.editor_state_note_closing
import korgnote.composeapp.generated.resources.editor_state_note_field
import korgnote.composeapp.generated.resources.editor_state_note_save
import korgnote.composeapp.generated.resources.editor_state_note_title
import org.jetbrains.compose.resources.stringResource

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
                    items = completion.suggestions,
                    selectedIndex = completion.selectedIndex,
                    onAccept = { completion.accept(it) },
                    inserts = completion.inserts.filter { it != DraftInsert.TABLE },
                    onInsert = { completion.insert(it) },
                    onPickDate = { completion.pickDate() },
                    clearOfNavigationBar = false,
                )
                Column(Modifier.heightIn(min = 96.dp)) {
                    OrgElementEditor(
                        elementKey = -1,
                        value = value,
                        onValueChange = onValueChange,
                        onCompletionKey = { key ->
                            if (completion.suggestions.isEmpty()) false else when (key) {
                                CompletionKey.ACCEPT -> completion.acceptSelected()
                                CompletionKey.DISMISS -> { completion.dismiss(); true }
                                CompletionKey.NEXT -> { completion.moveSelection(1); true }
                                CompletionKey.PREVIOUS -> { completion.moveSelection(-1); true }
                            }
                        },
                        todoKeywords = todoKeywords,
                        label = stringResource(Res.string.editor_state_note_field),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(Res.string.editor_state_note_save)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(Res.string.cancel)) } },
    )
}
