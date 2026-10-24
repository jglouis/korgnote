package com.korgnote.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The server's state, for the top bar: nothing while it answers, a crossed-out cloud while it does
 * not. A tap retries the sync; a long press says why the last attempt failed.
 *
 * Quiet on purpose: offline, notes still open and edits are kept, so this is a state to glance at,
 * not an error to read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerStatusIcon(unreachable: String?, retrying: Boolean, onRetry: () -> Unit) {
    if (unreachable == null) return
    if (retrying) {
        // The size of an IconButton, so the bar does not shift while the retry runs.
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        return
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(unreachable) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onRetry) {
            Icon(Icons.Filled.CloudOff, contentDescription = "Offline; tap to sync again")
        }
    }
}
