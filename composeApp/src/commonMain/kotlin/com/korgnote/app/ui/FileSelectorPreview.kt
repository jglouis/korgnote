package com.korgnote.app.ui

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
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.ui.tooling.preview.Preview

// A simple in-memory model for preview/demo purposes
interface Node {
    val name: String
    val path: String
}

data class Folder(
    override val name: String,
    override val path: String,
    val children: List<Node>
) : Node

data class File(
    override val name: String,
    override val path: String
) : Node

/**
 * The file tree.
 *
 * A flat `LazyColumn` over [visibleRows] rather than a nested structure: only the rows on screen
 * compose, which matters because the drawer holds a row per note — 366 of them here — and the tree
 * is rebuilt on every keystroke of the filter.
 *
 * [expandAll] opens everything regardless of what the reader has closed, which is what the filter
 * passes while it is narrowing the tree.
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
) {
    // Keyed by path, not by node: the tree is a new object graph after every filter keystroke and
    // every sync, so identity-keyed state would be lost each time — re-opening every folder the
    // reader had closed, and restarting every animation along with it.
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val rows = root.visibleRows(collapsed.keys.toSet(), expandAll, conflicts)

    LazyColumn(modifier = modifier) {
        items(rows, key = { it.node.path }) { row ->
            when (val node = row.node) {
                is Folder -> TreeFolderItem(
                    folder = node,
                    depth = row.depth,
                    expanded = row.expanded,
                    // A folder opened by [expandAll] is not really open, so closing it has to
                    // record the collapse rather than toggle from what is on screen.
                    onToggle = {
                        if (collapsed.remove(node.path) == null) collapsed[node.path] = true
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
    val rotation = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "expand_rotation"
    ).value

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
                contentDescription = if (expanded) "Folder open" else "Folder"
            )
            Spacer(Modifier.width(8.dp))
            Text(
                folder.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.rotate(rotation)
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
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onRename != null || onDelete != null
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onFileSelected(file.path) },
                    onLongClickLabel = if (hasMenu) "Rename or delete" else null,
                    onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            Spacer(Modifier.width((depth * 16).dp))
            when (conflict) {
                null -> Icon(imageVector = Icons.Filled.Description, contentDescription = "File")
                FileConflict.NOTE -> Icon(
                    Icons.Filled.SyncProblem,
                    contentDescription = "Note with a conflict",
                    tint = MaterialTheme.colorScheme.error,
                )
                FileConflict.COPY -> Icon(
                    Icons.Filled.SyncProblem,
                    contentDescription = "Conflicting version",
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
            if (onRename != null) {
                DropdownMenuItem(
                    text = { Text("Rename or move") },
                    onClick = { menuOpen = false; onRename(file.path) },
                )
            }
            if (onDelete != null) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { menuOpen = false; onDelete(file.path) },
                )
            }
        }
    }
}

@Composable
@Preview
fun FileSelector_Preview() {
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
