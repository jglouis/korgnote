package com.korgnote.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
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

// Previews

@Composable
private fun SharedPartsPreview(dark: Boolean) = PreviewTheme(dark) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        FaintHint("Saved on this phone; sent when the server is back.")
        CancelButton {}
    }
}

@Preview(name = "Light")
@Composable
private fun SharedPartsLightPreview() = SharedPartsPreview(dark = false)

@Preview(name = "Dark")
@Composable
private fun SharedPartsDarkPreview() = SharedPartsPreview(dark = true)
