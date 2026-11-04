package com.korgnote.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.korgnote.common.org.complete.shiftedEnd
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.back
import korgnote.composeapp.generated.resources.cancel
import korgnote.composeapp.generated.resources.editor_add_end
import korgnote.composeapp.generated.resources.editor_add_time
import korgnote.composeapp.generated.resources.editor_at_time
import korgnote.composeapp.generated.resources.editor_no_end
import korgnote.composeapp.generated.resources.editor_no_time
import korgnote.composeapp.generated.resources.editor_time_until
import korgnote.composeapp.generated.resources.editor_until_time
import korgnote.composeapp.generated.resources.ok
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

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
                TextButton(onClick = { onPick(pickedDate(), initialTime, initialEndTime) }) { Text(stringResource(Res.string.ok)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
                    TextButton(onClick = { step = PickerStep.TIME }) {
                        Text(initialTime?.let { stringResource(Res.string.editor_at_time, timeRangeLabel(it, initialEndTime)) } ?: stringResource(Res.string.editor_add_time))
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
                TextButton(onClick = { onPick(pickedDate(), pickedTime(), pickedEnd()) }) { Text(stringResource(Res.string.ok)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { step = PickerStep.DATE }) { Text(stringResource(Res.string.back)) }
                    if (initialTime != null) {
                        TextButton(onClick = { onPick(pickedDate(), null, null) }) { Text(stringResource(Res.string.editor_no_time)) }
                    }
                    TextButton(onClick = { step = PickerStep.END }) {
                        Text(pickedEnd()?.let { stringResource(Res.string.editor_until_time, it.hhmm()) } ?: stringResource(Res.string.editor_add_end))
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
                title = { Text(stringResource(Res.string.editor_time_until, pickedDate().toString(), start.hhmm())) },
                text = { TimePicker(state = endState) },
                confirmButton = {
                    // An end before its start is not a range; Org would not read it as one.
                    TextButton(enabled = end > start, onClick = { onPick(pickedDate(), start, end) }) {
                        Text(stringResource(Res.string.ok))
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { step = PickerStep.TIME }) { Text(stringResource(Res.string.back)) }
                        if (pickedEnd() != null) {
                            TextButton(onClick = { onPick(pickedDate(), start, null) }) { Text(stringResource(Res.string.editor_no_end)) }
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

internal fun Long.toUtcDate(): LocalDate =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC).date
