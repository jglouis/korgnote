package com.korgnote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.files.PickedFile
import com.korgnote.app.files.rememberFilePicker
import com.korgnote.app.viewmodel.LinkEdit
import com.korgnote.common.org.edit.LinkKind
import com.korgnote.common.org.edit.linkKindOf
import com.korgnote.common.org.link.LinkTarget

/**
 * A link as a form rather than as `[[…][…]]`: the words it shows, and where it goes — a web
 * address typed, a note picked from the notes, a file chosen on the device. Saving writes the
 * syntax; for a file, the path the notes' own machine knows it by, through Settings' File links.
 */
@Composable
fun LinkEditorDialog(
    edit: LinkEdit,
    /** What a note link's target is called, for showing it; see [com.korgnote.app.viewmodel.NotesViewModel.noteLinkTitle]. */
    noteTitle: (String) -> String,
    linkTargets: () -> List<LinkTarget>,
    currentPath: String,
    fileLinkMappings: List<FileLinkMapping>,
    /** The target to write and the words to show; or, when one was picked, the note to link by its id. */
    onSave: (target: String, description: String, note: LinkTarget?) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val original = edit.link.target.trim()
    val originalKind = if (original.isEmpty()) LinkKind.WEB else linkKindOf(original)
    var kind by remember { mutableStateOf(originalKind) }
    var description by remember { mutableStateOf(edit.link.description) }
    var address by remember { mutableStateOf(if (originalKind == LinkKind.WEB) original else "") }
    val filePrefix = if (originalKind == LinkKind.FILE) FILE_SCHEME.find(original)?.value.orEmpty() else "file:"
    var filePath by remember { mutableStateOf(if (originalKind == LinkKind.FILE) original.removePrefix(filePrefix) else "") }
    var fileProblem by remember { mutableStateOf<String?>(null) }
    var pickedNote by remember { mutableStateOf<LinkTarget?>(null) }
    var choosingNote by remember { mutableStateOf<List<LinkTarget>?>(null) }
    val keptNote = originalKind == LinkKind.NOTE && original.isNotEmpty()
    val noteShown = pickedNote?.title ?: if (keptNote) noteTitle(original) else null

    val pickFile = rememberFilePicker(fileLinkMappings) { picked ->
        when (picked) {
            is PickedFile.Linkable -> {
                filePath = picked.path
                fileProblem = null
            }
            is PickedFile.NotLinkable -> fileProblem = picked.reason
        }
    }

    val canSave = when (kind) {
        LinkKind.WEB -> address.isNotBlank()
        LinkKind.FILE -> filePath.isNotBlank()
        LinkKind.NOTE -> pickedNote != null || keptNote
    }

    choosingNote?.let { targets ->
        LinkPickerDialog(
            targets = targets,
            currentPath = currentPath,
            onPick = { target ->
                // Words that only named the old note follow it to the new one.
                if (description.isBlank() || description == noteShown) description = target.title
                pickedNote = target
                choosingNote = null
            },
            onTypeLink = null,
            onDismiss = { choosingNote = null },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (edit.isNew) "Add a link" else "Edit link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Label") },
                    placeholder = { Text("Shown instead of where it goes") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((k, label) in listOf(LinkKind.WEB to "Web", LinkKind.NOTE to "Note", LinkKind.FILE to "File")) {
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(label) })
                    }
                }
                when (kind) {
                    LinkKind.WEB -> OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Address") },
                        placeholder = { Text("https://…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Uri,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LinkKind.NOTE -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            noteShown ?: "No note chosen yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (noteShown == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { choosingNote = linkTargets() }) {
                            Text(if (noteShown == null) "Choose…" else "Change…")
                        }
                    }
                    LinkKind.FILE -> Column {
                        OutlinedTextField(
                            value = filePath,
                            onValueChange = {
                                filePath = it
                                fileProblem = null
                            },
                            label = { Text("File") },
                            placeholder = { Text("~/Sync/invoice.pdf") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (pickFile != null) {
                            TextButton(onClick = pickFile) { Text("Choose a file…") }
                        }
                        fileProblem?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    when (kind) {
                        LinkKind.WEB -> onSave(withScheme(address), description, null)
                        LinkKind.FILE -> onSave(filePrefix + filePath.trim(), description, null)
                        LinkKind.NOTE -> onSave(original, description, pickedNote)
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!edit.isNew) TextButton(onClick = onRemove) { Text("Remove link") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** An address typed as `example.com/page` is a web one; anything with a scheme of its own keeps it. */
internal fun withScheme(address: String): String {
    val a = address.trim()
    return if (HAS_SCHEME.containsMatchIn(a) || '.' !in a.substringBefore('/')) a else "https://$a"
}

private val FILE_SCHEME = Regex("^file(?:\\+sys|\\+emacs)?:", RegexOption.IGNORE_CASE)
private val HAS_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
