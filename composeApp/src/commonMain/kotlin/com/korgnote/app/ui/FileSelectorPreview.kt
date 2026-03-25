package com.korgnote.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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

@Composable
fun FileTree(
    root: Folder,
    onFileSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Track expanded state by node identity (here by object reference via Any hash)
    val expandedMap = remember { mutableStateMapOf<Folder, Boolean>() }

    // Make the tree scrollable to handle long file lists
    androidx.compose.foundation.lazy.LazyColumn(modifier = modifier) {
        item {
            TreeFolderItem(
                folder = root,
                depth = 0,
                expandedMap = expandedMap,
                onFileSelected = onFileSelected
            )
        }
    }
}

    @Composable
    private fun TreeFolderItem(
    folder: Folder,
    depth: Int,
    expandedMap: MutableMap<Folder, Boolean>,
    onFileSelected: (String) -> Unit,
) {
    val isExpanded = expandedMap[folder] ?: true // root expanded by default
    val rotation = animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "expand_rotation"
    ).value

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expandedMap[folder] = !isExpanded }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .animateContentSize(),
        horizontalArrangement = Arrangement.Start
    ) {
        Spacer(Modifier.width((depth * 16).dp))
        Icon(
            imageVector = if (isExpanded) Icons.Filled.FolderOpen else Icons.Filled.Folder,
            contentDescription = if (isExpanded) "Folder open" else "Folder"
        )
        Spacer(Modifier.width(8.dp))
        Text(
            folder.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            modifier = Modifier.rotate(rotation)
        )
    }
    HorizontalDivider()

    AnimatedVisibility(
        visible = isExpanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            folder.children.forEach { child ->
                when (child) {
                    is Folder -> TreeFolderItem(child, depth + 1, expandedMap, onFileSelected)
                    is File -> TreeFileItem(child, depth + 1, onFileSelected)
                }
            }
        }
    }
}

@Composable
private fun TreeFileItem(file: File, depth: Int, onFileSelected: (String) -> Unit) {
Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable { onFileSelected(file.path) }
        .padding(horizontal = 8.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.Start
) {
        Spacer(Modifier.width((depth * 16).dp))
        Icon(imageVector = Icons.Filled.Description, contentDescription = "File")
        Spacer(Modifier.width(8.dp))
        Text(file.name, style = MaterialTheme.typography.bodyMedium)
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
