package com.korgnote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaOccurrence
import com.korgnote.common.org.agenda.on
import com.korgnote.common.org.agenda.report
import com.korgnote.common.org.agenda.DaySpan
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.cal_all_day
import korgnote.composeapp.generated.resources.cal_clocked_block
import korgnote.composeapp.generated.resources.cal_clocked_summary
import korgnote.composeapp.generated.resources.ok
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.LocalDateTime
import kotlin.math.abs
import kotlin.math.pow
import org.jetbrains.compose.resources.stringResource

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

            val initialHour = remember(dates, byDate, clockedByDate, now, dayStartHour) {
                initialHour(dates, byDate, clockedByDate, now, dayStartHour)
            }
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
