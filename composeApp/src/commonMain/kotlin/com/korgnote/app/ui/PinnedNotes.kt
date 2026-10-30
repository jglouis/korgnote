package com.korgnote.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.korgnote.app.viewmodel.pinLabels
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.notes_pinned
import korgnote.composeapp.generated.resources.notes_unpin
import org.jetbrains.compose.resources.stringResource

/**
 * The pinned notes, one row each, for the top of the drawer: a tap opens the note, a long-press
 * offers to unpin it. Draws nothing at all when nothing is pinned, so the drawer is as it was until
 * the reader asks for this.
 */
@Composable
fun PinnedNotes(
    paths: List<String>,
    onOpen: (path: String) -> Unit,
    onUnpin: (path: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (paths.isEmpty()) return
    Column(modifier = modifier) {
        paths.zip(pinLabels(paths)).forEach { (path, label) ->
            PinnedNoteRow(path, label, onOpen, onUnpin)
        }
        HorizontalDivider()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedNoteRow(
    path: String,
    label: String,
    onOpen: (String) -> Unit,
    onUnpin: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onOpen(path) },
                    onLongClickLabel = stringResource(Res.string.notes_unpin),
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = stringResource(Res.string.notes_pinned),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.notes_unpin)) },
                onClick = { menuOpen = false; onUnpin(path) },
            )
        }
    }
}
