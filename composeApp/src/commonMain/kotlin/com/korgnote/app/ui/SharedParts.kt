package com.korgnote.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.cancel
import org.jetbrains.compose.resources.stringResource

/** The way out of a dialog, leaving things as they were. */
@Composable
internal fun CancelButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(Res.string.cancel)) }
}

/**
 * For the field that is what a screen or a dialog is for: it has the keyboard as soon as it opens,
 * and again each time [key] changes. Not when [enabled] was false at that moment.
 */
@Composable
internal fun rememberAutoFocus(key: Any? = Unit, enabled: Boolean = true): FocusRequester {
    val focus = remember(key) { FocusRequester() }
    LaunchedEffect(key) { if (enabled) focus.requestFocus() }
    return focus
}

/** A line of small print, set back from what it is about. */
@Composable
internal fun FaintHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}
