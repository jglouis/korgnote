@file:OptIn(ExperimentalFoundationApi::class)

package com.korgnote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaKind
import com.korgnote.common.org.agenda.AgendaOccurrence
import com.korgnote.common.org.agenda.ClockedTotal
import com.korgnote.common.org.agenda.on
import com.korgnote.common.org.agenda.report
import com.korgnote.common.org.agenda.DaySpan
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.cal_all_day
import korgnote.composeapp.generated.resources.cal_change_date
import korgnote.composeapp.generated.resources.cal_change_deadline
import korgnote.composeapp.generated.resources.cal_reschedule
import korgnote.composeapp.generated.resources.cal_clocked_block
import korgnote.composeapp.generated.resources.cal_clocked_period
import korgnote.composeapp.generated.resources.cal_clocked_report
import korgnote.composeapp.generated.resources.cal_clocked_summary
import korgnote.composeapp.generated.resources.ok
import korgnote.composeapp.generated.resources.cal_clocked_total
import korgnote.composeapp.generated.resources.editor_clock_in
import korgnote.composeapp.generated.resources.editor_clock_out
import korgnote.composeapp.generated.resources.cal_kind_closed
import korgnote.composeapp.generated.resources.cal_kind_deadline
import korgnote.composeapp.generated.resources.cal_kind_scheduled
import korgnote.composeapp.generated.resources.cal_kind_timestamp
import korgnote.composeapp.generated.resources.cal_nothing_planned
import korgnote.composeapp.generated.resources.cal_overdue
import korgnote.composeapp.generated.resources.cal_status_continues
import korgnote.composeapp.generated.resources.cal_status_due_in
import korgnote.composeapp.generated.resources.cal_status_ends
import korgnote.composeapp.generated.resources.cal_status_overdue
import korgnote.composeapp.generated.resources.cal_status_prefix
import korgnote.composeapp.generated.resources.cal_status_scheduled_ago
import korgnote.composeapp.generated.resources.cal_status_starts
import korgnote.composeapp.generated.resources.cal_today
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlin.math.abs
import kotlin.math.pow
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

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

/** The kind's name, for a screen reader where the icon stands for it. */
@Composable
fun AgendaKind.label(): String = stringResource(
    when (this) {
        AgendaKind.Deadline -> Res.string.cal_kind_deadline
        AgendaKind.Scheduled -> Res.string.cal_kind_scheduled
        AgendaKind.Timestamp -> Res.string.cal_kind_timestamp
        AgendaKind.Closed -> Res.string.cal_kind_closed
    },
)

/** The words for a status, and the number of days that goes in them. */
private val AgendaStatus.words: Pair<StringResource, Int?>
    get() = when (this) {
        AgendaStatus.Deadline -> Res.string.cal_kind_deadline to null
        is AgendaStatus.DueIn -> Res.string.cal_status_due_in to days
        is AgendaStatus.Overdue -> Res.string.cal_status_overdue to days
        is AgendaStatus.ScheduledAgo -> Res.string.cal_status_scheduled_ago to days
        AgendaStatus.Closed -> Res.string.cal_kind_closed to null
        AgendaStatus.Starts -> Res.string.cal_status_starts to null
        AgendaStatus.Continues -> Res.string.cal_status_continues to null
        AgendaStatus.Ends -> Res.string.cal_status_ends to null
    }

/** "Due in 3 d.", "Dans 3 j": the status in the language the app is shown in. */
@Composable
fun AgendaStatus.label(): String = words.let { (text, days) ->
    if (days == null) stringResource(text) else stringResource(text, days)
}

/** [label], away from composition: for a widget, say. */
suspend fun AgendaStatus.text(): String = words.let { (text, days) ->
    if (days == null) getString(text) else getString(text, days)
}

/**
 * The order entries are stacked in a cell or the all-day strip: multi-day ranges first, in the same
 * order on every day so each keeps its row and reads as one bar; then what matters most when there
 * is room for only a few — deadlines, then scheduled work, then appointments — and then by time,
 * then by priority.
 */
private val KIND_ORDER = listOf(AgendaKind.Deadline, AgendaKind.Scheduled, AgendaKind.Timestamp, AgendaKind.Closed)

private val chipOrder = compareBy<AgendaOccurrence>(
    { it.span == DaySpan.Single },
    { if (it.span != DaySpan.Single) it.entry.stamp.start.toEpochDays() else 0L },
    { KIND_ORDER.indexOf(it.entry.kind) },
    { it.time?.let { t -> t.hour * 60 + t.minute } ?: -1 },
    { it.entry.priorityOrder },
    { it.entry.file },
    { it.entry.stamp.range.first },
)

/** Done entries stay visible but step back. */
private fun Modifier.dimmedIfDone(occurrence: AgendaOccurrence) = if (occurrence.entry.done) alpha(0.45f) else this

/**
 * A colour for each TODO keyword the calendar shows. In the week and month grids a day is too
 * narrow for `TODO` or `EVENT` to leave any room for the title, so there the keyword is only an
 * edge in its colour; the agenda and the day view, which spell it out, write it in the same
 * colour, which is how the colours are learnt.
 *
 * The colours are [KeywordSwatches]', which the home-screen widget's labels share.
 */
class KeywordColors(keywords: Collection<String>) {
    private val swatches = KeywordSwatches(keywords)

    /** [entry]'s keyword colour, or null when it has no keyword. */
    fun of(entry: AgendaEntry): Color? {
        val keyword = entry.todo ?: return null
        if (entry.done) return OrgColors.done
        return swatches.of(keyword, done = false)?.color()
    }
}

@Composable
private fun rememberKeywordColors(agenda: AgendaIndex) = remember(agenda) {
    KeywordColors(agenda.entries.filter { !it.done }.mapNotNullTo(mutableSetOf()) { it.todo })
}

/** A bar down the left edge in [color], under what follows; nothing for no colour. */
private fun Modifier.keywordEdge(color: Color?) =
    if (color == null) this else drawBehind { drawRect(color, size = Size(KEYWORD_EDGE.toPx(), size.height)) }

private val KEYWORD_EDGE = 3.dp

/**
 * `TODO Title`, as the heading reads, with the priority cookie when there is one and the keyword
 * in [keywordColor]; or, [titleOnly], just the title, for where the keyword is an edge instead.
 *
 * The title goes through the same inline rendering as reading mode, so a link shows as its
 * description and emphasis as emphasis rather than as markup. The links themselves are left out:
 * tapping an entry opens it, and a link inside it must not take the tap.
 */
private fun AgendaOccurrence.headline(
    prefix: String? = null,
    keywordColor: Color? = null,
    titleOnly: Boolean = false,
): AnnotatedString = buildAnnotatedString {
    if (!titleOnly) {
        prefix?.let { append("$it ") }
        entry.todo?.let { todo ->
            withStyle(SpanStyle(color = keywordColor ?: Color.Unspecified)) { append(todo) }
            append(" ")
        }
        entry.priority?.let { append("[#${it.text}] ") }
    }
    val title = buildOrgInlineString(entry.title, viewMode = true)
    append(AnnotatedString(title.text, title.spanStyles))
}

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
private fun EntryMenu(
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
private fun Modifier.openOrMenu(onOpen: () -> Unit, onMenu: () -> Unit): Modifier {
    val actions = LocalCalendarActions.current
    return combinedClickable(onClick = onOpen, onLongClick = if (actions != null) onMenu else null)
}

/** The colour of clocked time, set apart from the kinds of date: work done, not work planned. */
@Composable
private fun clockedAccent(): Color = MaterialTheme.colorScheme.secondary

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
    /** What a running clock counts to in the report; the start of [today] when not given. */
    now: LocalDateTime = LocalDateTime(today, LocalTime(0, 0)),
) {
    val sections = remember(agenda, from, today, showDone) { agenda.agendaList(from, AGENDA_LIST_DAYS, today, showDone) }
    val last = from.plus(AGENDA_LIST_DAYS - 1, DateTimeUnit.DAY)
    val report = remember(agenda, from, now) { agenda.clocks.report(from, last, now) }
    val keywordColors = rememberKeywordColors(agenda)
    LazyColumn(modifier = modifier.fillMaxSize().testTag("agendaList")) {
        for (section in sections) {
            item(key = "header-${section.date}") {
                val date = section.date
                val isToday = date == today
                Text(
                    text = when {
                        date == null -> stringResource(Res.string.cal_overdue)
                        else -> "${date.dayOfWeek.displayName().capitalised()} ${date.day} ${date.month.displayName()}" +
                            if (isToday) " · ${stringResource(Res.string.cal_today)}" else ""
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
                        stringResource(Res.string.cal_nothing_planned),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
            }
            // Room for a time only on a day that has one: a column of nothing but icons would push
            // every title a third of the way across.
            val leading = if (section.occurrences.any { it.timeLabel() != null }) 88.dp else 24.dp
            // The time as well: a stamp repeating by the hour lands on one day several times.
            items(section.occurrences, key = { "${section.date}-${it.entry.file}-${it.entry.stamp.range.first}-${it.date}-${it.time}" }) {
                AgendaRow(it, today, leading, keywordColors.of(it.entry), onOpen = { onOpen(it) })
            }
        }
        if (report.isNotEmpty()) {
            item(key = "clocked") { ClockReport(report, from, last) }
        }
    }
}

/**
 * How long was clocked in the days shown, above the day or the week, when anything was: a tap
 * shows the report for those days, as the agenda shows it under its own.
 */
@Composable
private fun ClockedSummary(agenda: AgendaIndex, from: LocalDate, to: LocalDate, now: LocalDateTime) {
    val report = remember(agenda, from, to, now) { agenda.clocks.report(from, to, now) }
    if (report.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val accent = clockedAccent()
    Row(
        Modifier.clickable { open = true }.padding(horizontal = 4.dp, vertical = 4.dp).testTag("clockedSummary"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Timer, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(Res.string.cal_clocked_summary, clockDuration(report.sumOf { it.minutes })),
            style = MaterialTheme.typography.labelMedium,
            color = accent,
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(Res.string.ok)) } },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ClockReport(report, from, to, onOpened = { open = false })
                }
            },
        )
    }
}

/**
 * Emacs's agenda clock report: the time clocked in the days shown, by note and heading, and the
 * total. A tap on a heading opens it; a heading whose clock runs is lit, its time still going up.
 */
@Composable
private fun ClockReport(report: List<ClockedTotal>, from: LocalDate, to: LocalDate, onOpened: () -> Unit = {}) {
    val actions = LocalCalendarActions.current
    val accent = clockedAccent()
    Column(Modifier.fillMaxWidth().padding(top = if (from == to) 0.dp else 24.dp, bottom = 8.dp).testTag("clockReport")) {
        Row(Modifier.padding(start = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Timer, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(Res.string.cal_clocked_report), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Text(
            if (from == to) shortDay(from) else stringResource(Res.string.cal_clocked_period, shortDay(from), shortDay(to)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 26.dp, bottom = 8.dp),
        )
        for ((file, totals) in report.groupBy { it.file }) {
            Text(
                file.substringAfterLast('/'),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
            )
            for (total in totals) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = actions != null) {
                            actions?.open(total.file, total.headingOffset)
                            onOpened()
                        }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(20.dp).background(accent, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        (total.todo?.let { "$it " } ?: "") + buildOrgInlineString(total.title, viewMode = true).text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (total.running) Icon(Icons.Filled.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    Text(
                        clockDuration(total.minutes),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (total.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp)) {
            Text(
                stringResource(Res.string.cal_clocked_total),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 11.dp),
            )
            Text(clockDuration(report.sumOf { it.minutes }), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

/** `Mon 2 Nov`, in the language the app is shown in. */
@Composable
private fun shortDay(date: LocalDate): String = "${date.dayOfWeek.shortName()} ${date.day} ${date.month.shortName()}"


@Composable
private fun AgendaRow(occurrence: AgendaOccurrence, today: LocalDate, leading: Dp, keywordColor: Color?, onOpen: () -> Unit) {
    val accent = occurrence.entry.kind.accent()
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .openOrMenu(onOpen) { menu = true }
            .dimmedIfDone(occurrence)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryMenu(occurrence.entry.file, occurrence.entry.headingOffset, occurrence.entry.todo, menu, onDismiss = { menu = false }, entry = occurrence.entry)
        Box(Modifier.width(3.dp).height(36.dp).background(accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(leading)) {
            val time = occurrence.timeLabel()
            if (time != null) {
                Text(time, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
            } else {
                Icon(occurrence.entry.kind.icon(), contentDescription = occurrence.entry.kind.label(), tint = accent, modifier = Modifier.size(16.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            occurrence.status(today)?.let {
                Text(it.label(), style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.SemiBold)
            }
            Text(
                occurrence.headline(keywordColor = keywordColor),
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

/**
 * An hour of the grid until it is pinched: a minute a dp, which leaves a half-hour meeting room for
 * its title and time.
 */
val DEFAULT_HOUR_HEIGHT = 60.dp

/** Pinched in, a whole day on a phone's screen; pinched out, room for a quarter-hour's title and time. */
private val MIN_HOUR_HEIGHT = 24.dp
private val MAX_HOUR_HEIGHT = 240.dp

private val TIME_LABEL_SIZE = 9.sp
private val TIME_LABEL_LINE = 11.sp
private val GUTTER_WIDTH = 44.dp

/**
 * The narrowest a day of the week is drawn. Seven days on a phone held upright are a word wide
 * each, too narrow for a title; there the days scroll sideways instead, three and a bit on screen,
 * the bit saying there are more.
 */
private val MIN_DAY_WIDTH = 96.dp

/**
 * One or more days side by side: a header per day when there is more than one, an all-day strip,
 * and an hour grid with timed entries as blocks and a line at the current time.
 *
 * The grid opens scrolled to an hour before now, or to [dayStartHour] on other days, rather than at
 * midnight, which is rarely where anything is. Where the days do not all fit across, they scroll
 * sideways together with their headers and all-day strip, and open on today.
 *
 * Two fingers pulled apart or together on the grid stretch or squeeze the hours, [hourHeight] each,
 * around the time between the fingers; Ctrl and the wheel do the same where there is no touch.
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
    hourHeight: Dp = DEFAULT_HOUR_HEIGHT,
    onHourHeightChange: (Dp) -> Unit = {},
) {
    val today = now.date
    val byDate = remember(agenda, dates, today, showDone) { dates.associateWith { agenda.shownOn(it, today, showDone) } }
    // What was clocked on each day, a running clock up to now: moved on each minute with it.
    val clockedByDate = remember(agenda, dates, now) { dates.associateWith { agenda.clocks.on(it, now) } }
    val keywordColors = rememberKeywordColors(agenda)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val dayWidth = maxOf((maxWidth - GUTTER_WIDTH) / dates.size, MIN_DAY_WIDTH)
        val daysWidth = dayWidth * dates.size
        // One scroll for the headers, the all-day strip and the grid, so that they stay in line. A
        // swipe that starts at either end goes on to the pager, and so to the week before or after.
        val sideways = rememberScrollState(with(LocalDensity.current) { (dayWidth * dates.indexOf(today).coerceAtLeast(0)).roundToPx() })
        val days = if (daysWidth > maxWidth - GUTTER_WIDTH) Modifier.horizontalScroll(sideways).width(daysWidth) else Modifier
        val allDayMax = maxHeight / 3
        Column(Modifier.fillMaxSize().nestedScroll(remember { StopAtTheEdge() })) {
            ClockedSummary(agenda, dates.first(), dates.last(), now)
            if (dates.size > 1) {
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(GUTTER_WIDTH))
                    // The weekday beside the date rather than over it: the height is better spent on the hours.
                    Row(Modifier.weight(1f).then(days)) {
                        for (date in dates) {
                            Row(
                                Modifier.weight(1f).clickable { onDayClick(date) }.padding(vertical = 4.dp).testTag("weekDay-$date"),
                                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(date.dayOfWeek.shortName(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                DayNumber(date, isToday = date == today)
                            }
                        }
                    }
                }
            }

            // All-day strip: untimed entries, deadlines, and the days of multi-day ranges.
            // Multi-day ranges first, in the same order on every day, so that each one keeps its row
            // and reads as one bar across the columns.
            val allDay = remember(dates, byDate) {
                dates.map { date -> byDate.getValue(date).filter { it.isAllDay }.sortedWith(chipOrder) }
            }
            val compact = dates.size > 1
            if (allDay.any { it.isNotEmpty() }) {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        stringResource(Res.string.cal_all_day),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.width(GUTTER_WIDTH).padding(top = 2.dp),
                    )
                    if (compact) {
                        WeekAllDay(dates, allDay, today, keywordColors, onOpen, onDayClick, Modifier.weight(1f).then(days))
                    } else {
                        // A day with a dozen things to do and no time to them would otherwise push the
                        // hours off the screen: past a third of it, the strip scrolls.
                        Column(
                            Modifier.weight(1f).heightIn(max = allDayMax).verticalScroll(rememberScrollState()).testTag("allDay"),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            allDay.single().forEach {
                                key(it.entry.file, it.entry.stamp.range.first, it.time) {
                                    EventChip(it, today, keywordColors.of(it.entry), onOpen = { onOpen(it) })
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
            }

            // Opens an hour before now on today, or at the day's start, but never below the first
            // timed entry on screen: an early meeting scrolled out of sight is a meeting missed.
            val firstEvent = remember(dates, byDate, clockedByDate) {
                (
                    dates.flatMap { byDate.getValue(it) }.filter { !it.isAllDay }.map { it.time!!.hour } +
                        dates.flatMap { date -> clockedByDate.getValue(date).filter { it.span.start.date == date } }.map { it.startMinute / 60 }
                    ).minOrNull()
            }
            val preferred = if (today in dates) (now.hour - 1).coerceAtLeast(0) else dayStartHour
            val initialHour = minOf(preferred, firstEvent ?: preferred)
            // A little above the hour, so its label is not cut in half.
            val initialScroll = with(LocalDensity.current) { (hourHeight * initialHour - 8.dp).coerceAtLeast(0.dp).roundToPx() }
            val scroll = rememberScrollState(initialScroll)
            val currentHourHeight by rememberUpdatedState(hourHeight)
            val currentOnHourHeightChange by rememberUpdatedState(onHourHeightChange)
            val zoom = Modifier.zoomHours { factor, focusY ->
                val old = currentHourHeight
                val new = (old * factor).coerceIn(MIN_HOUR_HEIGHT, MAX_HOUR_HEIGHT)
                if (new != old) {
                    currentOnHourHeightChange(new)
                    // The time between the fingers stays between them.
                    scroll.dispatchRawDelta((scroll.value + focusY) * (new / old - 1))
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f).then(zoom).verticalScroll(scroll).testTag("hourGrid")) {
                Column(Modifier.width(GUTTER_WIDTH)) {
                    for (hour in 0 until 24) {
                        Box(Modifier.height(hourHeight)) {
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
                Row(Modifier.weight(1f).then(days)) {
                    for (date in dates) {
                        DayColumn(
                            layout = remember(byDate, clockedByDate, date) { layoutDay(byDate.getValue(date), clockedByDate.getValue(date)) },
                            nowMinute = if (date == today) now.hour * 60 + now.minute else null,
                            hourHeight = hourHeight,
                            keywordColors = keywordColors,
                            compact = compact,
                            onOpen = onOpen,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Keeps a swipe that scrolled the days from also turning the week: what is left of it at the end of
 * the days stops there, and only a swipe that starts at the end goes on to the pager. The pager
 * judges a swipe by how far the finger went, so without this a long one that mostly scrolled the
 * days would turn the week as well.
 */
private class StopAtTheEdge : NestedScrollConnection {
    private var scrolledDays = false

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (consumed.x != 0f) scrolledDays = true
        return if (scrolledDays) Offset(available.x, 0f) else Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val stopped = if (scrolledDays) Velocity(available.x, 0f) else Velocity.Zero
        scrolledDays = false
        return stopped
    }
}

/**
 * Calls [onZoom] with how much two fingers moved apart up and down since the last event, and where
 * between them they are, and keeps their moves from the scroll and the pager under them; or with a
 * step of Ctrl and the wheel, at the pointer. One finger is left alone, to scroll.
 */
private fun Modifier.zoomHours(onZoom: (factor: Float, focusY: Float) -> Unit) = pointerInput(Unit) {
    // Fingers side by side rather than one over the other say nothing about the hours.
    val minSpan = 24.dp.toPx()
    awaitEachGesture {
        do {
            // Before the scroll under it sees the event, so that two fingers do not also scroll.
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val (a, b) = pressed
                val span = abs(a.position.y - b.position.y)
                val before = abs(a.previousPosition.y - b.previousPosition.y)
                if (span >= minSpan && before >= minSpan) onZoom(span / before, (a.position.y + b.position.y) / 2)
                event.changes.forEach { it.consume() }
            } else if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isCtrlPressed) {
                val change = event.changes.first()
                onZoom(1.1f.pow(-change.scrollDelta.y), change.position.y)
                change.consume()
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun DayColumn(
    layout: DayLayout,
    nowMinute: Int?,
    hourHeight: Dp,
    keywordColors: KeywordColors,
    compact: Boolean,
    onOpen: (AgendaOccurrence) -> Unit,
    modifier: Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val titleStyle = MaterialTheme.typography.labelSmall
    val (titleLine, timeLine) = with(LocalDensity.current) { titleStyle.lineHeight.toDp() to TIME_LABEL_LINE.toDp() }
    BoxWithConstraints(modifier.height(hourHeight * 24)) {
        // Hour lines.
        for (hour in 0 until 24) {
            HorizontalDivider(Modifier.offset(y = hourHeight * hour), color = lineColor.copy(alpha = 0.5f))
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(lineColor.copy(alpha = 0.5f)))

        for (block in layout.clocked) {
            key(block.piece.span.file, block.piece.span.headingOffset, block.piece.startMinute) {
                ClockedBlockView(block, width = maxWidth / block.columns, hourHeight = hourHeight, compact = compact, titleLine = titleLine, timeLine = timeLine)
            }
        }

        for (block in layout.events) {
            val occurrence = block.occurrence
            // By entry, not by place: what is open on a block stays with it when a sync adds one before it.
            key(occurrence.entry.file, occurrence.entry.stamp.range.first, occurrence.time) {
                EventBlock(block, width = maxWidth / block.columns, hourHeight = hourHeight, keywordColors = keywordColors, compact = compact, titleLine = titleLine, timeLine = timeLine, onOpen = onOpen)
            }
        }

        if (nowMinute != null) {
            val nowColor = MaterialTheme.colorScheme.error
            Box(Modifier.fillMaxWidth().offset(y = hourHeight * nowMinute / 60 - 1.dp).testTag("nowLine")) {
                Box(Modifier.fillMaxWidth().height(2.dp).offset(y = 3.dp).background(nowColor))
                Box(Modifier.size(8.dp).background(nowColor, CircleShape))
            }
        }
    }
}

/**
 * A timed entry on the hour grid, as tall as it lasts: its title, as many lines of it as the block
 * has room for, and its time under it where there is room for both.
 */
@Composable
private fun EventBlock(
    block: TimedBlock,
    width: Dp,
    hourHeight: Dp,
    keywordColors: KeywordColors,
    compact: Boolean,
    titleLine: Dp,
    timeLine: Dp,
    onOpen: (AgendaOccurrence) -> Unit,
) {
    val occurrence = block.occurrence
    val accent = occurrence.entry.kind.accent()
    val keywordColor = keywordColors.of(occurrence.entry)
    val height = hourHeight * (block.endMinute - block.startMinute) / 60
    // The time under the title where there is room for both, rather than half of it cut off:
    // where a block sits on the grid already says roughly when it is.
    val time = occurrence.timeLabel()?.takeIf { height - 4.dp >= titleLine + timeLine }
    // As many lines of title as the block is tall, less its time; a long meeting in a
    // narrow column is otherwise two lines of title and a long empty block under them.
    val lines = ((height - 4.dp - (if (time != null) timeLine else 0.dp)) / titleLine).toInt().coerceAtLeast(1)
    val inset = if (compact) 1.dp else 3.dp
    // A pinch comes here with every move of the fingers, and only the block's size has changed.
    val headline = remember(occurrence, keywordColor, compact) { occurrence.headline(keywordColor = keywordColor, titleOnly = compact) }
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .offset(x = width * block.column, y = hourHeight * block.startMinute / 60)
            .width(width)
            .height(height)
            .padding(1.dp)
            .clip(RoundedCornerShape(4.dp))
            // Opaque, so the hour lines do not run through the text.
            .background(MaterialTheme.colorScheme.surface)
            .background(accent.copy(alpha = 0.18f))
            .keywordEdge(keywordColor)
            .openOrMenu({ onOpen(occurrence) }) { menu = true }
            .dimmedIfDone(occurrence)
            .padding(
                start = (if (keywordColor != null) KEYWORD_EDGE else 0.dp) + inset,
                end = inset,
                top = 1.dp,
                bottom = 1.dp,
            ),
    ) {
        EntryMenu(occurrence.entry.file, occurrence.entry.headingOffset, occurrence.entry.todo, menu, onDismiss = { menu = false }, entry = occurrence.entry)
        Text(
            headline,
            modifier = Modifier.keywordDescription(occurrence, compact),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
        )
        time?.let {
            Text(it, fontSize = TIME_LABEL_SIZE, lineHeight = TIME_LABEL_LINE, color = accent, maxLines = 1)
        }
    }
}

/**
 * A stretch of clocked time on the hour grid: what the hours actually went on, beside what was
 * planned in them. Outlined in its own colour with a timer, rather than filled as an entry is; a
 * running clock grows with the time. A tap opens the heading, a long-press clocks it in or out.
 */
@Composable
private fun ClockedBlockView(block: ClockedBlock, width: Dp, hourHeight: Dp, compact: Boolean, titleLine: Dp, timeLine: Dp) {
    val actions = LocalCalendarActions.current
    val span = block.piece.span
    val running = span.end == null
    val accent = if (running) MaterialTheme.colorScheme.primary else clockedAccent()
    val height = hourHeight * (block.endMinute - block.startMinute) / 60
    val start = LocalTime(block.piece.startMinute / 60, block.piece.startMinute % 60).hhmm()
    val duration = clockDuration(block.piece.minutes)
    val time = "$start · $duration".takeIf { height - 4.dp >= titleLine + timeLine }
    val lines = ((height - 4.dp - (if (time != null) timeLine else 0.dp)) / titleLine).toInt().coerceAtLeast(1)
    val title = buildOrgInlineString(span.title, viewMode = true).text
    val description = stringResource(Res.string.cal_clocked_block, title, start, duration)
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .offset(x = width * block.column, y = hourHeight * block.startMinute / 60)
            .width(width)
            .height(height)
            .padding(1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface)
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .openOrMenu({ actions?.open(span.file, span.headingOffset) }) { menu = true }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("clocked-${span.file}-${span.headingOffset}-${block.piece.startMinute}")
            .padding(horizontal = if (compact) 2.dp else 4.dp, vertical = 1.dp),
    ) {
        EntryMenu(span.file, span.headingOffset, span.todo, menu, onDismiss = { menu = false })
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Timer, contentDescription = null, tint = accent, modifier = Modifier.padding(top = 2.dp, end = 2.dp).size(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        time?.let { Text(it, fontSize = TIME_LABEL_SIZE, lineHeight = TIME_LABEL_LINE, color = accent, maxLines = 1) }
    }
}

/**
 * The week's all-day strip: each multi-day range as one bar across its days, with room for two
 * lines of title, then each day's own entries under it, as many as [ALL_DAY_LIMIT] allows and a
 * count of the rest.
 */
@Composable
private fun WeekAllDay(
    dates: List<LocalDate>,
    allDay: List<List<AgendaOccurrence>>,
    today: LocalDate,
    keywordColors: KeywordColors,
    onOpen: (AgendaOccurrence) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bars = remember(allDay) { layoutRangeBars(allDay) }
    val lanes = (bars.maxOfOrNull { it.lane } ?: -1) + 1
    val shownLanes = lanes.coerceAtMost(ALL_DAY_LIMIT)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (lane in 0 until shownLanes) {
            BarLane(bars.filter { it.lane == lane }, dates.size) { bar ->
                RangeBarChip(bar, keywordColors.of(bar.occurrence.entry), maxLines = 2, onOpen = { onOpen(bar.occurrence) })
            }
        }
        Row(Modifier.fillMaxWidth()) {
            dates.forEachIndexed { i, date ->
                val singles = allDay[i].filter { it.span == DaySpan.Single }
                val limit = (ALL_DAY_LIMIT - shownLanes).coerceAtLeast(1)
                // The ranges that crossed this day in a lane left out count among the rest.
                val hidden = (singles.size - limit).coerceAtLeast(0) +
                    bars.count { it.lane >= shownLanes && i in it.columns }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // A day with little on it lets each title take a second line.
                    val lines = if (singles.size <= 2) 2 else 1
                    singles.take(limit).forEach {
                        key(it.entry.file, it.entry.stamp.range.first, it.time) {
                            EventChip(it, today, keywordColors.of(it.entry), onOpen = { onOpen(it) }, compact = true, maxLines = lines)
                        }
                    }
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
    }
}

/** Entries in the week's all-day strip, bars and days together, before the rest are counted. */
private const val ALL_DAY_LIMIT = 3

/** One lane of [bars] across a row of [days] columns, each bar as wide as the days it crosses. */
@Composable
private fun BarLane(bars: List<RangeBar>, days: Int, modifier: Modifier = Modifier, bar: @Composable (RangeBar) -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        var column = 0
        for (each in bars.sortedBy { it.first }) {
            if (each.first > column) Spacer(Modifier.weight((each.first - column).toFloat()))
            Box(Modifier.weight((each.last - each.first + 1).toFloat())) { bar(each) }
            column = each.last + 1
        }
        if (column < days) Spacer(Modifier.weight((days - column).toFloat()))
    }
}

/**
 * A multi-day range over the days of a row, its title across all of them. Rounded where it starts
 * and ends, square where it goes on past the row.
 */
@Composable
private fun RangeBarChip(
    bar: RangeBar,
    keywordColor: Color?,
    maxLines: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val occurrence = bar.occurrence
    val accent = occurrence.entry.kind.accent()
    val r = 3.dp
    val start = if (bar.continuesBefore) 0.dp else r
    val end = if (bar.continuesAfter) 0.dp else r
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = if (bar.continuesBefore) 0.dp else 1.dp, end = if (bar.continuesAfter) 0.dp else 1.dp)
            .clip(RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end))
            .background(accent.copy(alpha = 0.18f))
            .keywordEdge(keywordColor)
            .clickable(onClick = onOpen)
            .dimmedIfDone(occurrence)
            .padding(start = if (keywordColor != null) KEYWORD_EDGE + 2.dp else 3.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = occurrence.headline(keywordColor = keywordColor, titleOnly = true),
            modifier = Modifier.keywordDescription(occurrence, titleOnly = true),
            fontSize = CHIP_FONT,
            lineHeight = CHIP_LINE,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * An entry in the all-day strip or a month cell: its colour, and as much of its title as fits.
 *
 * A day of a multi-day range, in the day view, is squared off on the sides where the range goes on.
 * The week and the month draw a range as one [RangeBarChip] across its days instead.
 */
@Composable
private fun EventChip(
    occurrence: AgendaOccurrence,
    today: LocalDate,
    keywordColor: Color?,
    onOpen: () -> Unit,
    compact: Boolean = false,
    maxLines: Int = 1,
) {
    val accent = occurrence.entry.kind.accent()
    val status = occurrence.status(today)?.label()
    val r = 3.dp
    val (shape, inset) = when (occurrence.span) {
        DaySpan.Single -> RoundedCornerShape(r) to PaddingValues(horizontal = 1.dp)
        DaySpan.Start -> RoundedCornerShape(topStart = r, bottomStart = r) to PaddingValues(start = 1.dp)
        DaySpan.Middle -> RectangleShape to PaddingValues()
        DaySpan.End -> RoundedCornerShape(topEnd = r, bottomEnd = r) to PaddingValues(end = 1.dp)
    }
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(inset)
            .clip(shape)
            .background(accent.copy(alpha = 0.18f))
            .keywordEdge(keywordColor)
            .openOrMenu(onOpen) { menu = true }
            .dimmedIfDone(occurrence)
            .padding(
                start = if (keywordColor != null) KEYWORD_EDGE + 2.dp else 3.dp,
                end = 3.dp,
                top = if (compact) 0.dp else 2.dp,
                bottom = if (compact) 0.dp else 2.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryMenu(occurrence.entry.file, occurrence.entry.headingOffset, occurrence.entry.todo, menu, onDismiss = { menu = false }, entry = occurrence.entry)
        if (!compact) {
            Icon(occurrence.entry.kind.icon(), contentDescription = null, tint = accent, modifier = Modifier.size(10.dp))
            Spacer(Modifier.width(2.dp))
        }
        Text(
            text = when {
                !compact && status != null && occurrence.entry.kind == AgendaKind.Deadline ->
                    occurrence.headline(prefix = stringResource(Res.string.cal_status_prefix, status), keywordColor = keywordColor)
                else -> occurrence.headline(keywordColor = keywordColor, titleOnly = compact)
            },
            modifier = Modifier.keywordDescription(occurrence, compact),
            fontSize = if (compact) CHIP_FONT else 11.sp,
            lineHeight = if (compact) CHIP_LINE else 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            // A range is one line on every day it crosses, so its bar stays straight.
            maxLines = if (occurrence.span == DaySpan.Single) maxLines else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The keyword a title-only entry leaves out, still read out by a screen reader. */
private fun Modifier.keywordDescription(occurrence: AgendaOccurrence, titleOnly: Boolean): Modifier {
    val keyword = occurrence.entry.todo?.takeIf { titleOnly } ?: return this
    val title = buildOrgInlineString(occurrence.entry.title, viewMode = true).text
    return semantics { contentDescription = "$keyword $title" }
}

@Composable
private fun DayNumber(date: LocalDate, isToday: Boolean, dim: Boolean = false) {
    Box(
        Modifier
            .size(DAY_NUMBER_SIZE)
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
 * as [MONTH_MAX_ENTRIES] allows and a count of the rest. Tapping a day opens it in the day view.
 *
 * Each week is as tall as its busiest day needs, and the weeks share out what is left of the
 * screen, so a quiet week does not take the room a busy one would cut its titles short for. When
 * the weeks need more than the screen, the month scrolls.
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
    val weeks = remember(anchorDate.year, anchorDate.month) { monthWeeks(anchorDate) }
    val keywordColors = rememberKeywordColors(agenda)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            for (d in DayOfWeek.entries) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(d.shortName(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            WeekRows(maxHeight, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("monthWeeks")) {
                for (dates in weeks) {
                    MonthWeek(
                        dates = dates,
                        days = remember(agenda, dates, showDone) { dates.map { agenda.monthDay(it, showDone).sortedWith(chipOrder) } },
                        month = anchorDate.month,
                        today = today,
                        keywordColors = keywordColors,
                        onDayClick = onDayClick,
                    )
                }
            }
        }
    }
}

/** A week of the month: the days' numbers, the ranges as bars across them, and each day's entries. */
@Composable
private fun MonthWeek(
    dates: List<LocalDate>,
    days: List<List<AgendaOccurrence>>,
    month: Month,
    today: LocalDate,
    keywordColors: KeywordColors,
    onDayClick: (LocalDate) -> Unit,
) {
    // Each range as one bar across its days in the week, its title as wide as the bar.
    val bars = remember(days) { layoutRangeBars(days) }
    val lanes = (bars.maxOfOrNull { it.lane } ?: -1) + 1
    // A line is kept for the count of the rest wherever a lane is left out.
    val shownLanes = if (lanes > MONTH_MAX_ENTRIES) MONTH_MAX_ENTRIES - 1 else lanes
    // A bar under the day numbers, with the gap above it.
    val line = with(LocalDensity.current) { CHIP_LINE.toDp() } + 1.dp
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxSize()) {
            dates.forEachIndexed { col, date ->
                val singles = days[col].filter { it.span == DaySpan.Single }
                val crossing = bars.count { it.lane >= shownLanes && col in it.columns }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(vertical = 1.dp)
                        .clickable { onDayClick(date) }
                        .testTag("monthDay-$date"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    DayNumber(date, isToday = date == today, dim = date.month != month)
                    // Under the lanes the bars are drawn in, over the cells.
                    Spacer(Modifier.height(line * shownLanes))
                    val room = MONTH_MAX_ENTRIES - shownLanes
                    val rest = singles.size + crossing
                    // The count of the rest takes a line too, unless there are none.
                    val fits = if (rest > room) (room - 1).coerceAtLeast(0) else room
                    // A day with little on it lets each title run on to a third line.
                    val lines = if (singles.size <= 2) 3 else 2
                    val shown = singles.take(fits)
                    shown.forEach {
                        key(it.entry.file, it.entry.stamp.range.first, it.time) {
                            Spacer(Modifier.height(1.dp))
                            EventChip(it, today, keywordColors.of(it.entry), onOpen = { onDayClick(date) }, compact = true, maxLines = lines)
                        }
                    }
                    val hidden = rest - shown.size
                    if (hidden > 0) {
                        Text("+$hidden", fontSize = CHIP_FONT, lineHeight = CHIP_LINE, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        // Drawn over the cells, below the day numbers, at the height their spacers keep.
        Column(Modifier.fillMaxWidth().padding(top = 1.dp + DAY_NUMBER_SIZE)) {
            for (lane in 0 until shownLanes) {
                Spacer(Modifier.height(1.dp))
                BarLane(bars.filter { it.lane == lane }, days = 7, modifier = Modifier.height(line - 1.dp)) { bar ->
                    RangeBarChip(
                        bar,
                        keywordColors.of(bar.occurrence.entry),
                        maxLines = 1,
                        onOpen = { onDayClick(dates[bar.first]) },
                        modifier = Modifier.fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/**
 * Weeks one under the other, each as tall as it needs and the shorter ones raised to fill [height]
 * between them, as [fillHeights] shares it out.
 */
@Composable
private fun WeekRows(height: Dp, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val heights = fillHeights(measurables.map { it.minIntrinsicHeight(width) }, height.roundToPx())
        val placeables = measurables.zip(heights) { week, h -> week.measure(Constraints.fixed(width, h)) }
        layout(width, heights.sum()) {
            var y = 0
            placeables.forEach {
                it.place(0, y)
                y += it.height
            }
        }
    }
}

/** The circle a day's number sits in. */
private val DAY_NUMBER_SIZE = 24.dp

/** Lines of entries under a day's number, bars included, before the rest are counted. */
private const val MONTH_MAX_ENTRIES = 5

/** The text of an entry in the week's all-day strip and in the month, where a day is narrow. */
private val CHIP_FONT = 10.sp
private val CHIP_LINE = 12.sp
