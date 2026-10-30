package com.korgnote.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.link.LinkTarget
import com.korgnote.common.org.link.matching
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.cancel
import korgnote.composeapp.generated.resources.notes_heading
import korgnote.composeapp.generated.resources.notes_link_nothing_matches
import korgnote.composeapp.generated.resources.notes_link_query_placeholder
import korgnote.composeapp.generated.resources.notes_link_to
import korgnote.composeapp.generated.resources.notes_link_to_target
import korgnote.composeapp.generated.resources.notes_link_web_or_file
import korgnote.composeapp.generated.resources.notes_note
import org.jetbrains.compose.resources.stringResource

/**
 * Finds where a link goes, among every note and every heading in them, as org-roam's node finder
 * does: the words typed match the note's title, its path and the headings above, in any order.
 *
 * Picking one writes an `id:` link, giving the heading an `:ID:` first when it has none. A web
 * address or a file goes through the link form instead, which [onTypeLink] opens; null where the
 * picker was opened from that form.
 */
@Composable
fun LinkPickerDialog(
    targets: List<LinkTarget>,
    currentPath: String,
    onPick: (LinkTarget) -> Unit,
    onTypeLink: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(targets, query) { targets.matching(query, currentPath) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.notes_link_to)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(Res.string.notes_link_query_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (shown.isEmpty()) {
                    Text(
                        stringResource(Res.string.notes_link_nothing_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                // Short enough that the buttons stay above a phone's keyboard, which the dialog makes room for.
                LazyColumn(Modifier.heightIn(max = 240.dp).padding(top = 8.dp)) {
                    items(shown, key = { "${it.path}#${it.offset}" }) { target ->
                        LinkTargetRow(target, onClick = { onPick(target) })
                    }
                }
            }
        },
        confirmButton = {
            if (onTypeLink != null) TextButton(onClick = onTypeLink) { Text(stringResource(Res.string.notes_link_web_or_file)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun LinkTargetRow(target: LinkTarget, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .clickable(onClickLabel = stringResource(Res.string.notes_link_to_target, target.title), onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Icon(
            if (target.isNote) Icons.Outlined.Description else Icons.AutoMirrored.Filled.Notes,
            contentDescription = stringResource(if (target.isNote) Res.string.notes_note else Res.string.notes_heading),
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                target.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (target.isNote) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                target.where,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
