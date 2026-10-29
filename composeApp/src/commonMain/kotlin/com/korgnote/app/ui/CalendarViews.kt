package com.korgnote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaKind
import com.korgnote.common.org.agenda.AgendaOccurrence
import com.korgnote.common.org.agenda.DaySpan
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlin.math.abs
import kotlin.math.pow

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
 * A colour for each TODO keyword the calendar shows. In the week and month grids a day is too
 * narrow for `TODO` or `EVENT` to leave any room for the title, so there the keyword is only an
 * edge in its colour; the agenda and the day view, which spell it out, write it in the same
 * colour, which is how the colours are learnt.
 *
 * Hashing the name, as tags do, gives `TODO` and `EVENT` the same colour. The open keywords take
 * [KEYWORD_PALETTE] in alphabetical order instead, which only moves when a keyword is added; a
 * finished one is grey, whatever it is.
 */
class KeywordColors(keywords: Collection<String>) {
    private val index = keywords.sorted().withIndex().associate { (i, keyword) -> keyword to i }

    /** [entry]'s keyword colour, or null when it has no keyword. */
    fun of(entry: AgendaEntry): Color? {
        val keyword = entry.todo ?: return null
        if (entry.done) return OrgColors.done
        val swatch = KEYWORD_PALETTE[(index[keyword] ?: return null) % KEYWORD_PALETTE.size]
        return Color(0xFF000000.toInt() or if (OrgColors.dark) swatch.dark else swatch.light)
    }
}

/** Away from the kinds' own colours, so an edge does not vanish into its chip's tint. */
private val KEYWORD_PALETTE = listOf(5, 10, 7, 1, 8, 11, 6, 0).map { TagColors.palette[it] }

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
        entry.priority?.let { append("[#$it] ") }
    }
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
    val keywordColors = rememberKeywordColors(agenda)
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
                AgendaRow(it, today, leading, keywordColors.of(it.entry), onOpen = { onOpen(it) })
            }
        }
    }
}

@Composable
private fun AgendaRow(occurrence: AgendaOccurrence, today: LocalDate, leading: Dp, keywordColor: Color?, onOpen: () -> Unit) {
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
                                Text(date.shortWeekday, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                DayNumber(date, isToday = date == today)
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
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        "all day",
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
                                EventChip(it, today, keywordColors.of(it.entry), onOpen = { onOpen(it) })
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
                            blocks = remember(byDate, date) { layoutTimed(byDate.getValue(date)) },
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
    blocks: List<TimedBlock>,
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

        for (block in blocks) {
            val occurrence = block.occurrence
            val accent = occurrence.entry.kind.accent()
            val keywordColor = keywordColors.of(occurrence.entry)
            val width = maxWidth / block.columns
            val height = hourHeight * (block.endMinute - block.startMinute) / 60
            // The time under the title where there is room for both, rather than half of it cut off:
            // where a block sits on the grid already says roughly when it is.
            val time = occurrence.timeLabel()?.takeIf { height - 4.dp >= titleLine + timeLine }
            // As many lines of title as the block is tall, less its time; a long meeting in a
            // narrow column is otherwise two lines of title and a long empty block under them.
            val lines = ((height - 4.dp - (if (time != null) timeLine else 0.dp)) / titleLine).toInt().coerceAtLeast(1)
            val inset = if (compact) 1.dp else 3.dp
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
                    .clickable { onOpen(occurrence) }
                    .dimmedIfDone(occurrence)
                    .padding(
                        start = (if (keywordColor != null) KEYWORD_EDGE else 0.dp) + inset,
                        end = inset,
                        top = 1.dp,
                        bottom = 1.dp,
                    ),
            ) {
                Text(
                    occurrence.headline(keywordColor = keywordColor, titleOnly = compact),
                    modifier = Modifier.keywordDescription(occurrence, compact),
                    style = titleStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = lines,
                    overflow = TextOverflow.Ellipsis,
                )
                time?.let {
                    Text(it, fontSize = TIME_LABEL_SIZE, lineHeight = TIME_LABEL_LINE, color = accent, maxLines = 1)
                }
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
                        EventChip(it, today, keywordColors.of(it.entry), onOpen = { onOpen(it) }, compact = true, maxLines = lines)
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
    val status = occurrence.statusLabel(today)
    val r = 3.dp
    val (shape, inset) = when (occurrence.span) {
        DaySpan.Single -> RoundedCornerShape(r) to PaddingValues(horizontal = 1.dp)
        DaySpan.Start -> RoundedCornerShape(topStart = r, bottomStart = r) to PaddingValues(start = 1.dp)
        DaySpan.Middle -> RectangleShape to PaddingValues()
        DaySpan.End -> RoundedCornerShape(topEnd = r, bottomEnd = r) to PaddingValues(end = 1.dp)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(inset)
            .clip(shape)
            .background(accent.copy(alpha = 0.18f))
            .keywordEdge(keywordColor)
            .clickable(onClick = onOpen)
            .dimmedIfDone(occurrence)
            .padding(
                start = if (keywordColor != null) KEYWORD_EDGE + 2.dp else 3.dp,
                end = 3.dp,
                top = if (compact) 0.dp else 2.dp,
                bottom = if (compact) 0.dp else 2.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!compact) {
            Icon(occurrence.entry.kind.icon(), contentDescription = null, tint = accent, modifier = Modifier.size(10.dp))
            Spacer(Modifier.width(2.dp))
        }
        Text(
            text = when {
                !compact && status != null && occurrence.entry.kind == AgendaKind.Deadline ->
                    occurrence.headline(prefix = "$status:", keywordColor = keywordColor)
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
            for (d in listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(d, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            WeekRows(maxHeight, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("monthWeeks")) {
                for (dates in weeks) {
                    MonthWeek(
                        dates = dates,
                        days = dates.map { agenda.monthDay(it, showDone).sortedWith(chipOrder) },
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
                        Spacer(Modifier.height(1.dp))
                        EventChip(it, today, keywordColors.of(it.entry), onOpen = { onDayClick(date) }, compact = true, maxLines = lines)
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
