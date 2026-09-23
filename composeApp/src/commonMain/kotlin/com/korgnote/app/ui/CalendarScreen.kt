package com.korgnote.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaOccurrence
import com.korgnote.common.org.agenda.AgendaOptions
import kotlinx.coroutines.delay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

const val SLIDE_DURATION_MS = 100
const val FADE_DURATION_MS = 100

/**
 * The calendar: an agenda list, a day, a week or a month, all drawn from the same [agenda].
 *
 * [now] is a parameter so a test or a preview can say what time it is; the app leaves it to the
 * clock, read again every minute so the "now" line moves and today changes at midnight.
 */
@Composable
fun CalendarScreen(
    modifier: Modifier = Modifier,
    agenda: AgendaIndex = AgendaIndex.EMPTY,
    onOpenEntry: (AgendaEntry) -> Unit = {},
    initialMode: CalendarMode? = null,
    initialAnchorDate: LocalDate? = null,
    now: LocalDateTime? = null,
) {
    val clock by produceState(now ?: currentDateTime(), now) {
        if (now != null) return@produceState
        while (true) {
            delay(60_000)
            value = currentDateTime()
        }
    }
    val today = clock.date
    var anchorDate by remember { mutableStateOf(initialAnchorDate ?: today) }
    var mode by remember { mutableStateOf(initialMode ?: CalendarMode.Agenda) }
    var showDone by remember { mutableStateOf(true) }
    var slideDirection by remember { mutableStateOf(0) } // -1 for left (next), +1 for right (prev)

    fun show(newMode: CalendarMode, date: LocalDate = anchorDate) {
        slideDirection = 0
        mode = newMode
        anchorDate = date
    }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
        // Header with the period shown and navigation
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                Text(
                    text = mode.title(anchorDate),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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

        // Mode switcher, and whether done entries are shown
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            for (m in CalendarMode.entries) {
                FilterChip(
                    selected = mode == m,
                    onClick = { show(m) },
                    label = { Text(m.name) }
                )
            }
          }
          IconButton(onClick = { showDone = !showDone }) {
              Icon(
                  if (showDone) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                  contentDescription = if (showDone) "Hide done" else "Show done",
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
          }
        }

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
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
                val open = { occurrence: AgendaOccurrence -> onOpenEntry(occurrence.entry) }
                when (calendarMode) {
                    CalendarMode.Agenda -> AgendaListView(
                        agenda = agenda,
                        from = aDate,
                        today = today,
                        showDone = showDone,
                        onOpen = open,
                    )

                    CalendarMode.Day -> TimeGridView(
                        dates = listOf(aDate),
                        agenda = agenda,
                        now = clock,
                        showDone = showDone,
                        onOpen = open,
                        onDayClick = {},
                    )

                    CalendarMode.Week -> TimeGridView(
                        dates = (0 until 7).map { aDate.startOfWeek().plus(it, DateTimeUnit.DAY) },
                        agenda = agenda,
                        now = clock,
                        showDone = showDone,
                        onOpen = open,
                        onDayClick = { show(CalendarMode.Day, it) },
                    )

                    CalendarMode.Month -> MonthView(
                        anchorDate = aDate,
                        agenda = agenda,
                        today = today,
                        showDone = showDone,
                        onDayClick = { show(CalendarMode.Day, it) },
                    )
                }
            }
        }
    }
}

private fun currentDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

private val LocalDate.monthTitle get() = month.name.lowercase().replaceFirstChar { it.titlecase() }

/** What the header says is on screen. */
private fun CalendarMode.title(anchor: LocalDate): String = when (this) {
    CalendarMode.Month -> "${anchor.monthTitle} ${anchor.year}"
    CalendarMode.Day -> "${anchor.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.titlecase() }} ${anchor.day} ${anchor.monthTitle.take(3)} ${anchor.year}"
    CalendarMode.Week -> rangeTitle(anchor.startOfWeek(), anchor.startOfWeek().plus(6, DateTimeUnit.DAY))
    CalendarMode.Agenda -> rangeTitle(anchor, anchor.plus(AGENDA_LIST_DAYS - 1, DateTimeUnit.DAY))
}

private fun rangeTitle(first: LocalDate, last: LocalDate): String =
    if (first.month == last.month) "${first.day}–${last.day} ${first.monthTitle.take(3)} ${first.year}"
    else "${first.day} ${first.monthTitle.take(3)} – ${last.day} ${last.monthTitle.take(3)} ${last.year}"


// -----------------------
// Previews
// -----------------------

private fun previewSampleAgenda(): AgendaIndex = AgendaIndex.of(
    // Fixed sample data focused on September 2025
    mapOf(
        "sep-2025.org" to """
            * TODO Pay invoices
            SCHEDULED: <2025-09-10 Wed>
            * Dentist <2025-09-15 Mon 14:30-15:15>
            * Stand-up <2025-09-15 Mon 09:30-09:45 +1d>
            * Holidays
            <2025-09-19 Fri>--<2025-09-23 Tue>
            * TODO Quarterly report
            DEADLINE: <2025-09-18 Thu -5d>
        """.trimIndent(),
        "next-actions.org" to "* NEXT Plan the trip\nSCHEDULED: <2025-09-12 Fri>\n",
    ),
    AgendaOptions(today = LocalDate(2025, 9, 15)),
)

private val previewNow = LocalDateTime(2025, 9, 15, 11, 20)

@Preview
@Composable
private fun Preview_Calendar_Agenda() {
    CalendarScreen(agenda = previewSampleAgenda(), initialMode = CalendarMode.Agenda, now = previewNow)
}

@Preview
@Composable
private fun Preview_Calendar_Day() {
    CalendarScreen(agenda = previewSampleAgenda(), initialMode = CalendarMode.Day, now = previewNow)
}

@Preview
@Composable
private fun Preview_Calendar_Week() {
    CalendarScreen(agenda = previewSampleAgenda(), initialMode = CalendarMode.Week, now = previewNow)
}

@Preview
@Composable
private fun Preview_Calendar_Month() {
    CalendarScreen(agenda = previewSampleAgenda(), initialMode = CalendarMode.Month, now = previewNow)
}
