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
                    contentDescription = "Notes with a conflict: ${conflicts.size}",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Changed in two places at once",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            for ((path, copies) in conflicts.entries.sortedBy { it.key }) {
                DropdownMenuItem(
                    text = { Text(if (copies.size == 1) path else "$path (${copies.size} versions)") },
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
) {
    val (original, copy, viewingCopy, moreCopies) = conflict
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
                    if (viewingCopy) "A conflicting version of $original" else "This note changed in two places at once",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (viewingCopy) {
                    "It could not be merged with the note, which holds the server's version."
                } else {
                    "This is the server's version. The one that could not be merged is kept as " +
                        copy.substringAfterLast('/') +
                        if (moreCopies > 0) " (and $moreCopies more)." else "."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onCompare) { Text("Compare") }
                TextButton(onClick = if (viewingCopy) onUseCopy else onKeepNote) {
                    Text(if (viewingCopy) "Use this one" else "Keep this one")
                }
                TextButton(onClick = if (viewingCopy) onKeepNote else onUseCopy) {
                    Text(if (viewingCopy) "Discard this one" else "Use the other")
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
) {
    val removed = MaterialTheme.colorScheme.errorContainer
    val added = MaterialTheme.colorScheme.tertiaryContainer
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compare versions") },
        text = {
            Column {
                Text("− only in ${original.substringAfterLast('/')} (the server's)", style = MaterialTheme.typography.bodySmall)
                Text("+ only in ${copy.substringAfterLast('/')}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                if (hunks.isEmpty()) {
                    Text("The two versions are the same: either can go.")
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    for (hunk in hunks) {
                        item {
                            Text(
                                hunk.heading ?: "Before the first heading",
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
                TextButton(onClick = onKeepNote) { Text("Keep the note") }
                TextButton(onClick = onUseCopy) { Text("Use the copy") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
