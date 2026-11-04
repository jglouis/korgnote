package com.korgnote.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaOccurrence
import com.korgnote.common.org.agenda.DaySpan
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month

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

/** Lines of entries under a day's number, bars included, before the rest are counted. */
private const val MONTH_MAX_ENTRIES = 5
