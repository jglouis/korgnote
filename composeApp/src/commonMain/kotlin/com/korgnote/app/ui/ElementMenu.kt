package com.korgnote.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.delete
import korgnote.composeapp.generated.resources.editor_add_heading
import korgnote.composeapp.generated.resources.editor_add_heading_below
import korgnote.composeapp.generated.resources.editor_add_subheading
import korgnote.composeapp.generated.resources.editor_cancel_clock
import korgnote.composeapp.generated.resources.editor_clock_in
import korgnote.composeapp.generated.resources.editor_clock_out
import korgnote.composeapp.generated.resources.editor_delete_heading
import korgnote.composeapp.generated.resources.editor_delete_heading_text
import korgnote.composeapp.generated.resources.editor_delete_heading_text_subheadings
import korgnote.composeapp.generated.resources.editor_delete_heading_title
import korgnote.composeapp.generated.resources.editor_demote
import korgnote.composeapp.generated.resources.editor_make_stamp_active
import korgnote.composeapp.generated.resources.editor_make_stamp_inactive
import korgnote.composeapp.generated.resources.editor_move_down
import korgnote.composeapp.generated.resources.editor_move_up
import korgnote.composeapp.generated.resources.editor_promote
import korgnote.composeapp.generated.resources.editor_refile
import korgnote.composeapp.generated.resources.editor_set_effort
import korgnote.composeapp.generated.resources.editor_this_heading
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import korgnote.composeapp.generated.resources.editor_priority

/** A stamp of the element the long-press menu can make active or inactive, as written. */
internal class MenuStamp(val text: String, val active: Boolean, val toggle: () -> Unit)

/**
 * How many of an element's stamps its menu offers to toggle. A journal entry can hold dozens, and a
 * menu of dozens is no menu; past these, the stamp is toggled from the editor's suggestions.
 */
internal const val MAX_MENU_STAMPS = 5

/**
 * What a long-press offers. Adding a heading used to be a **+** on every element; it is rare next
 * to reading, so it lives here, out of sight, along with restructuring a heading's subtree, and
 * making the element's dates active — in the calendar — or inactive.
 *
 * A null action is one that does not apply here. Moving and shifting stay listed, disabled, so the
 * menu keeps its shape from one heading to the next; the preamble, which is not a heading, gets
 * only what applies to it.
 */
@Composable
internal fun ElementMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isPreamble: Boolean,
    stamps: List<MenuStamp>,
    onAddHeadingBelow: (() -> Unit)?,
    onAddSubheading: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onPromote: (() -> Unit)?,
    onDemote: (() -> Unit)?,
    onRefile: (() -> Unit)?,
    onPriority: (() -> Unit)?,
    onClockIn: (() -> Unit)? = null,
    onClockOut: (() -> Unit)? = null,
    onCancelClock: (() -> Unit)? = null,
    onEffort: (() -> Unit)? = null,
) {
    @Composable
    fun Item(label: String, action: (() -> Unit)?) = DropdownMenuItem(
        text = { Text(label) },
        enabled = action != null,
        onClick = { onDismiss(); action?.invoke() },
    )

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (onAddHeadingBelow != null) {
            DropdownMenuItem(
                text = { Text(stringResource(if (isPreamble) Res.string.editor_add_heading else Res.string.editor_add_heading_below)) },
                onClick = { onDismiss(); onAddHeadingBelow() },
            )
        }
        // The preamble is not a heading, so there is nothing to put a child under.
        if (onAddSubheading != null && !isPreamble) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.editor_add_subheading)) },
                onClick = { onDismiss(); onAddSubheading() },
            )
        }
        // The heading's clock: started, or stopped and cancelled when it runs.
        if (!isPreamble && (onClockIn != null || onClockOut != null || onCancelClock != null)) {
            HorizontalDivider()
            onClockIn?.let { Item(stringResource(Res.string.editor_clock_in), it) }
            onClockOut?.let { Item(stringResource(Res.string.editor_clock_out), it) }
            onCancelClock?.let { Item(stringResource(Res.string.editor_cancel_clock), it) }
        }
        // What it should take, which its clocked time is shown against.
        if (!isPreamble && onEffort != null) Item(stringResource(Res.string.editor_set_effort), onEffort)
        if (stamps.isNotEmpty()) {
            HorizontalDivider()
            for (stamp in stamps) {
                val label = if (stamp.active) Res.string.editor_make_stamp_inactive else Res.string.editor_make_stamp_active
                Item(stringResource(label, stamp.text.replace(' ', ' ')), stamp.toggle)
            }
        }
        if (!isPreamble) {
            HorizontalDivider()
            Item(stringResource(Res.string.editor_move_up), onMoveUp)
            Item(stringResource(Res.string.editor_move_down), onMoveDown)
            Item(stringResource(Res.string.editor_promote), onPromote)
            Item(stringResource(Res.string.editor_demote), onDemote)
            // Out of this place entirely, `C-c C-w`: under a heading here or in another note.
            if (onRefile != null) Item(stringResource(Res.string.editor_refile), onRefile)
            if (onPriority != null) Item(stringResource(Res.string.editor_priority), onPriority)
        }
        if (onDelete != null && !isPreamble) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.editor_delete_heading), color = MaterialTheme.colorScheme.error) },
                onClick = { onDismiss(); onDelete() },
            )
        }
    }
}

/**
 * Confirms deleting a heading. It says how much goes with it, because the sub-headings may be
 * folded away or scrolled off screen, and a subtree is easy to underestimate.
 */
@Composable
internal fun DeleteSectionDialog(
    title: String,
    subheadings: Int,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.editor_delete_heading_title)) },
        text = {
            val heading = title.ifBlank { stringResource(Res.string.editor_this_heading) }
            Text(
                if (subheadings == 0) {
                    stringResource(Res.string.editor_delete_heading_text, heading)
                } else {
                    pluralStringResource(Res.plurals.editor_delete_heading_text_subheadings, subheadings, heading, subheadings)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onDelete) { Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { CancelButton(onDismiss) },
    )
}
