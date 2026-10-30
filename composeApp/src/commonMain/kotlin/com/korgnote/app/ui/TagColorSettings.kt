package com.korgnote.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.cancel
import korgnote.composeapp.generated.resources.settings_colour_from_name
import korgnote.composeapp.generated.resources.settings_no_tags
import korgnote.composeapp.generated.resources.settings_show_all_tags
import korgnote.composeapp.generated.resources.settings_show_fewer_tags
import org.jetbrains.compose.resources.stringResource

/**
 * The tags the notes use, each in its colour; tapping one picks another from the palette, or puts
 * it back to the one its name gives it. [chosen] is the form's copy, saved with the rest of Settings.
 *
 * The most used come first and only so many are shown until asked: a corpus has a long tail of tags
 * used once, and nobody wants to scroll past it to the server settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagColorSettings(knownTags: List<String>, chosen: Map<String, Int>, onChange: (Map<String, Int>) -> Unit) {
    var picking by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }
    // Tags with a colour chosen stay listed even once no note uses them, so the choice can be undone.
    val tags = (knownTags + chosen.keys.filter { it !in knownTags })
    val shown = if (showAll) tags else tags.take(SHOWN_AT_FIRST)

    if (tags.isEmpty()) {
        Text(stringResource(Res.string.settings_no_tags), style = MaterialTheme.typography.bodySmall)
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Drawn from the form, not from what is saved, so a choice shows before Save is pressed.
        shown.forEach { tag -> TagSuggestionChip(tag, colorOverride = colorFor(tag, chosen)) { picking = tag } }
    }
    if (tags.size > SHOWN_AT_FIRST) {
        TextButton(onClick = { showAll = !showAll }) {
            Text(
                if (showAll) stringResource(Res.string.settings_show_fewer_tags)
                else stringResource(Res.string.settings_show_all_tags, tags.size),
            )
        }
    }

    picking?.let { tag ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(tag) },
            text = {
                Column {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TagColors.palette.forEach { swatch ->
                            val selected = chosen[tag] == swatch.light
                            Surface(
                                onClick = {
                                    onChange(chosen + (tag to swatch.light))
                                    picking = null
                                },
                                shape = CircleShape,
                                color = swatchColor(swatch),
                                modifier = Modifier.size(36.dp).then(
                                    if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier
                                ),
                            ) {}
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onChange(chosen - tag)
                    picking = null
                }) { Text(stringResource(Res.string.settings_colour_from_name)) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

/** [tag]'s colour as the form has it, which may not be saved yet. */
private fun colorFor(tag: String, chosen: Map<String, Int>): Color {
    val rgb = chosen[tag]
    val swatch = rgb?.let { value -> TagColors.palette.firstOrNull { it.light == value } } ?: TagColors.automatic(tag)
    return swatchColor(swatch)
}

private fun swatchColor(swatch: TagColors.Swatch) =
    Color(0xFF000000.toInt() or if (OrgColors.dark) swatch.dark else swatch.light)

private const val SHOWN_AT_FIRST = 24
