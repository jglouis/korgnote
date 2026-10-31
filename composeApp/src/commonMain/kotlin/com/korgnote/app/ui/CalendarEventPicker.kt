package com.korgnote.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.korgnote.app.capture.DeviceCalendar
import com.korgnote.app.capture.DeviceEvent
import com.korgnote.common.org.capture.CalendarEvent
import com.korgnote.common.org.capture.EventTime
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.cancel
import korgnote.composeapp.generated.resources.capture_all_day
import korgnote.composeapp.generated.resources.capture_calendar_allow
import korgnote.composeapp.generated.resources.capture_calendar_allow_text
import korgnote.composeapp.generated.resources.capture_calendar_none
import korgnote.composeapp.generated.resources.capture_calendar_search
import korgnote.composeapp.generated.resources.capture_pick_event
import korgnote.composeapp.generated.resources.capture_reading_calendar
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/**
 * The phone's calendar, the past week and the next two months, to pick an event to capture: what
 * Google Calendar cannot share. Asks for the calendar first when it may not be read yet.
 */
@Composable
fun CalendarEventPicker(
    calendar: DeviceCalendar,
    permission: PermissionAsk,
    onPick: (CalendarEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    var granted by remember { mutableStateOf(permission.granted) }
    var events by remember { mutableStateOf<List<DeviceEvent>?>(null) }
    var query by remember { mutableStateOf("") }
    // Bumped to read the calendar again, once it is allowed.
    var reads by remember { mutableIntStateOf(0) }
    val today = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date }

    LaunchedEffect(granted, reads) {
        if (granted) events = calendar.events(today.minus(DatePeriod(days = PAST_DAYS)), PAST_DAYS + COMING_DAYS)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.capture_pick_event)) },
        text = {
            Column {
                if (!granted) {
                    Text(stringResource(Res.string.capture_calendar_allow_text), style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = {
                            permission.request { ok ->
                                granted = ok
                                reads++
                            }
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text(stringResource(Res.string.capture_calendar_allow)) }
                    return@Column
                }
                val all = events
                if (all == null) {
                    Text(stringResource(Res.string.capture_reading_calendar), style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(Res.string.capture_calendar_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val shown = all.filter { each ->
                    val haystack = "${each.event.summary} ${each.event.location.orEmpty()} ${each.calendar}".lowercase()
                    words.all { it in haystack }
                }
                if (shown.isEmpty()) {
                    Text(
                        stringResource(Res.string.capture_calendar_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                var lastDay: LocalDate? = null
                val rows = shown.map { each ->
                    val day = dayOf(each.event.start)
                    val header = day.takeIf { it != lastDay }
                    lastDay = day
                    header to each
                }
                // Opened at today: the past week is there, above, for the meeting just had.
                val list = rememberLazyListState()
                LaunchedEffect(rows.size, query) {
                    val first = rows.indexOfFirst { (_, each) -> dayOf(each.event.start) >= today }
                    if (first > 0 && query.isBlank()) list.scrollToItem(first)
                }
                // Short enough that the buttons stay above a phone's keyboard, as in the link picker.
                LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 8.dp), state = list) {
                    items(rows) { (header, each) ->
                        if (header != null) DayHeader(header, today)
                        EventRow(each, onClick = { onPick(each.event) })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun DayHeader(day: LocalDate, today: LocalDate) {
    val name = "${day.dayOfWeek.displayName().capitalised()} ${day.day} ${day.month.shortName()}"
    Text(
        name,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
        color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun EventRow(each: DeviceEvent, onClick: () -> Unit) {
    val event = each.event
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(
            timeOf(event) ?: stringResource(Res.string.capture_all_day),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(88.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(event.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val under = listOfNotNull(event.location, each.calendar.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (under.isNotEmpty()) {
                Text(under, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun dayOf(time: EventTime): LocalDate = when (time) {
    is EventTime.AllDay -> time.date
    is EventTime.At -> time.time.date
}

/** `14:00–15:30`, or null for an event that lasts all day. */
private fun timeOf(event: CalendarEvent): String? {
    val start = (event.start as? EventTime.At)?.time ?: return null
    fun hm(hour: Int, minute: Int) = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    val end = (event.end as? EventTime.At)?.time?.takeIf { it.date == start.date && it > start }
    return hm(start.hour, start.minute) + (end?.let { "–" + hm(it.hour, it.minute) } ?: "")
}

/** How far back the picker looks: a meeting just had is often what gets written down. */
private const val PAST_DAYS = 7

/** How far ahead it looks. */
private const val COMING_DAYS = 60
