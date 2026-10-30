package com.korgnote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.korgnote.app.widgets.CalendarTarget
import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaOccurrence
import com.korgnote.common.org.agenda.AgendaOptions
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.cal_hide_done
import korgnote.composeapp.generated.resources.cal_mode_agenda
import korgnote.composeapp.generated.resources.cal_mode_day
import korgnote.composeapp.generated.resources.cal_mode_month
import korgnote.composeapp.generated.resources.cal_mode_week
import korgnote.composeapp.generated.resources.cal_next
import korgnote.composeapp.generated.resources.cal_previous
import korgnote.composeapp.generated.resources.cal_show_done
import korgnote.composeapp.generated.resources.cal_today
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import org.jetbrains.compose.resources.stringResource


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
    headerActions: @Composable () -> Unit = {},
) {
    val clock by produceState(now ?: currentDateTime(), now) {
        if (now != null) return@produceState
        while (true) {
            delay(1.minutes)
            value = currentDateTime()
        }
    }
    val today = clock.date
    // Saved, as is the tab: turning the phone recreates the activity, and turning it to see more of
    // the calendar should not lose the place in it.
    var mode by rememberSaveable { mutableStateOf(initialMode ?: CalendarMode.Agenda) }
    // The period on the pager's middle page. Pages either side are the periods before and after it,
    // so a swipe moves under the finger and settles on whichever page it is let go nearest, as any
    // pager does; this only moves when the mode changes or Today jumps somewhere far.
    var base by rememberSaveable(stateSaver = LocalDateSaver) { mutableStateOf(initialAnchorDate ?: today) }
    // Finished entries are history, and a year of them buries what is still to do.
    var showDone by rememberSaveable { mutableStateOf(false) }
    // How far the hours are pinched, kept from one day or week to the next and between the two.
    var hourHeight by rememberSaveable { mutableStateOf(DEFAULT_HOUR_HEIGHT.value) }
    val pager = key(mode, base) { rememberPagerState(initialPage = CALENDAR_MIDDLE_PAGE) { CALENDAR_PAGES } }
    val scope = rememberCoroutineScope()
    fun dateOf(page: Int) = mode.shift(base, page - CALENDAR_MIDDLE_PAGE)
    val anchorDate = dateOf(pager.currentPage)

    fun show(newMode: CalendarMode, date: LocalDate = anchorDate) {
        mode = newMode
        base = date
    }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
        CalendarHeader(
            title = mode.title(anchorDate),
            mode = mode,
            showDone = showDone,
            onToday = {
                // Slid to when today is a page away, or back to the middle; jumped to otherwise.
                val near = (CALENDAR_MIDDLE_PAGE - 1..CALENDAR_MIDDLE_PAGE + 1).firstOrNull { page ->
                    val first = dateOf(page)
                    today >= first && today < mode.next(first)
                }
                when {
                    base == today -> scope.launch { pager.animateScrollToPage(CALENDAR_MIDDLE_PAGE) }
                    near != null -> scope.launch { pager.animateScrollToPage(near) }
                    else -> base = today
                }
            },
            onPrevious = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
            onNext = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            onMode = { show(it) },
            onToggleDone = { showDone = !showDone },
            actions = headerActions,
        )

        Spacer(Modifier.height(8.dp))

        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.Top,
        ) { page ->
            val calendarMode = mode
            val aDate = dateOf(page)
            Box(Modifier.fillMaxSize()) {
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
                        hourHeight = hourHeight.dp,
                        onHourHeightChange = { hourHeight = it.value },
                    )

                    CalendarMode.Week -> TimeGridView(
                        dates = (0 until 7).map { aDate.startOfWeek().plus(it, DateTimeUnit.DAY) },
                        agenda = agenda,
                        now = clock,
                        showDone = showDone,
                        onOpen = open,
                        onDayClick = { show(CalendarMode.Day, it) },
                        hourHeight = hourHeight.dp,
                        onHourHeightChange = { hourHeight = it.value },
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

/** Pages either way of the middle one: further than anyone swipes, so the pager never seems to end. */
private const val CALENDAR_PAGES = 20_001
private const val CALENDAR_MIDDLE_PAGE = CALENDAR_PAGES / 2

/**
 * The period shown and the way through it, the mode switcher, and whether done entries show. Two
 * rows on a phone held upright; one where the width allows, as on a phone on its side, where height
 * is what the calendar is short of. [actions] end the first row.
 */
@Composable
private fun CalendarHeader(
    title: String,
    mode: CalendarMode,
    showDone: Boolean,
    onToday: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onMode: (CalendarMode) -> Unit,
    onToggleDone: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val period: @Composable (Modifier) -> Unit = { modifier ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
            Icon(Icons.Filled.CalendarMonth, contentDescription = null)
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
    val navigation = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.cal_today),
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clickable(onClick = onToday),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Icon(
                Icons.Filled.ChevronLeft,
                contentDescription = stringResource(Res.string.cal_previous),
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onPrevious)
            )
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = stringResource(Res.string.cal_next),
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onNext)
            )
        }
    }
    val modes: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in CalendarMode.entries) {
                FilterChip(
                    selected = mode == m,
                    onClick = { onMode(m) },
                    label = { Text(m.label()) }
                )
            }
        }
    }
    val doneToggle = @Composable {
        IconButton(onClick = onToggleDone) {
            Icon(
                if (showDone) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = stringResource(if (showDone) Res.string.cal_hide_done else Res.string.cal_show_done),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                period(Modifier.weight(1f))
                modes(Modifier.padding(horizontal = 8.dp))
                doneToggle()
                navigation()
                actions()
            }
        } else {
            // The toggle sits up with the navigation, so that the modes have the second row to
            // themselves and fit a phone held upright.
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    period(Modifier.weight(1f))
                    doneToggle()
                    navigation()
                    actions()
                }
                Spacer(Modifier.height(8.dp))
                modes(Modifier.fillMaxWidth())
            }
        }
    }
}

/** A date kept across the activity being recreated: what is saved is its text. */
internal val LocalDateSaver = Saver<LocalDate, String>(save = { it.toString() }, restore = { LocalDate.parse(it) })
/** Where the calendar was asked to open, kept the same way: `Month 2026-09-01`. */
internal val OptionalCalendarTargetSaver = Saver<CalendarTarget?, String>(
    save = { it?.let { target -> "${target.mode.name} ${target.date}" } },
    restore = { it.split(' ').let { (mode, date) -> CalendarTarget(CalendarMode.valueOf(mode), LocalDate.parse(date)) } },
)

private fun currentDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

@Composable
private fun CalendarMode.label(): String = stringResource(
    when (this) {
        CalendarMode.Agenda -> Res.string.cal_mode_agenda
        CalendarMode.Day -> Res.string.cal_mode_day
        CalendarMode.Week -> Res.string.cal_mode_week
        CalendarMode.Month -> Res.string.cal_mode_month
    },
)

/** What the header says is on screen. */
@Composable
private fun CalendarMode.title(anchor: LocalDate): String = when (this) {
    CalendarMode.Month -> "${anchor.month.displayName().capitalised()} ${anchor.year}"
    CalendarMode.Day -> "${anchor.dayOfWeek.shortName().capitalised()} ${anchor.day} ${anchor.month.shortName()} ${anchor.year}"
    CalendarMode.Week -> rangeTitle(anchor.startOfWeek(), anchor.startOfWeek().plus(6, DateTimeUnit.DAY))
    CalendarMode.Agenda -> rangeTitle(anchor, anchor.plus(AGENDA_LIST_DAYS - 1, DateTimeUnit.DAY))
}

@Composable
private fun rangeTitle(first: LocalDate, last: LocalDate): String =
    if (first.month == last.month) "${first.day}–${last.day} ${first.month.shortName()} ${first.year}"
    else "${first.day} ${first.month.shortName()} – ${last.day} ${last.month.shortName()} ${last.year}"


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
