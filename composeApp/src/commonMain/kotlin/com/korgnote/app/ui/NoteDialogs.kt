package com.korgnote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.korgnote.common.org.agenda.parseOrgTimestamp
import com.korgnote.common.org.complete.orgStamp
import com.korgnote.common.org.complete.restamp
import com.korgnote.common.org.edit.FileKeywords
import com.korgnote.common.org.template.parseTypedTags
import kotlinx.datetime.LocalDate

/**
 * Asks for a title and shows the file it will make, so the name on disk is never a surprise.
 * [pathFor] is the view model's own rule, so what is shown is what is created. Tags go into the
 * note's `#+filetags:`, see [TagsField].
 */
@Composable
fun NewNoteDialog(
    pathFor: (title: String) -> String?,
    onCreate: (title: String, tags: List<String>) -> Unit,
    onDismiss: () -> Unit,
    /** Tags used across the notes, most used first. */
    knownTags: List<String> = emptyList(),
    /** Why the last attempt was refused, shown in the dialog rather than behind it. */
    error: String? = null,
) {
    var title by remember { mutableStateOf("") }
    var tagsText by remember { mutableStateOf(TextFieldValue("")) }
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
                TagsField(tagsText, knownTags) { tagsText = it }
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
            TextButton(enabled = path != null, onClick = { onCreate(title, parseTypedTags(tagsText.text)) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Tags typed in one field, separated by spaces. The ones the notes already use are offered as chips
 * for the word being typed — or, before anything is typed, the most used — so a tag is picked rather
 * than spelt again a little differently.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagsField(value: TextFieldValue, knownTags: List<String>, onValueChange: (TextFieldValue) -> Unit) {
    val typedTags = parseTypedTags(value.text)
    // The word the caret is on is still being typed: it narrows the suggestions, and is not yet
    // one of the tags already chosen.
    val partial = value.text.takeLastWhile { it !in " ,:\t" }
    val chosen = if (partial.isEmpty()) typedTags else typedTags.dropLast(1)
    val suggestions = knownTags
        .filter { it !in chosen && it.startsWith(partial, ignoreCase = true) && !it.equals(partial, ignoreCase = true) }
        .take(8)

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Tags") },
        placeholder = { Text("work ecam") },
        singleLine = true,
    )
    if (suggestions.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            suggestions.forEach { tag ->
                TagSuggestionChip(tag) {
                    val text = (chosen + tag).joinToString(" ") + " "
                    onValueChange(TextFieldValue(text, TextRange(text.length)))
                }
            }
        }
    }
}

/**
 * The open note's title, author, date and tags, as a form: what its `#+` lines say, without having
 * to edit them as text. [initial] is what the note says now; the date has a picker, and the author
 * can be filled in from Settings with a tap.
 */
@Composable
fun NoteDetailsDialog(
    initial: FileKeywords,
    knownTags: List<String>,
    /** The author Settings names, offered when the note has none. */
    defaultAuthor: String,
    today: LocalDate,
    onSave: (FileKeywords) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initial.title) }
    var author by remember { mutableStateOf(initial.author) }
    var date by remember { mutableStateOf(initial.date) }
    var tagsText by remember {
        val text = initial.tags.joinToString(" ").let { if (it.isEmpty()) it else "$it " }
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }
    var pickingDate by remember { mutableStateOf(false) }

    if (pickingDate) {
        val stamp = parseOrgTimestamp(date)
        OrgDatePickerDialog(
            initialDate = stamp?.start ?: today,
            initialTime = stamp?.startTime,
            initialEndTime = stamp?.endTime,
            onPick = { picked, time, end ->
                // Inactive, as org-roam dates a note, unless the note had it otherwise.
                date = restamp(date.ifBlank { picked.orgStamp(active = false) }, picked, time, end)
                pickingDate = false
            },
            onDismiss = { pickingDate = false },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author") },
                    singleLine = true,
                    trailingIcon = if (author.isBlank() && defaultAuthor.isNotBlank()) {
                        { TextButton(onClick = { author = defaultAuthor }) { Text("Me") } }
                    } else null,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Date") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { pickingDate = true }) {
                            Icon(Icons.Filled.Event, contentDescription = "Pick the date")
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                TagsField(tagsText, knownTags) { tagsText = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(FileKeywords(title, author, date, parseTypedTags(tagsText.text)))
            }) { Text("Save") }
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
