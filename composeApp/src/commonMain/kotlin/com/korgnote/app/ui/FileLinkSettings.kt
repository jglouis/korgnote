package com.korgnote.app.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.files.folderLabel
import com.korgnote.app.files.rememberFolderPicker

/**
 * The folders file links name, each with where it is on this device: chosen in the system's picker
 * on Android, typed as a path elsewhere. Kept by the Settings screen's Save, like the rest of it.
 */
@Composable
internal fun FileLinkSettings(mappings: List<FileLinkMapping>, onChange: (List<FileLinkMapping>) -> Unit) {
    // The row a folder is being chosen for, while the picker is showing.
    var choosingFor by remember { mutableStateOf<Int?>(null) }
    val pickFolder = rememberFolderPicker { folder ->
        choosingFor?.takeIf { it in mappings.indices }?.let { row ->
            onChange(mappings.mapIndexed { i, m -> if (i == row) m.copy(to = folder) else m })
        }
        choosingFor = null
    }

    Text("File links")
    Text(
        "Links to files outside the notes, like [[file:~/Sync/invoice.pdf]], open in the app this " +
            "device picks for the file. Where a folder they name is somewhere else here, say where.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    for ((row, mapping) in mappings.withIndex()) {
        fun update(changed: FileLinkMapping) = onChange(mappings.mapIndexed { i, m -> if (i == row) changed else m })
        Spacer(Modifier.height(8.dp))
        FileLinkMappingRow(
            from = { modifier ->
                OutlinedTextField(
                    modifier = modifier,
                    value = mapping.from,
                    onValueChange = { update(mapping.copy(from = it)) },
                    label = { Text("Folder in links") },
                    placeholder = { Text("~/Sync") },
                    singleLine = true,
                )
            },
            to = { modifier ->
                if (pickFolder != null) {
                    OutlinedButton(modifier = modifier, onClick = { choosingFor = row; pickFolder() }) {
                        Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (mapping.to.isBlank()) "Choose the folder" else folderLabel(mapping.to),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    OutlinedTextField(
                        modifier = modifier,
                        value = mapping.to,
                        onValueChange = { update(mapping.copy(to = it)) },
                        label = { Text("Folder on this device") },
                        placeholder = { Text("/home/me/Sync") },
                        singleLine = true,
                    )
                }
            },
            onRemove = { onChange(mappings.filterIndexed { i, _ -> i != row }) },
        )
    }
    TextButton(onClick = { onChange(mappings + FileLinkMapping("", "")) }) {
        Text("Add a folder")
    }
}

/**
 * One mapping drawn as one: the folder the links name, an arrow, and the folder it is here. Side by
 * side where there is room; on a phone the two fields would each be too narrow to read a path in,
 * so the arrow points down from one to the other inside a card that holds them together.
 */
@Composable
private fun FileLinkMappingRow(
    from: @Composable (Modifier) -> Unit,
    to: @Composable (Modifier) -> Unit,
    onRemove: () -> Unit,
) {
    val remove = @Composable {
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Remove this folder")
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 560.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                from(Modifier.weight(1f))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "is, on this device,",
                    modifier = Modifier.padding(horizontal = 8.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                to(Modifier.weight(1f))
                remove()
            }
        } else {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 12.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        from(Modifier.weight(1f).padding(top = 4.dp))
                        remove()
                    }
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.ArrowDownward,
                            contentDescription = null,
                            modifier = Modifier.padding(horizontal = 12.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "is, on this device,",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    to(Modifier.fillMaxWidth().padding(end = 12.dp))
                }
            }
        }
    }
}

/**
 * Why [mappings] cannot be saved, or null when they can. Rows left wholly empty are not a mistake;
 * the screen drops them.
 */
internal fun fileLinkMappingsError(mappings: List<FileLinkMapping>): String? {
    val filled = mappings.filter { it.from.isNotBlank() || it.to.isNotBlank() }
    return when {
        filled.any { it.from.isBlank() || it.to.isBlank() } ->
            "Please give each file-link folder both its path in links and its place on this device."
        filled.any { !it.from.trim().startsWith("~") && !it.from.trim().startsWith("/") } ->
            "Please write each folder in links as the links do, starting with ~ or /."
        else -> null
    }
}
