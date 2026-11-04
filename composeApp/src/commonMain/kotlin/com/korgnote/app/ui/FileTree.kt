package com.korgnote.app.ui

import com.korgnote.common.fs.File
import com.korgnote.common.fs.Folder
import com.korgnote.common.fs.Node
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.ui.tooling.preview.Preview
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.delete
import korgnote.composeapp.generated.resources.editor_collapse
import korgnote.composeapp.generated.resources.editor_conflicting_version
import korgnote.composeapp.generated.resources.editor_expand
import korgnote.composeapp.generated.resources.editor_file
import korgnote.composeapp.generated.resources.editor_folder
import korgnote.composeapp.generated.resources.editor_folder_open
import korgnote.composeapp.generated.resources.editor_note_actions
import korgnote.composeapp.generated.resources.editor_note_with_conflict
import korgnote.composeapp.generated.resources.editor_pin
import korgnote.composeapp.generated.resources.editor_rename_or_move
import korgnote.composeapp.generated.resources.editor_unpin
import org.jetbrains.compose.resources.stringResource

/**
 * The file tree.
 *
 * A flat `LazyColumn` over [visibleRows] rather than a nested structure: only the rows on screen
 * compose, which matters because the drawer holds a row per note — 366 of them here — and the tree
 * is rebuilt on every keystroke of the filter.
 *
 * Folders start closed. [expandAll] opens everything regardless, which is what the filter passes
 * while it is narrowing the tree to the folders that hold a match.
 */
@Composable
fun FileTree(
    root: Folder,
    onFileSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    expandAll: Boolean = false,
    /** Offered on a long-press of a note; either may be null to leave it out. */
    onRenameFile: ((path: String) -> Unit)? = null,
    onDeleteFile: ((path: String) -> Unit)? = null,
    /** Notes with conflict copies: both are flagged, and each copy drawn under its note. */
    conflicts: Map<String, List<String>> = emptyMap(),
    /** Pinned notes, whose long-press menu offers to unpin rather than pin. */
    pinned: Set<String> = emptySet(),
    /** Offered on a long-press of a note, as Pin or Unpin; null to leave it out. */
    onTogglePin: ((path: String) -> Unit)? = null,
) {
    // Keyed by path, not by node: the tree is a new object graph after every filter keystroke and
    // every sync, so identity-keyed state would be lost each time — closing every folder the
    // reader had opened, and restarting every animation along with it.
    val toggled = remember { mutableStateMapOf<String, Boolean>() }
    val rows = root.visibleRows(toggled.keys.toSet(), expandAll, conflicts)

    LazyColumn(modifier = modifier) {
        items(rows, key = { it.node.path }) { row ->
            when (val node = row.node) {
                is Folder -> TreeFolderItem(
                    folder = node,
                    depth = row.depth,
                    expanded = row.expanded,
                    // Flips what the reader chose, not what is on screen: a folder [expandAll]
                    // opened is open only while the filter holds it.
                    onToggle = {
                        if (toggled.remove(node.path) == null) toggled[node.path] = true
                    },
                    modifier = Modifier.animateItem(),
                )

                is File -> TreeFileItem(
                    file = node,
                    depth = row.depth,
                    conflict = when {
                        row.copyOf != null -> FileConflict.COPY
                        node.path in conflicts -> FileConflict.NOTE
                        else -> null
                    },
                    onFileSelected = onFileSelected,
                    onRename = onRenameFile,
                    onDelete = onDeleteFile,
                    pinned = node.path in pinned,
                    onTogglePin = onTogglePin,
                    modifier = Modifier.animateItem(),
                )

                else -> Unit
            }
        }
    }
}

@Composable
private fun TreeFolderItem(
    folder: Folder,
    depth: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            Spacer(Modifier.width((depth * 16).dp))
            Icon(
                imageVector = if (expanded) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                contentDescription = stringResource(if (expanded) Res.string.editor_folder_open else Res.string.editor_folder)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                folder.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            DisclosureChevron(
                expanded = expanded,
                contentDescription = stringResource(if (expanded) Res.string.editor_collapse else Res.string.editor_expand),
            )
        }
        HorizontalDivider()
    }
}

/** A file's part in a conflict: the note that has copies, or one of those copies. */
private enum class FileConflict { NOTE, COPY }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeFileItem(
    file: File,
    depth: Int,
    conflict: FileConflict?,
    onFileSelected: (String) -> Unit,
    onRename: ((String) -> Unit)?,
    onDelete: ((String) -> Unit)?,
    pinned: Boolean,
    onTogglePin: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onRename != null || onDelete != null || onTogglePin != null
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onFileSelected(file.path) },
                    onLongClickLabel = if (hasMenu) stringResource(Res.string.editor_note_actions) else null,
                    onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            Spacer(Modifier.width((depth * 16).dp))
            when (conflict) {
                null -> Icon(imageVector = Icons.Filled.Description, contentDescription = stringResource(Res.string.editor_file))
                FileConflict.NOTE -> Icon(
                    Icons.Filled.SyncProblem,
                    contentDescription = stringResource(Res.string.editor_note_with_conflict),
                    tint = MaterialTheme.colorScheme.error,
                )
                FileConflict.COPY -> Icon(
                    Icons.Filled.SyncProblem,
                    contentDescription = stringResource(Res.string.editor_conflicting_version),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                file.name,
                style = if (conflict == FileConflict.COPY) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = if (conflict == FileConflict.NOTE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (onTogglePin != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (pinned) Res.string.editor_unpin else Res.string.editor_pin)) },
                    onClick = { menuOpen = false; onTogglePin(file.path) },
                )
            }
            if (onRename != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.editor_rename_or_move)) },
                    onClick = { menuOpen = false; onRename(file.path) },
                )
            }
            if (onDelete != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.delete)) },
                    onClick = { menuOpen = false; onDelete(file.path) },
                )
            }
        }
    }
}

/**
 * One line of the file tree as it is drawn: a node, how far it is indented, and — for a folder —
 * whether it is open.
 *
 * The tree is flattened into these before it is rendered, so the list can be a real `LazyColumn`
 * and compose only the rows on screen. Drawing it as a nested structure meant every row of every
 * open folder composed whether or not anyone could see it, and the drawer holds a row per note.
 */
data class TreeRow(
    val node: Node,
    val depth: Int,
    /** Meaningful for folders; always false for files, which have nothing to open. */
    val expanded: Boolean,
    /** For a conflict copy drawn under its note: that note's path. */
    val copyOf: String? = null,
)

/**
 * The rows to draw, top to bottom, skipping everything inside a closed folder.
 *
 * Folders below the root are closed unless [opened] names them, so an untouched tree shows the
 * notes at the top and a row per folder rather than every note at once. The root is open unless
 * [opened] names it, which is what closing it records. [expandAll] overrides the lot, which is what
 * a filter needs: the folders left in a filtered tree are the ones holding a match, and a match
 * hidden in a closed folder would look like a filter that does not work.
 *
 * [opened] holds **paths**, not nodes. The tree is rebuilt from scratch whenever the filter or the
 * replica changes, so anything keyed by node identity is thrown away on every keystroke — which is
 * what used to reset every folder and restart every animation while typing.
 */
fun Folder.visibleRows(
    opened: Set<String>,
    expandAll: Boolean = false,
    /**
     * Notes with conflict copies, as `NoteLibrary.conflicts` holds them. Each copy is drawn under
     * its note rather than wherever its name sorts, so the two read as one problem. A copy whose
     * note is not in the folder — filtered out, say — stays where it is.
     */
    conflicts: Map<String, List<String>> = emptyMap(),
): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()

    fun walk(folder: Folder, depth: Int) {
        // The root's entry in [opened] is a toggle like any other, flipped the other way round.
        val expanded = expandAll || (folder.path in opened) != (depth == 0)
        rows += TreeRow(folder, depth, expanded)
        if (!expanded) return
        val files = folder.children.filterIsInstance<File>().associateBy { it.path }
        val grouped = folder.children
            .filter { it is File && it.path in conflicts }
            .flatMap { conflicts.getValue(it.path) }
            .filter { it in files }
            .toSet()
        for (child in folder.children) {
            when (child) {
                is Folder -> walk(child, depth + 1)
                is File -> {
                    if (child.path in grouped) continue
                    rows += TreeRow(child, depth + 1, expanded = false)
                    for (copy in conflicts[child.path].orEmpty()) {
                        val node = files[copy] ?: continue
                        rows += TreeRow(node, depth + 2, expanded = false, copyOf = child.path)
                    }
                }
                else -> Unit
            }
        }
    }

    walk(this, depth = 0)
    return rows
}

@Composable
@Preview
fun FileTree_Preview() {
    val sample = Folder(
        name = "Documents",
        path = "",
        children = listOf(
            Folder(
                name = "Org",
                path = "Org",
                children = listOf(
                    Folder(
                        name = "Agenda",
                        path = "Org/Agenda",
                        children = listOf(
                            File("inbox.org", "Org/Agenda/inbox.org"),
                            File("next-actions.org", "Org/Agenda/next-actions.org"),
                            File("calendar.org", "Org/Agenda/calendar.org")
                        )
                    ),
                    Folder(
                        name = "Projects",
                        path = "Org/Projects",
                        children = listOf(
                            File("korgnote.org", "Org/Projects/korgnote.org"),
                            File("website-redesign.org", "Org/Projects/website-redesign.org"),
                            File("research-notes.org", "Org/Projects/research-notes.org")
                        )
                    ),
                    Folder(
                        name = "Journal",
                        path = "Org/Journal",
                        children = listOf(
                            Folder(
                                name = "2024",
                                path = "Org/Journal/2024",
                                children = listOf(
                                    File("2024-12-31.org", "Org/Journal/2024/2024-12-31.org")
                                )
                            ),
                            Folder(
                                name = "2025",
                                path = "Org/Journal/2025",
                                children = listOf(
                                    File("2025-01-01.org", "Org/Journal/2025/2025-01-01.org"),
                                    File("2025-09-03.org", "Org/Journal/2025/2025-09-03.org")
                                )
                            )
                        )
                    ),
                    Folder(
                        name = "Reference",
                        path = "Org/Reference",
                        children = listOf(
                            File("books.org", "Org/Reference/books.org"),
                            File("quotes.org", "Org/Reference/quotes.org")
                        )
                    )
                )
            ),
            File("notes.org", "notes.org")
        )
    )

    MaterialTheme {
        Surface {
            FileTree(root = sample, onFileSelected = {}, modifier = Modifier.padding(8.dp).height(300.dp))
        }
    }
}
