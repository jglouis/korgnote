package com.korgnote.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.korgnote.common.fs.NotePaths

/**
 * Asks for a title and shows the file it will make, so the name on disk is never a surprise.
 * [pathFor] is the view model's own rule, so what is shown is what is created.
 */
@Composable
fun NewNoteDialog(
    pathFor: (title: String) -> String?,
    onCreate: (title: String) -> Unit,
    onDismiss: () -> Unit,
    /** Why the last attempt was refused, shown in the dialog rather than behind it. */
    error: String? = null,
) {
    var title by remember { mutableStateOf("") }
    val path = pathFor(title)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New note") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.focusRequester(focus),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    path ?: "Type a title",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                RefusalText(error)
            }
        },
        confirmButton = {
            TextButton(enabled = path != null, onClick = { onCreate(title) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Asks for a new path for [path]. A rename and a move are the same thing here: the field holds the
 * whole path, with the name selected, so typing replaces the name and editing the start moves it.
 */
@Composable
fun RenameNoteDialog(
    path: String,
    onRename: (newPath: String) -> Unit,
    onDismiss: () -> Unit,
    error: String? = null,
) {
    val folder = NotePaths.folderOf(path)
    val nameStart = if (folder.isEmpty()) 0 else folder.length + 1
    var value by remember {
        mutableStateOf(TextFieldValue(path, TextRange(nameStart, path.removeSuffix(".org").length)))
    }
    val target = NotePaths.normalize(value.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename or move") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Path") },
                    singleLine = true,
                    isError = target == null,
                    modifier = Modifier.focusRequester(focus),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (target == null) "Folders and a name, like notes/idea.org" else target,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (target == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                )
                RefusalText(error)
            }
        },
        confirmButton = {
            TextButton(enabled = target != null && target != path, onClick = { onRename(value.text) }) {
                Text("Rename")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Confirms a delete. When the note holds edits the server has not seen, it says so: those exist
 * nowhere else, and deleting the note deletes them. The server's own copy goes to its trash.
 */
@Composable
fun DeleteNoteDialog(
    path: String,
    hasUnsyncedChanges: Boolean,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $path?") },
        text = {
            Column {
                if (hasUnsyncedChanges) {
                    Text(
                        "This note has changes that have not reached the server yet. " +
                            "They exist only on this device and will be lost.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "It is deleted on every device. The server keeps a copy in .korgnote-trash, " +
                        "and if someone edits it elsewhere before this reaches the server, it comes back.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text(if (hasUnsyncedChanges) "Delete anyway" else "Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RefusalText(error: String?) {
    if (error == null) return
    Spacer(Modifier.height(8.dp))
    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
