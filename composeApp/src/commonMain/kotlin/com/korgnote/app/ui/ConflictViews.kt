package com.korgnote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.korgnote.common.sync.DiffHunk
import com.korgnote.common.sync.DiffLine
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.close
import korgnote.composeapp.generated.resources.notes_conflict_before_first_heading
import korgnote.composeapp.generated.resources.notes_conflict_compare
import korgnote.composeapp.generated.resources.notes_conflict_compare_title
import korgnote.composeapp.generated.resources.notes_conflict_copy_text
import korgnote.composeapp.generated.resources.notes_conflict_copy_title
import korgnote.composeapp.generated.resources.notes_conflict_count
import korgnote.composeapp.generated.resources.notes_conflict_discard_this
import korgnote.composeapp.generated.resources.notes_conflict_keep_note
import korgnote.composeapp.generated.resources.notes_conflict_keep_this
import korgnote.composeapp.generated.resources.notes_conflict_menu_title
import korgnote.composeapp.generated.resources.notes_conflict_note_text
import korgnote.composeapp.generated.resources.notes_conflict_note_text_more
import korgnote.composeapp.generated.resources.notes_conflict_note_title
import korgnote.composeapp.generated.resources.notes_conflict_only_in_copy
import korgnote.composeapp.generated.resources.notes_conflict_only_in_note
import korgnote.composeapp.generated.resources.notes_conflict_same
import korgnote.composeapp.generated.resources.notes_conflict_side_folder
import korgnote.composeapp.generated.resources.notes_conflict_side_server
import korgnote.composeapp.generated.resources.notes_conflict_use_copy
import korgnote.composeapp.generated.resources.notes_conflict_use_other
import korgnote.composeapp.generated.resources.notes_conflict_use_this
import korgnote.composeapp.generated.resources.notes_conflict_versions
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Notes with an unresolved conflict, for the top bar: nothing while there are none, a warning with
 * their count while there are. A tap lists them; picking one opens it, where its banner says what
 * to do.
 *
 * Not quiet like being offline: a conflict means two versions of a note exist and somebody has to
 * choose, and nothing will choose for them.
 */
@Composable
fun ConflictStatusIcon(conflicts: Map<String, List<String>>, onOpen: (path: String) -> Unit) {
    if (conflicts.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            BadgedBox(badge = { Badge { Text("${conflicts.size}") } }) {
                Icon(
                    Icons.Filled.SyncProblem,
                    contentDescription = stringResource(Res.string.notes_conflict_count, conflicts.size),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(Res.string.notes_conflict_menu_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            for ((path, copies) in conflicts.entries.sortedBy { it.key }) {
                DropdownMenuItem(
                    text = { Text(if (copies.size == 1) path else stringResource(Res.string.notes_conflict_versions, path, copies.size)) },
                    onClick = { open = false; onOpen(path) },
                )
            }
        }
    }
}

/** The conflict the banner shows for the open note: a note and one of its copies. */
data class ShownConflict(
    val original: String,
    val copy: String,
    /** True when the copy is what is open, rather than the note. */
    val viewingCopy: Boolean,
    /** Copies beyond [copy]; each gets its turn once this one is settled. */
    val moreCopies: Int,
)

/**
 * What the banner says for [openPath]: on a note, its newest copy and how many more there are; on
 * a copy, its own note. Null when neither is in conflict.
 */
fun conflictShown(openPath: String?, conflicts: Map<String, List<String>>): ShownConflict? {
    if (openPath == null) return null
    conflicts[openPath]?.firstOrNull()?.let { newest ->
        return ShownConflict(openPath, newest, viewingCopy = false, moreCopies = conflicts.getValue(openPath).size - 1)
    }
    val original = conflicts.entries.firstOrNull { openPath in it.value }?.key ?: return null
    return ShownConflict(original, openPath, viewingCopy = true, moreCopies = 0)
}

/**
 * Above a note in conflict: which version is on screen, where the other one is, and the three ways
 * out. Shown on the note itself and on a copy, worded for whichever is open, with the same actions
 * either way. See [conflictShown].
 */
@Composable
fun ConflictBanner(
    conflict: ShownConflict,
    onCompare: () -> Unit,
    onKeepNote: () -> Unit,
    onUseCopy: () -> Unit,
    modifier: Modifier = Modifier,
    /** Whose version the note holds: the server's, or the folder's when the notes are a folder. */
    keptSide: String = "server",
) {
    val (original, copy, viewingCopy, moreCopies) = conflict
    val side = keptSideName(keptSide)
    val copyName = copy.substringAfterLast('/')
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SyncProblem, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (viewingCopy) {
                        stringResource(Res.string.notes_conflict_copy_title, original)
                    } else {
                        stringResource(Res.string.notes_conflict_note_title)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    viewingCopy -> stringResource(Res.string.notes_conflict_copy_text, side)
                    moreCopies > 0 -> pluralStringResource(Res.plurals.notes_conflict_note_text_more, moreCopies, side, copyName, moreCopies)
                    else -> stringResource(Res.string.notes_conflict_note_text, side, copyName)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onCompare) { Text(stringResource(Res.string.notes_conflict_compare)) }
                TextButton(onClick = if (viewingCopy) onUseCopy else onKeepNote) {
                    Text(stringResource(if (viewingCopy) Res.string.notes_conflict_use_this else Res.string.notes_conflict_keep_this))
                }
                TextButton(onClick = if (viewingCopy) onKeepNote else onUseCopy) {
                    Text(stringResource(if (viewingCopy) Res.string.notes_conflict_discard_this else Res.string.notes_conflict_use_other))
                }
            }
        }
    }
}

/**
 * The note and its copy side by side, change by change: each hunk under the heading it falls in,
 * lines only in the note marked −, lines only in the copy marked +. Settling it from here is the
 * same as from the banner.
 */
@Composable
fun ConflictCompareDialog(
    original: String,
    copy: String,
    hunks: List<DiffHunk>,
    onKeepNote: () -> Unit,
    onUseCopy: () -> Unit,
    onDismiss: () -> Unit,
    /** As in [ConflictBanner]. */
    keptSide: String = "server",
) {
    val removed = MaterialTheme.colorScheme.errorContainer
    val added = MaterialTheme.colorScheme.tertiaryContainer
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.notes_conflict_compare_title)) },
        text = {
            Column {
                Text(
                    stringResource(Res.string.notes_conflict_only_in_note, original.substringAfterLast('/'), keptSideName(keptSide)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(Res.string.notes_conflict_only_in_copy, copy.substringAfterLast('/')),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                if (hunks.isEmpty()) {
                    Text(stringResource(Res.string.notes_conflict_same))
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    for (hunk in hunks) {
                        item {
                            Text(
                                hunk.heading ?: stringResource(Res.string.notes_conflict_before_first_heading),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                            )
                        }
                        items(hunk.lines) { line ->
                            val (mark, background) = when (line.kind) {
                                DiffLine.Kind.SAME -> "  " to null
                                DiffLine.Kind.REMOVED -> "− " to removed
                                DiffLine.Kind.ADDED -> "+ " to added
                            }
                            Text(
                                mark + line.text,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (background != null) Modifier.background(background) else Modifier)
                                    .padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onKeepNote) { Text(stringResource(Res.string.notes_conflict_keep_note)) }
                TextButton(onClick = onUseCopy) { Text(stringResource(Res.string.notes_conflict_use_copy)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
    )
}

/** `server` or `folder`, as [ConflictBanner]'s `keptSide` names them, in the app's language. */
@Composable
private fun keptSideName(keptSide: String): String =
    stringResource(if (keptSide == "folder") Res.string.notes_conflict_side_folder else Res.string.notes_conflict_side_server)
