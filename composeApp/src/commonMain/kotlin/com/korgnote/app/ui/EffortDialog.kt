package com.korgnote.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.edit.effortMinutes
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.editor_effort_current
import korgnote.composeapp.generated.resources.editor_effort_other
import korgnote.composeapp.generated.resources.editor_effort_title
import korgnote.composeapp.generated.resources.editor_remove_effort
import korgnote.composeapp.generated.resources.ok
import org.jetbrains.compose.resources.stringResource

/** The efforts offered with a tap, in minutes: a quarter of an hour to half a day. */
private val EFFORT_CHOICES = listOf(15, 30, 60, 120, 240)

/**
 * What a heading should take, as `org-set-effort` asks it: a few common lengths a tap away, or
 * another typed as Org reads one (`1:30`, `45`, `2h`, `1h 30min`); and, when it has one, removing it.
 */
@Composable
internal fun EffortDialog(current: String?, onPick: (minutes: Int?) -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val typedMinutes = effortMinutes(typed)?.takeIf { it > 0 }
    val currentMinutes = current?.let(::effortMinutes)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.editor_effort_title)) },
        text = {
            Column {
                for (minutes in EFFORT_CHOICES) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(minutes) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(clockDuration(minutes), modifier = Modifier.weight(1f))
                        if (minutes == currentMinutes) {
                            Icon(Icons.Filled.Check, contentDescription = stringResource(Res.string.editor_effort_current))
                        }
                    }
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(Res.string.editor_effort_other)) },
                    singleLine = true,
                    isError = typed.isNotBlank() && typedMinutes == null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (current != null) {
                    TextButton(onClick = { onPick(null) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(Res.string.editor_remove_effort), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { typedMinutes?.let(onPick) }, enabled = typedMinutes != null) { Text(stringResource(Res.string.ok)) }
        },
        dismissButton = { CancelButton(onDismiss) },
    )
}
