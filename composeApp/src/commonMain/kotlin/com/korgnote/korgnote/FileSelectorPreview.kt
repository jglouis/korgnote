package com.korgnote.korgnote

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
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.ui.tooling.preview.Preview

// A simple in-memory model for preview/demo purposes
sealed interface Node {
    val name: String
}

data class Folder(override val name: String, val children: List<Node>) : Node
data class File(override val name: String) : Node

@Composable
fun FileTree(
    root: Folder,
    onFileSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Track expanded state by node identity (here by object reference via Any hash)
    val expandedMap = remember { mutableStateMapOf<Folder, Boolean>() }

    Column(modifier = modifier) {
        TreeFolderItem(
            folder = root,
            depth = 0,
            expandedMap = expandedMap,
            onFileSelected = onFileSelected
        )
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
    val rotation = androidx.compose.animation.core.animateFloatAsState(
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

    androidx.compose.animation.AnimatedVisibility(
        visible = isExpanded,
        enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
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
            .clickable { onFileSelected(file.name) }
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
        children = listOf(
            Folder(
                name = "Projects",
                children = listOf(
                    Folder(
                        name = "KorgNote",
                        children = listOf(
                            File("README.md"),
                            File("Design.md"),
                            Folder(
                                name = "assets",
                                children = listOf(
                                    File("icon.png"),
                                    File("banner.jpg")
                                )
                            )
                        )
                    ),
                    File("todo.txt")
                )
            ),
            Folder(
                name = "Photos",
                children = listOf(
                    Folder(
                        name = "2023",
                        children = listOf(
                            File("vacation-1.jpg"),
                            File("vacation-2.jpg")
                        )
                    ),
                    Folder(
                        name = "2024",
                        children = listOf(
                            File("family.png")
                        )
                    )
                )
            ),
            File("notes.txt")
        )
    )

    MaterialTheme {
        Surface {
            FileTree(root = sample, onFileSelected = {}, modifier = Modifier.padding(8.dp))
        }
    }
}
