package com.korgnote.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.korgnote.app.viewmodel.ClockRunning
import com.korgnote.common.org.edit.formatClocked
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.cancel
import korgnote.composeapp.generated.resources.clock_cancel
import korgnote.composeapp.generated.resources.clock_left_running
import korgnote.composeapp.generated.resources.clock_open
import korgnote.composeapp.generated.resources.clock_out
import korgnote.composeapp.generated.resources.clock_out_at
import korgnote.composeapp.generated.resources.clock_out_now
import korgnote.composeapp.generated.resources.clock_running_count
import korgnote.composeapp.generated.resources.clock_running_for
import korgnote.composeapp.generated.resources.ok
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** How long a clock runs before the bar asks whether it was left on: a working day. */
const val CLOCK_LEFT_RUNNING_MINUTES = 8 * 60

/** Minutes [clock] has run at [now]; none for one that starts later, on a device whose clock runs ahead. */
fun ClockRunning.minutesAt(now: LocalDateTime): Int =
    ((now.toInstant(TimeZone.UTC) - start.toInstant(TimeZone.UTC)).inWholeMinutes).toInt().coerceAtLeast(0)

/**
 * The running clock, on every tab: the heading clocked, how long it has run, and a button to stop
 * it. A tap opens the heading. Several clocks running — two devices clocked in while apart — are
 * all listed from a tap, each to be opened or stopped.
 *
 * A clock running longer than [CLOCK_LEFT_RUNNING_MINUTES] turns the bar to a warning, and its
 * stop button asks when it should have stopped: the clock left on overnight.
 */
@Composable
fun RunningClockBar(
    clocks: List<ClockRunning>,
    now: LocalDateTime,
    onOpen: (ClockRunning) -> Unit,
    onClockOut: (ClockRunning, at: LocalDateTime?) -> Unit,
    onCancel: (ClockRunning) -> Unit,
    modifier: Modifier = Modifier,
) {
    val newest = clocks.firstOrNull() ?: return
    val minutes = newest.minutesAt(now)
    val leftRunning = minutes >= CLOCK_LEFT_RUNNING_MINUTES
    var listOpen by remember { mutableStateOf(false) }
    var stopMenuFor by remember { mutableStateOf<ClockRunning?>(null) }
    var pickingEndFor by remember { mutableStateOf<ClockRunning?>(null) }

    val container = if (leftRunning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (leftRunning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = container, contentColor = content, modifier = modifier.fillMaxWidth()) {
        Box {
            Row(
                modifier = Modifier
                    .clickable { if (clocks.size > 1) listOpen = true else onOpen(newest) }
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Timer, contentDescription = null, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(
                        plainHeadingTitle(newest.title),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val elapsed = formatClocked(minutes)
                    val status = if (leftRunning) stringResource(Res.string.clock_left_running, elapsed) else stringResource(Res.string.clock_running_for, elapsed)
                    val more = if (clocks.size > 1) " · " + pluralStringResource(Res.plurals.clock_running_count, clocks.size, clocks.size) else ""
                    Text(status + more, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { if (leftRunning) stopMenuFor = newest else onClockOut(newest, null) }) {
                        Icon(Icons.Filled.Stop, contentDescription = stringResource(Res.string.clock_out))
                    }
                    StopMenu(
                        clock = stopMenuFor?.takeIf { it == newest },
                        onDismiss = { stopMenuFor = null },
                        onClockOut = onClockOut,
                        onPickEnd = { pickingEndFor = it },
                        onCancel = onCancel,
                    )
                }
            }
            // Every clock, newest first, each to open or stop.
            DropdownMenu(expanded = listOpen, onDismissRequest = { listOpen = false }) {
                clocks.forEachIndexed { i, clock ->
                    if (i > 0) HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("${plainHeadingTitle(clock.title)}  ${formatClocked(clock.minutesAt(now))}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { listOpen = false; onOpen(clock) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.clock_out)) },
                        leadingIcon = { Icon(Icons.Filled.Stop, contentDescription = null) },
                        onClick = { listOpen = false; onClockOut(clock, null) },
                    )
                }
            }
        }
    }

    pickingEndFor?.let { clock ->
        ClockEndDialog(
            clock = clock,
            now = now,
            onPick = { at -> pickingEndFor = null; onClockOut(clock, at) },
            onDismiss = { pickingEndFor = null },
        )
    }
}

/** What stopping a clock left running offers: now, at a time of day, or not at all. */
@Composable
private fun StopMenu(
    clock: ClockRunning?,
    onDismiss: () -> Unit,
    onClockOut: (ClockRunning, LocalDateTime?) -> Unit,
    onPickEnd: (ClockRunning) -> Unit,
    onCancel: (ClockRunning) -> Unit,
) {
    DropdownMenu(expanded = clock != null, onDismissRequest = onDismiss) {
        if (clock == null) return@DropdownMenu
        DropdownMenuItem(text = { Text(stringResource(Res.string.clock_out_now)) }, onClick = { onDismiss(); onClockOut(clock, null) })
        DropdownMenuItem(text = { Text(stringResource(Res.string.clock_out_at)) }, onClick = { onDismiss(); onPickEnd(clock) })
        DropdownMenuItem(text = { Text(stringResource(Res.string.clock_cancel)) }, onClick = { onDismiss(); onCancel(clock) })
    }
}

/**
 * When a clock left running should have stopped: a time of day, taken as its latest moment between
 * the clock's start and now — last night's 18:00, not tonight's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockEndDialog(clock: ClockRunning, now: LocalDateTime, onPick: (LocalDateTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = clock.start.hour, initialMinute = clock.start.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.clock_out_at)) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onPick(clockEndAt(clock.start, now, LocalTime(state.hour, state.minute))) }) { Text(stringResource(Res.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** The latest moment at [time] of day that is not after [now], and not before [start]. */
internal fun clockEndAt(start: LocalDateTime, now: LocalDateTime, time: LocalTime): LocalDateTime {
    var at = LocalDateTime(now.date, time)
    if (at > now) at = LocalDateTime(now.date.minus(1, DateTimeUnit.DAY), time)
    return if (at < start) start else at
}

/** A heading's title as it reads: its links' descriptions, without the brackets around them. */
private fun plainHeadingTitle(title: String): String = buildOrgInlineString(title, viewMode = true).text
