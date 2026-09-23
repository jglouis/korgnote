package com.korgnote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.tooling.preview.Preview
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaKind
import com.korgnote.common.org.agenda.AgendaOccurrence
import com.korgnote.common.org.agenda.AgendaOptions
import kotlin.time.Clock

const val SLIDE_DURATION_MS = 100
const val FADE_DURATION_MS = 100

@Composable
fun CalendarScreen(
    modifier: Modifier = Modifier,
    agenda: AgendaIndex = AgendaIndex.EMPTY,
    onOpenEntry: (AgendaEntry) -> Unit = {},
    initialMode: CalendarMode? = null,
    initialAnchorDate: LocalDate? = null,
    initialDialogDate: LocalDate? = null
) {
    modifier.background(MaterialTheme.colorScheme.surface)
    var today by remember {
        mutableStateOf(
            Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        )
    }
    var anchorDate by remember { mutableStateOf(initialAnchorDate ?: today) }
    var mode by remember { mutableStateOf(initialMode ?: CalendarMode.Month) }
    var slideDirection by remember { mutableStateOf(0) } // -1 for left (next), +1 for right (prev)

    Column(modifier = modifier.padding(8.dp)) {
        // Header with month/year and navigation
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                Text(
                    text = "${
                        anchorDate.month.name.lowercase().replaceFirstChar { it.titlecase() }
                    } ${anchorDate.year}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Today",
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clickable { slideDirection = 0; anchorDate = today },
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    Icons.Filled.ChevronLeft,
                    contentDescription = "Previous",
                    modifier = Modifier
                        .size(28.dp)
                        .clickable { slideDirection = 1; anchorDate = mode.prev(anchorDate) }
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "Next",
                    modifier = Modifier
                        .size(28.dp)
                        .clickable { slideDirection = -1; anchorDate = mode.next(anchorDate) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Mode switcher
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = mode == CalendarMode.Week,
                onClick = { slideDirection = 0; mode = CalendarMode.Week },
                label = { Text("Week") }
            )
            FilterChip(
                selected = mode == CalendarMode.Month,
                onClick = { slideDirection = 0; mode = CalendarMode.Month },
                label = { Text("Month") }
            )
        }

        Spacer(Modifier.height(8.dp))

        // Week day headers
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            for (d in days) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        d,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        var dialogDate by remember { mutableStateOf(initialDialogDate) }
        val dialogEntries = dialogDate?.let { agenda.calendarDay(it) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(mode, anchorDate) {
                    var triggered = false
                    var totalDx = 0f
                    val threshold = 60f
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dragAmount ->
                            if (triggered) return@detectHorizontalDragGestures
                            totalDx += dragAmount
                            if (totalDx <= -threshold) {
                                slideDirection = -1
                                anchorDate = mode.next(anchorDate)
                                triggered = true
                            } else if (totalDx >= threshold) {
                                slideDirection = 1
                                anchorDate = mode.prev(anchorDate)
                                triggered = true
                            }
                        },
                        onDragEnd = {
                            triggered = false
                            totalDx = 0f
                        },
                        onDragCancel = {
                            triggered = false
                            totalDx = 0f
                        }
                    )
                }
        ) {
            AnimatedContent(
                targetState = Pair(mode, anchorDate),
                transitionSpec = {
                    val dir = slideDirection
                    if (dir < 0) {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(SLIDE_DURATION_MS)
                        ) + fadeIn(animationSpec = tween(SLIDE_DURATION_MS)) togetherWith
                                slideOutOfContainer(
                                    AnimatedContentTransitionScope.SlideDirection.Left,
                                    animationSpec = tween(SLIDE_DURATION_MS)
                                ) + fadeOut(animationSpec = tween(SLIDE_DURATION_MS))
                    } else if (dir > 0) {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(SLIDE_DURATION_MS)
                        ) + fadeIn(animationSpec = tween(SLIDE_DURATION_MS)) togetherWith
                                slideOutOfContainer(
                                    AnimatedContentTransitionScope.SlideDirection.Right,
                                    animationSpec = tween(SLIDE_DURATION_MS)
                                ) + fadeOut(animationSpec = tween(SLIDE_DURATION_MS))
                    } else {
                        fadeIn(animationSpec = tween(FADE_DURATION_MS)) togetherWith fadeOut(
                            animationSpec = tween(FADE_DURATION_MS)
                        )
                    }
                }, label = "calendarPager"
            ) { (calendarMode, aDate) ->
                when (calendarMode) {
                    CalendarMode.Month -> MonthGrid(
                        anchorDate = aDate,
                        today = today,
                        hasEntries = { agenda.calendarDay(it).isNotEmpty() }
                    ) { date ->
                        if (agenda.calendarDay(date).isNotEmpty()) dialogDate = date
                    }

                    CalendarMode.Week -> WeekRow(
                        anchorDate = aDate,
                        today = today,
                        hasEntries = { agenda.calendarDay(it).isNotEmpty() }
                    ) { date ->
                        if (agenda.calendarDay(date).isNotEmpty()) dialogDate = date
                    }
                }
            }
        }

        // Dialog showing the entries dated on the selected day
        if (!dialogEntries.isNullOrEmpty() && dialogDate != null) {
            AlertDialog(
                onDismissRequest = { dialogDate = null },
                confirmButton = {
                    Text(
                        text = "Close",
                        modifier = Modifier.clickable { dialogDate = null },
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                title = { Text("$dialogDate") },
                text = {
                    Column {
                        dialogEntries.forEach { e ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onOpenEntry(e.entry)
                                        dialogDate = null
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Leading affordance dot
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            shape = CircleShape
                                        )
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    // The heading alone, rendered as the document renders it.
                                    OrgText(content = e.entry.asHeading())
                                    e.caption()?.let {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            )
        }
    }
}

enum class CalendarMode {
    Week, Month;

    fun next(date: LocalDate): LocalDate = when (this) {
        Week -> date.plus(7, DateTimeUnit.DAY)
        Month -> date.plus(1, DateTimeUnit.MONTH)
    }

    fun prev(date: LocalDate): LocalDate = when (this) {
        Week -> date.minus(7, DateTimeUnit.DAY)
        Month -> date.minus(1, DateTimeUnit.MONTH)
    }
}

@Composable
private fun WeekRow(
    anchorDate: LocalDate,
    today: LocalDate,
    hasEntries: (LocalDate) -> Boolean,
    onDateClick: (LocalDate) -> Unit
) {
    val start = anchorDate.startOfWeek()
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (i in 0 until 7) {
            val d = start.plus(i, DateTimeUnit.DAY)
            val has = hasEntries(d)
            Box(modifier = Modifier.weight(1f)) {
                DayCell(date = d, isToday = d == today, isOtherMonth = false, hasSchedule = has) {
                    if (has) onDateClick(d)
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(
    anchorDate: LocalDate,
    today: LocalDate,
    hasEntries: (LocalDate) -> Boolean,
    onDateClick: (LocalDate) -> Unit
) {
    val firstOfMonth = LocalDate(anchorDate.year, anchorDate.month, 1)
    val start = firstOfMonth.startOfWeek()
    Column(modifier = Modifier.fillMaxWidth()) {
        for (row in 0 until 6) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                for (col in 0 until 7) {
                    val idx = row * 7 + col
                    val date = start.plus(idx, DateTimeUnit.DAY)
                    val isOther = date.month != anchorDate.month
                    val hasSchedule = hasEntries(date)
                    Box(modifier = Modifier.weight(1f)) {
                        DayCell(
                            date = date,
                            isToday = date == today,
                            isOtherMonth = isOther,
                            hasSchedule = hasSchedule
                        ) {
                            if (hasSchedule) onDateClick(date)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isToday: Boolean,
    isOtherMonth: Boolean,
    hasSchedule: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val backgroundColor =
        if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent
    val textColor = when {
        isOtherMonth -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val clickableMod = if (hasSchedule) Modifier.clickable { onClick?.invoke() } else Modifier
    Column(
        modifier = Modifier
            .padding(2.dp)
            .then(clickableMod)
            .background(backgroundColor)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = date.day.toString(), color = textColor)
        if (hasSchedule) {
            Spacer(Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.primary, shape = CircleShape)
            )
        }
    }
}

private fun LocalDate.startOfWeek(): LocalDate {
    // Assume Monday as first day
    val dayIndex = (this.dayOfWeek.isoDayNumber - 1) // 0..6
    return this.minus(dayIndex, DateTimeUnit.DAY)
}


/**
 * What the month and week grids show for [date]: each entry on its own date only. A deadline's
 * advance warnings and overdue items brought forward to today belong to the agenda list (T17),
 * where they can be told apart; here they would only be more dots.
 */
private fun AgendaIndex.calendarDay(date: LocalDate): List<AgendaOccurrence> = on(date).filter { it.isOnOwnDate }

/** A heading-only section, so the dialog renders the entry exactly as the document does. */
private fun AgendaEntry.asHeading() = OrgSection(title = title, todo = todo, level = 1, tags = tags, priority = priority)

/** The time and kind, when there is anything to say beyond the date the dialog already shows. */
private fun AgendaOccurrence.caption(): String? {
    val time = time?.let { start -> endTime?.let { "$start–$it" } ?: "$start" }
    val kind = when (entry.kind) {
        AgendaKind.Deadline -> "Deadline"
        AgendaKind.Closed -> "Closed"
        AgendaKind.Scheduled, AgendaKind.Timestamp -> null
    }
    return listOfNotNull(kind, time).joinToString(" · ").ifEmpty { null }
}

// -----------------------
// Previews
// -----------------------

private fun previewSampleAgenda(): AgendaIndex = AgendaIndex.of(
    // Fixed sample data focused on September 2025
    mapOf(
        "sep-2025.org" to """
            * TODO Pay invoices
            SCHEDULED: <2025-09-10 Wed>
            * Dentist <2025-09-15 Mon 14:30>
            * TODO Quarterly report
            DEADLINE: <2025-09-30 Tue>
        """.trimIndent(),
        "next-actions.org" to "* NEXT Plan the trip\nSCHEDULED: <2025-09-21 Sun>\n",
    ),
    AgendaOptions(today = LocalDate(2025, 9, 15)),
)

@Preview
@Composable
private fun Preview_Calendar_Month_NoDialog() {
    CalendarScreen(
        agenda = previewSampleAgenda(),
        initialMode = CalendarMode.Month,
        initialAnchorDate = LocalDate(2025, 9, 15)
    )
}

@Preview
@Composable
private fun Preview_Calendar_Week_NoDialog() {
    CalendarScreen(
        agenda = previewSampleAgenda(),
        initialMode = CalendarMode.Week,
        initialAnchorDate = LocalDate(2025, 9, 15)
    )
}

@Preview
@Composable
private fun Preview_Calendar_Month_WithDialog() {
    val dateWithEntries = LocalDate(2025, 9, 10)
    CalendarScreen(
        agenda = previewSampleAgenda(),
        initialMode = CalendarMode.Month,
        initialAnchorDate = LocalDate(2025, 9, 1),
        initialDialogDate = dateWithEntries
    )
}

@Preview
@Composable
private fun Preview_Calendar_Week_WithDialog() {
    val dateWithEntries = LocalDate(2025, 9, 21)
    CalendarScreen(
        agenda = previewSampleAgenda(),
        initialMode = CalendarMode.Week,
        initialAnchorDate = LocalDate(2025, 9, 21),
        initialDialogDate = dateWithEntries
    )
}
