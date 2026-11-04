@file:OptIn(ExperimentalFoundationApi::class)

package com.korgnote.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.org.agenda.AgendaKind
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.cal_change_date
import korgnote.composeapp.generated.resources.cal_change_deadline
import korgnote.composeapp.generated.resources.cal_reschedule
import korgnote.composeapp.generated.resources.editor_clock_in
import korgnote.composeapp.generated.resources.editor_clock_out
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource

// --- Clocking from the calendar ----------------------------------------------------------------

/**
 * What the calendar can do to the heading behind an entry, given by the app: clock it in or out,
 * and change its keyword. None in a preview or a test that gives none, where a long-press does
 * nothing.
 */
class CalendarActions(
    val isRunning: (file: String, headingOffset: Int) -> Boolean,
    val clockIn: (file: String, headingOffset: Int) -> Unit,
    val clockOut: (file: String, headingOffset: Int) -> Unit,
    /** Opens the note at a clocked heading, as a tap on an entry opens its note. */
    val open: (file: String, headingOffset: Int) -> Unit,
    /** The keywords of the note at [file]: its own `#+TODO:`, or Org's own when it declares none. */
    val keywords: (file: String) -> OrgTodoConfig,
    /** Sets the heading's keyword, or clears it for null, as the note's keyword menu does. */
    val setTodo: (file: String, headingOffset: Int, keyword: String?) -> Unit,
    /** Moves an entry's date to the one picked, with its time or none. */
    val reschedule: ((entry: AgendaEntry, date: LocalDate, time: LocalTime?, endTime: LocalTime?) -> Unit)? = null,
)

val LocalCalendarActions = compositionLocalOf<CalendarActions?> { null }

/**
 * A long-press's menu on an entry or a clocked block: for an [entry], moving its date first —
 * *Reschedule*, *Change deadline* — then *Clock in* on its heading, or *Clock out* when its clock
 * runs, then its keywords, [todo] ticked, as the note's keyword menu offers them. Put inside what is
 * pressed, which it drops down from.
 */
@Composable
internal fun EntryMenu(
    file: String,
    headingOffset: Int,
    todo: String?,
    expanded: Boolean,
    onDismiss: () -> Unit,
    entry: AgendaEntry? = null,
) {
    val actions = LocalCalendarActions.current ?: return
    // Outside the menu, which closes as the picker opens.
    var rescheduling by remember { mutableStateOf(false) }
    val reschedule = actions.reschedule?.takeIf { entry != null && entry.kind != AgendaKind.Closed }
    if (rescheduling && entry != null && reschedule != null) {
        val stamp = entry.stamp
        // Over several days, the end's time is the last day's, not a range within the first.
        val endTime = stamp.endTime.takeIf { stamp.end == null }
        OrgDatePickerDialog(
            initialDate = stamp.start,
            initialTime = stamp.startTime,
            initialEndTime = endTime,
            onPick = { date, time, end -> rescheduling = false; reschedule(entry, date, time, end) },
            onDismiss = { rescheduling = false },
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (reschedule != null && entry != null) {
            val label = when (entry.kind) {
                AgendaKind.Deadline -> Res.string.cal_change_deadline
                AgendaKind.Scheduled -> Res.string.cal_reschedule
                else -> Res.string.cal_change_date
            }
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                leadingIcon = { Icon(Icons.Filled.EditCalendar, contentDescription = null) },
                onClick = { onDismiss(); rescheduling = true },
            )
            HorizontalDivider()
        }
        if (actions.isRunning(file, headingOffset)) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.editor_clock_out)) }, onClick = { onDismiss(); actions.clockOut(file, headingOffset) })
        } else {
            DropdownMenuItem(text = { Text(stringResource(Res.string.editor_clock_in)) }, onClick = { onDismiss(); actions.clockIn(file, headingOffset) })
        }
        HorizontalDivider()
        TodoKeywordItems(todo, remember(file, actions) { actions.keywords(file) }) { keyword ->
            onDismiss()
            if (keyword != todo) actions.setTodo(file, headingOffset, keyword)
        }
    }
}

/** A tap that opens, and a long-press that opens [EntryMenu] when the app gives it actions. */
@Composable
internal fun Modifier.openOrMenu(onOpen: () -> Unit, onMenu: () -> Unit): Modifier {
    val actions = LocalCalendarActions.current
    return combinedClickable(onClick = onOpen, onLongClick = if (actions != null) onMenu else null)
}
