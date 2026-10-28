package com.korgnote.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.IconButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.complete.CompletionItem
import com.korgnote.common.org.edit.DraftInsert
import com.korgnote.common.org.complete.shiftedEnd
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/** What a hardware keyboard can ask of the suggestions. */
enum class CompletionKey { ACCEPT, DISMISS, NEXT, PREVIOUS }

/**
 * The suggestions, as a strip of chips **pinned to the bottom of the screen** while an element is
 * being edited.
 *
 * Pinned rather than placed next to the field, because the field is inside a scrolling list: both
 * Android's `imePadding` and iOS's lifting bring *the field* into view when the keyboard opens, so
 * anything laid out after it lands in the region the keyboard just took. Position it against the
 * screen and that cannot happen, whatever the element's height or where the caret is in it.
 *
 * A strip rather than a floating popup, on every platform: the popup the design sketched suits a
 * desktop with a hardware keyboard, but it fights the IME and the selection handles on a phone,
 * which is where these notes are mostly written. One presentation also means one thing to keep
 * working. The keyboard path (Tab to accept, arrows to move, Esc to dismiss) is handled by the
 * field itself, so a desktop user never has to reach for the chips.
 *
 * With nothing to suggest, the strip holds [inserts] instead: buttons that begin a link, a tag, a
 * date, a place or a checkbox, so that none of their syntax has to be remembered or typed.
 *
 * [actions] — discarding and saving the element — sit at the end of the strip for the same reason.
 * As floating buttons they were drawn over the document, and the last element of a file, having
 * nothing below it to scroll up with, stayed under them while it was being edited.
 */
@Composable
fun CompletionStrip(
    items: List<CompletionItem>,
    selectedIndex: Int,
    onAccept: (CompletionItem) -> Unit,
    modifier: Modifier = Modifier,
    inserts: List<DraftInsert> = emptyList(),
    onInsert: (DraftInsert) -> Unit = {},
    /** The date button, which asks a picker before it has anything to insert. */
    onPickDate: (() -> Unit)? = null,
    /** The link button, when it finds a note or heading to link to instead of starting a `[[`. */
    onPickLink: (() -> Unit)? = null,
    /**
     * Opens the link form on the link the caret is in; null when it is in none. Its button stays in
     * front of the suggestions, which the caret in a link's target brings up in place of the inserts.
     */
    onEditLink: (() -> Unit)? = null,
    /** The table button, which opens the grid editor on a new table rather than typing pipes. */
    onInsertTable: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val listState = rememberLazyListState()
    // Keyboard selection can walk past the edge of the strip; keep the chosen chip in sight.
    val leading = if (onEditLink != null) 1 else 0
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in items.indices) listState.animateScrollToItem(selectedIndex + leading)
    }

    // The bar keeps its place for the whole edit, empty or not. Suggestions come and go with almost
    // every keystroke, and a bar that came and went with them would reflow the document under the
    // caret each time — trading one distraction for a worse one. Empty, it offers the inserts.
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The chips scroll in what the actions leave, so the actions never scroll away.
            BoxWithConstraints(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (items.isEmpty()) {
                    // A date anywhere comes next to the link, before the heading's own things.
                    val buttons = buildList {
                        for (what in inserts) {
                            when {
                                // On a link, the link button is for that link.
                                what == DraftInsert.LINK && onEditLink != null -> add(Triple("Edit link", what.icon(), onEditLink))
                                what == DraftInsert.LINK && onPickLink != null -> add(Triple(what.label(), what.icon(), onPickLink))
                                what == DraftInsert.TABLE && onInsertTable != null -> add(Triple(what.label(), what.icon(), onInsertTable))
                                else -> add(Triple(what.label(), what.icon()) { onInsert(what) })
                            }
                            if (what == DraftInsert.LINK && onPickDate != null) add(Triple("Date", Icons.Filled.Event, onPickDate))
                        }
                    }
                    // Labelled where there is room for all of them; on a phone, icons, so that none
                    // is scrolled out of sight where nobody would know to look for it.
                    if (maxWidth >= LABELLED_INSERT_WIDTH * buttons.size) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            buttons.forEach { (label, icon, onClick) -> item { InsertChip(label, icon, onClick) } }
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        ) {
                            buttons.forEach { (label, icon, onClick) ->
                                IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
                                    Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } else {
                    LazyRow(
                        state = listState,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        if (onEditLink != null) item { InsertChip("Edit link", Icons.Filled.Link, onEditLink) }
                        itemsIndexed(items) { index, item ->
                            val selected = index == selectedIndex
                            AssistChip(
                                onClick = { onAccept(item) },
                                // A long address must not take the whole strip and hide the others.
                                label = {
                                    Text(
                                        item.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 150.dp),
                                    )
                                },
                                colors = if (selected) {
                                    AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                } else {
                                    AssistChipDefaults.assistChipColors()
                                },
                            )
                        }
                    }
                }
            }
            // Set apart, so that discarding is never mistaken for one of the inserts beside it.
            VerticalDivider(Modifier.height(28.dp).padding(horizontal = 2.dp))
            actions()
        }
    }
}

/** Room for one labelled insert chip; below that per button, the strip shows icons. */
private val LABELLED_INSERT_WIDTH = 104.dp

@Composable
private fun InsertChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

private fun DraftInsert.label(): String = when (this) {
    DraftInsert.LINK -> "Link"
    DraftInsert.TAG -> "Tag"
    DraftInsert.SCHEDULE -> "Schedule"
    DraftInsert.LOCATION -> "Place"
    DraftInsert.CHECKBOX -> "Checkbox"
    DraftInsert.TABLE -> "Table"
}

private fun DraftInsert.icon(): ImageVector = when (this) {
    DraftInsert.LINK -> Icons.Filled.Link
    DraftInsert.TAG -> Icons.Filled.Sell
    DraftInsert.SCHEDULE -> Icons.Filled.EditCalendar
    DraftInsert.LOCATION -> Icons.Filled.Place
    DraftInsert.CHECKBOX -> Icons.Filled.CheckBox
    DraftInsert.TABLE -> Icons.Filled.TableChart
}

/**
 * The date and time picker behind the "Pick date & time…" suggestion, for the dates the shortcuts do
 * not cover — and for changing a stamp already written, which it opens on.
 *
 * The date comes first and is all most entries need, so OK there is the whole of it: it keeps the
 * time the stamp already had. The time is a second step, behind a button that shows the current
 * one; that step can also take the time away. An end time, making it a range such as a meeting's
 * `10:00-11:30`, is a third step behind the time, so a plain time costs no more taps than before.
 * Org writes times in 24-hour form, so the clocks do too.
 *
 * Moving the start of a range moves its end with it, keeping its length; the end step is where the
 * length itself changes.
 *
 * The picker hands back UTC midnight for the day that was tapped, so the conversion reads it in
 * UTC: reading it in the device's zone would land on the previous day for anyone west of Greenwich.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrgDatePickerDialog(
    initialDate: LocalDate,
    initialTime: LocalTime?,
    initialEndTime: LocalTime?,
    onPick: (date: LocalDate, time: LocalTime?, endTime: LocalTime?) -> Unit,
    onDismiss: () -> Unit,
) {
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
    )
    // A new time starts on the coming hour, the likeliest one for something planned today.
    val timeState = rememberTimePickerState(
        initialHour = initialTime?.hour ?: ((currentHour() + 1) % 24),
        initialMinute = initialTime?.minute ?: 0,
        is24Hour = true,
    )
    var step by remember { mutableStateOf(PickerStep.DATE) }
    val pickedDate = { dateState.selectedDateMillis?.toUtcDate() ?: initialDate }
    val pickedTime = { LocalTime(timeState.hour, timeState.minute) }
    // The end as it stands for the start now on the clock: the written one, moved along with it.
    val pickedEnd = { shiftedEnd(initialTime, initialEndTime, pickedTime()) }

    when (step) {
        PickerStep.DATE -> DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = { onPick(pickedDate(), initialTime, initialEndTime) }) { Text("OK") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { step = PickerStep.TIME }) {
                        Text(initialTime?.let { "At ${timeRangeLabel(it, initialEndTime)}" } ?: "Add time")
                    }
                }
            },
        ) {
            DatePicker(state = dateState)
        }

        PickerStep.TIME -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(pickedDate().toString()) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = { onPick(pickedDate(), pickedTime(), pickedEnd()) }) { Text("OK") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { step = PickerStep.DATE }) { Text("Back") }
                    if (initialTime != null) {
                        TextButton(onClick = { onPick(pickedDate(), null, null) }) { Text("No time") }
                    }
                    TextButton(onClick = { step = PickerStep.END }) {
                        Text(pickedEnd()?.let { "Until ${it.hhmm()}" } ?: "Add end")
                    }
                }
            },
        )

        PickerStep.END -> {
            val start = pickedTime()
            val initialEnd = pickedEnd() ?: hourAfter(start)
            // Remembered here rather than above, so that it opens on the end for the start just
            // picked rather than the one the dialog opened with; a new range starts an hour long.
            val endState = rememberTimePickerState(
                initialHour = initialEnd.hour,
                initialMinute = initialEnd.minute,
                is24Hour = true,
            )
            val end = LocalTime(endState.hour, endState.minute)
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("${pickedDate()}, ${start.hhmm()} until") },
                text = { TimePicker(state = endState) },
                confirmButton = {
                    // An end before its start is not a range; Org would not read it as one.
                    TextButton(enabled = end > start, onClick = { onPick(pickedDate(), start, end) }) {
                        Text("OK")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { step = PickerStep.TIME }) { Text("Back") }
                        if (pickedEnd() != null) {
                            TextButton(onClick = { onPick(pickedDate(), start, null) }) { Text("No end") }
                        }
                    }
                },
            )
        }
    }
}

private enum class PickerStep { DATE, TIME, END }

/** `10:00`, or `10:00–11:30` for a range. */
private fun timeRangeLabel(start: LocalTime, end: LocalTime?): String =
    if (end == null) start.hhmm() else "${start.hhmm()}–${end.hhmm()}"

/** An hour after [time], or the last minute of the day when that would be tomorrow. */
private fun hourAfter(time: LocalTime): LocalTime =
    if (time.hour < 23) LocalTime(time.hour + 1, time.minute) else LocalTime(23, 59)

private fun currentHour(): Int = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour

private fun Long.toUtcDate(): LocalDate =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC).date
