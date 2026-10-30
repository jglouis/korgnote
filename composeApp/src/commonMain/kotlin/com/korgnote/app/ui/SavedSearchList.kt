package com.korgnote.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.korgnote.app.data.SavedSearch
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.cancel
import korgnote.composeapp.generated.resources.notes_name
import korgnote.composeapp.generated.resources.notes_rename_or_remove
import korgnote.composeapp.generated.resources.notes_rename_saved_search
import korgnote.composeapp.generated.resources.notes_save_search
import korgnote.composeapp.generated.resources.notes_saved_searches
import korgnote.composeapp.generated.resources.notes_saved_searches_empty
import korgnote.composeapp.generated.resources.remove
import korgnote.composeapp.generated.resources.rename
import korgnote.composeapp.generated.resources.save
import org.jetbrains.compose.resources.stringResource

/**
 * The saved searches, for the drawer the search screen opens: a tap runs one, a long-press offers
 * to rename or remove it.
 */
@Composable
fun SavedSearchList(
    searches: List<SavedSearch>,
    onRun: (SavedSearch) -> Unit,
    onRename: (SavedSearch) -> Unit,
    onRemove: (SavedSearch) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            stringResource(Res.string.notes_saved_searches),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        )
        if (searches.isEmpty()) {
            Text(
                stringResource(Res.string.notes_saved_searches_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            return@Column
        }
        LazyColumn {
            items(searches, key = { it.query }) { search ->
                SavedSearchRow(search, onRun, onRename, onRemove)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedSearchRow(
    search: SavedSearch,
    onRun: (SavedSearch) -> Unit,
    onRename: (SavedSearch) -> Unit,
    onRemove: (SavedSearch) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onRun(search) },
                    onLongClickLabel = stringResource(Res.string.notes_rename_or_remove),
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(search.name, style = MaterialTheme.typography.bodyMedium)
                // What it runs, under what it is called; said once when the name is the query.
                if (search.name != search.query) {
                    Text(
                        search.query,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.rename)) }, onClick = { menuOpen = false; onRename(search) })
            DropdownMenuItem(text = { Text(stringResource(Res.string.remove)) }, onClick = { menuOpen = false; onRemove(search) })
        }
    }
}

/**
 * Names a search being saved, or renames one already saved. The name starts as [name], selected,
 * so typing replaces it and confirming keeps it.
 */
@Composable
fun SaveSearchDialog(
    query: String,
    name: String,
    renaming: Boolean,
    onSave: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(name, TextRange(0, name.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (renaming) Res.string.notes_rename_saved_search else Res.string.notes_save_search)) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(Res.string.notes_name)) },
                    singleLine = true,
                    modifier = Modifier.focusRequester(focus),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    query,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.text) }) { Text(stringResource(if (renaming) Res.string.rename else Res.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
