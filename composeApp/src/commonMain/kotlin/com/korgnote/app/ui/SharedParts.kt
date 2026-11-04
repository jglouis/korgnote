package com.korgnote.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.cancel
import org.jetbrains.compose.resources.stringResource

/** The way out of a dialog, leaving things as they were. */
@Composable
internal fun CancelButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(Res.string.cancel)) }
}

/** A line of small print, set back from what it is about. */
@Composable
internal fun FaintHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}
