package com.korgnote.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable

/**
 * The notes folder's state, for the top bar: nothing while it can be read and written, which is
 * what it is for, and a crossed-out folder when it cannot. A tap tries again; a long press says why.
 *
 * Unlike the server's cloud there is no sync to show going on: a save is in the folder as soon as
 * it is made, and whatever syncs the folder is another app's business.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderStatusIcon(unreachable: String?, onRetry: () -> Unit) {
    if (unreachable == null) return
    val description = "Cannot use the notes folder: $unreachable Edits are kept until it can be used again."
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onRetry) {
            Icon(Icons.Filled.FolderOff, contentDescription = description, tint = MaterialTheme.colorScheme.error)
        }
    }
}
