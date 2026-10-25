package com.korgnote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaKind
import com.korgnote.common.org.agenda.AgendaOccurrence
import com.korgnote.common.org.agenda.DaySpan
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus

// --- How each kind looks -----------------------------------------------------------------------

/**
 * One colour per kind of date, used for the bar, the chip and the label alike, so that a deadline
 * reads as a deadline in every view. The icon says the same thing to anyone who cannot tell the
 * colours apart.
 */
@Composable
fun AgendaKind.accent(): Color = when (this) {
    AgendaKind.Deadline -> MaterialTheme.colorScheme.error
    AgendaKind.Scheduled -> MaterialTheme.colorScheme.primary
    AgendaKind.Timestamp -> MaterialTheme.colorScheme.tertiary
    AgendaKind.Closed -> MaterialTheme.colorScheme.outline
}

fun AgendaKind.icon(): ImageVector = when (this) {
    AgendaKind.Deadline -> Icons.Filled.Flag
    AgendaKind.Scheduled -> Icons.Filled.Schedule
    AgendaKind.Timestamp -> Icons.Filled.Event
    AgendaKind.Closed -> Icons.Filled.CheckCircle
}

/**
 * The order entries are stacked in a cell or the all-day strip: multi-day ranges first, in the same
 * order on every day so each keeps its row and reads as one bar; then what matters most when there
 * is room for only a few — deadlines, then scheduled work, then appointments — and then by time.
 */
private val KIND_ORDER = listOf(AgendaKind.Deadline, AgendaKind.Scheduled, AgendaKind.Timestamp, AgendaKind.Closed)

private val chipOrder = compareBy<AgendaOccurrence>(
    { it.span == DaySpan.Single },
    { if (it.span != DaySpan.Single) it.entry.stamp.start.toEpochDays() else 0L },
    { KIND_ORDER.indexOf(it.entry.kind) },
    { it.time?.let { t -> t.hour * 60 + t.minute } ?: -1 },
    { it.entry.file },
    { it.entry.stamp.range.first },
)

/** Done entries stay visible but step back. */
private fun Modifier.dimmedIfDone(occurrence: AgendaOccurrence) = if (occurrence.entry.done) alpha(0.45f) else this

/**
 * `TODO Title`, as the heading reads, with the priority cookie when there is one.
 *
 * The title goes through the same inline rendering as reading mode, so a link shows as its
 * description and emphasis as emphasis rather than as markup. The links themselves are left out:
 * tapping an entry opens it, and a link inside it must not take the tap.
 */
private fun AgendaOccurrence.headline(prefix: String? = null): AnnotatedString = buildAnnotatedString {
    val keywords = listOfNotNull(prefix, entry.todo, entry.priority?.let { "[#$it]" }).joinToString(" ")
    if (keywords.isNotEmpty()) append("$keywords ")
    val title = buildOrgInlineString(entry.title, viewMode = true)
    append(AnnotatedString(title.text, title.spanStyles))
}

private val LocalDate.shortWeekday get() = dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.titlecase() }
private val LocalDate.longWeekday get() = dayOfWeek.name.lowercase().replaceFirstChar { it.titlecase() }
private val LocalDate.monthName get() = month.name.lowercase().replaceFirstChar { it.titlecase() }

// --- Agenda list -------------------------------------------------------------------------------

/**
 * The `org-agenda` view: what is overdue, then a week of days with what is on each.
 */
@Composable
fun AgendaListView(
    agenda: AgendaIndex,
    from: LocalDate,
    today: LocalDate,
    showDone: Boolean,
    onOpen: (AgendaOccurrence) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = remember(agenda, from, today, showDone) { agenda.agendaList(from, AGENDA_LIST_DAYS, today, showDone) }
    LazyColumn(modifier = modifier.fillMaxSize().testTag("agendaList")) {
        for (section in sections) {
            item(key = "header-${section.date}") {
                val date = section.date
                val isToday = date == today
                Text(
                    text = when {
                        date == null -> "Overdue"
                        else -> "${date.longWeekday} ${date.day} ${date.monthName}" + if (isToday) " · Today" else ""
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isToday || date == null) FontWeight.Bold else FontWeight.SemiBold,
                    color = when {
                        date == null -> MaterialTheme.colorScheme.error
                        isToday -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp),
                )
            }
            if (section.occurrences.isEmpty()) {
                item(key = "empty-${section.date}") {
                    Text(
                        "Nothing planned",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
            }
            // Room for a time only on a day that has one: a column of nothing but icons would push
            // every title a third of the way across.
            val leading = if (section.occurrences.any { it.timeLabel() != null }) 88.dp else 24.dp
            items(section.occurrences, key = { "${section.date}-${it.entry.file}-${it.entry.stamp.range.first}-${it.date}" }) {
                AgendaRow(it, today, leading, onOpen = { onOpen(it) })
            }
        }
    }
}

@Composable
private fun AgendaRow(occurrence: AgendaOccurrence, today: LocalDate, leading: Dp, onOpen: () -> Unit) {
    val accent = occurrence.entry.kind.accent()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .dimmedIfDone(occurrence)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(36.dp).background(accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(leading)) {
            val time = occurrence.timeLabel()
            if (time != null) {
                Text(time, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
            } else {
                Icon(occurrence.entry.kind.icon(), contentDescription = occurrence.entry.kind.name, tint = accent, modifier = Modifier.size(16.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            occurrence.statusLabel(today)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.SemiBold)
            }
            Text(
                occurrence.headline(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (occurrence.entry.tags.isNotEmpty()) {
                TagChips(occurrence.entry.tags, Modifier.padding(top = 2.dp), maxLines = 1)
            }
        }
    }
}

// --- Day and week: all-day strip and hour grid -------------------------------------------------

private val HOUR_HEIGHT = 48.dp
private val GUTTER_WIDTH = 44.dp

/**
 * One or more days side by side: a header per day when there is more than one, an all-day strip,
 * and an hour grid with timed entries as blocks and a line at the current time.
 *
 * The grid opens scrolled to an hour before now, or to [dayStartHour] on other days, rather than at
 * midnight, which is rarely where anything is.
 */
@Composable
fun TimeGridView(
    dates: List<LocalDate>,
    agenda: AgendaIndex,
    now: LocalDateTime,
    showDone: Boolean,
    onOpen: (AgendaOccurrence) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    dayStartHour: Int = 7,
) {
    val today = now.date
    val byDate = remember(agenda, dates, today, showDone) { dates.associateWith { agenda.shownOn(it, today, showDone) } }
    Column(modifier.fillMaxSize()) {
        if (dates.size > 1) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Where the columns are wide enough, as on a phone on its side, the weekday goes
                // beside the date: there the height is better spent on the hours.
                val inline = (maxWidth - GUTTER_WIDTH) / dates.size >= 72.dp
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(GUTTER_WIDTH))
                    for (date in dates) {
                        val modifier = Modifier.weight(1f).clickable { onDayClick(date) }.padding(vertical = 4.dp)
                        val weekday = @Composable {
                            Text(date.shortWeekday, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        if (inline) {
                            Row(
                                modifier,
                                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                weekday()
                                DayNumber(date, isToday = date == today)
                            }
                        } else {
                            Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
                                weekday()
                                DayNumber(date, isToday = date == today)
                            }
                        }
                    }
                }
            }
        }

        // All-day strip: untimed entries, deadlines, and the days of multi-day ranges.
        // Multi-day ranges first, in the same order on every day, so that each one keeps its row
        // and reads as one bar across the columns.
        val allDay = dates.map { date ->
            byDate.getValue(date).filter { it.isAllDay }.sortedWith(chipOrder)
        }
        val compact = dates.size > 1
        if (allDay.any { it.isNotEmpty() }) {
            val limit = if (dates.size > 1) 3 else Int.MAX_VALUE
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    "all day",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.width(GUTTER_WIDTH).padding(top = 2.dp),
                )
                dates.forEachIndexed { i, date ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        allDay[i].take(limit).forEach {
                            EventChip(it, today, onOpen = { onOpen(it) }, compact = compact, firstShown = i == 0)
                        }
                        val hidden = allDay[i].size - limit
                        if (hidden > 0) {
                            Text(
                                "+$hidden",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.clickable { onDayClick(date) }.padding(horizontal = 2.dp),
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
        }

        // Opens an hour before now on today, or at the day's start, but never below the first
        // timed entry on screen: an early meeting scrolled out of sight is a meeting missed.
        val firstEvent = dates.flatMap { byDate.getValue(it) }.filter { !it.isAllDay }.minOfOrNull { it.time!!.hour }
        val preferred = if (today in dates) (now.hour - 1).coerceAtLeast(0) else dayStartHour
        val initialHour = minOf(preferred, firstEvent ?: preferred)
        // A little above the hour, so its label is not cut in half.
        val initialScroll = with(LocalDensity.current) { (HOUR_HEIGHT * initialHour - 8.dp).coerceAtLeast(0.dp).roundToPx() }
        val scroll = rememberScrollState(initialScroll)
        Row(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).testTag("hourGrid")) {
            Column(Modifier.width(GUTTER_WIDTH)) {
                for (hour in 0 until 24) {
                    Box(Modifier.height(HOUR_HEIGHT)) {
                        if (hour > 0) {
                            Text(
                                "${hour.toString().padStart(2, '0')}:00",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.offset(y = (-7).dp),
                            )
                        }
                    }
                }
            }
            for (date in dates) {
                DayColumn(
                    blocks = remember(byDate, date) { layoutTimed(byDate.getValue(date)) },
                    nowMinute = if (date == today) now.hour * 60 + now.minute else null,
                    onOpen = onOpen,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DayColumn(
    blocks: List<TimedBlock>,
    nowMinute: Int?,
    onOpen: (AgendaOccurrence) -> Unit,
    modifier: Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(modifier.height(HOUR_HEIGHT * 24)) {
        // Hour lines.
        for (hour in 0 until 24) {
            HorizontalDivider(Modifier.offset(y = HOUR_HEIGHT * hour), color = lineColor.copy(alpha = 0.5f))
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(lineColor.copy(alpha = 0.5f)))

        for (block in blocks) {
            val accent = block.occurrence.entry.kind.accent()
            val width = maxWidth / block.columns
            Column(
                Modifier
                    .offset(x = width * block.column, y = HOUR_HEIGHT * block.startMinute / 60)
                    .width(width)
                    .height(HOUR_HEIGHT * (block.endMinute - block.startMinute) / 60)
                    .padding(1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    // Opaque, so the hour lines do not run through the text.
                    .background(MaterialTheme.colorScheme.surface)
                    .background(accent.copy(alpha = 0.18f))
                    .clickable { onOpen(block.occurrence) }
                    .dimmedIfDone(block.occurrence)
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            ) {
                Text(
                    block.occurrence.headline(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                block.occurrence.timeLabel()?.let {
                    Text(it, fontSize = 9.sp, color = accent, maxLines = 1)
                }
            }
        }

        if (nowMinute != null) {
            val nowColor = MaterialTheme.colorScheme.error
            Box(Modifier.fillMaxWidth().offset(y = HOUR_HEIGHT * nowMinute / 60 - 1.dp).testTag("nowLine")) {
                Box(Modifier.fillMaxWidth().height(2.dp).offset(y = 3.dp).background(nowColor))
                Box(Modifier.size(8.dp).background(nowColor, CircleShape))
            }
        }
    }
}

/**
 * An entry in the all-day strip or a month cell: its colour, and as much of its title as fits.
 *
 * A day of a multi-day range is squared off on the sides where the range continues and carries its
 * title only where it starts — or on [firstShown], the first column on screen — so that the days
 * read as one bar.
 */
@Composable
private fun EventChip(
    occurrence: AgendaOccurrence,
    today: LocalDate,
    onOpen: () -> Unit,
    compact: Boolean = false,
    firstShown: Boolean = true,
) {
    val accent = occurrence.entry.kind.accent()
    val status = occurrence.statusLabel(today)
    val r = 3.dp
    val (shape, inset) = when (occurrence.span) {
        DaySpan.Single -> RoundedCornerShape(r) to PaddingValues(horizontal = 1.dp)
        DaySpan.Start -> RoundedCornerShape(topStart = r, bottomStart = r) to PaddingValues(start = 1.dp)
        DaySpan.Middle -> RectangleShape to PaddingValues()
        DaySpan.End -> RoundedCornerShape(topEnd = r, bottomEnd = r) to PaddingValues(end = 1.dp)
    }
    val showTitle = occurrence.span == DaySpan.Single || occurrence.span == DaySpan.Start || firstShown
    Row(
        Modifier
            .fillMaxWidth()
            .padding(inset)
            .clip(shape)
            .background(accent.copy(alpha = 0.18f))
            .clickable(onClick = onOpen)
            .dimmedIfDone(occurrence)
            .padding(horizontal = 3.dp, vertical = if (compact) 0.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!compact) {
            Icon(occurrence.entry.kind.icon(), contentDescription = null, tint = accent, modifier = Modifier.size(10.dp))
            Spacer(Modifier.width(2.dp))
        }
        Text(
            text = when {
                !showTitle -> AnnotatedString(" ")
                !compact && status != null && occurrence.entry.kind == AgendaKind.Deadline -> occurrence.headline(prefix = "$status:")
                else -> occurrence.headline()
            },
            fontSize = if (compact) 9.sp else 11.sp,
            lineHeight = if (compact) 11.sp else 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DayNumber(date: LocalDate, isToday: Boolean, dim: Boolean = false) {
    Box(
        Modifier
            .size(24.dp)
            .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            date.day.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = when {
                isToday -> MaterialTheme.colorScheme.onPrimary
                dim -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

// --- Month -------------------------------------------------------------------------------------

/**
 * The month grid, with a chip per entry on each day — coloured by kind, done ones faded — as many
 * as fit and a count of the rest. Tapping a day opens it in the day view.
 */
@Composable
fun MonthView(
    anchorDate: LocalDate,
    agenda: AgendaIndex,
    today: LocalDate,
    showDone: Boolean,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstOfMonth = LocalDate(anchorDate.year, anchorDate.month, 1)
    val start = firstOfMonth.startOfWeek()
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            for (d in listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(d, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        // The six weeks share the height, but a week too short to show an entry under its day
        // numbers is no use, as on a phone on its side: there the month scrolls instead.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val rowHeight = maxOf(maxHeight / 6, MONTH_ROW_MIN_HEIGHT)
            // A compact chip or the "+n" line under the day number, with the gap above it.
            val line = with(LocalDensity.current) { 11.sp.toDp() } + 1.dp
            val lines = ((rowHeight - 26.dp) / line).toInt().coerceIn(1, 3)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                for (row in 0 until 6) {
                    Row(Modifier.fillMaxWidth().height(rowHeight)) {
                        for (col in 0 until 7) {
                            val date = start.plus(row * 7 + col, DateTimeUnit.DAY)
                            val shown = agenda.monthDay(date, showDone).sortedWith(chipOrder)
                            Column(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(vertical = 1.dp)
                                    .clickable { onDayClick(date) }
                                    .testTag("monthDay-$date"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                DayNumber(date, isToday = date == today, dim = date.month != anchorDate.month)
                                // The count of the rest takes a line too, unless there are none.
                                val fits = if (shown.size > lines) lines - 1 else lines
                                shown.take(fits).forEach {
                                    Spacer(Modifier.height(1.dp))
                                    EventChip(it, today, onOpen = { onDayClick(date) }, compact = true, firstShown = col == 0)
                                }
                                if (shown.size > fits) {
                                    Text("+${shown.size - fits}", fontSize = 9.sp, lineHeight = 11.sp, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val MONTH_ROW_MIN_HEIGHT = 52.dp
