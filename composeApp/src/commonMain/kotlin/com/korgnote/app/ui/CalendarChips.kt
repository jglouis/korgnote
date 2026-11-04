package com.korgnote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.korgnote.common.org.agenda.AgendaKind
import com.korgnote.common.org.agenda.AgendaOccurrence
import com.korgnote.common.org.agenda.DaySpan
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.cal_status_prefix
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/** One lane of [bars] across a row of [days] columns, each bar as wide as the days it crosses. */
@Composable
internal fun BarLane(bars: List<RangeBar>, days: Int, modifier: Modifier = Modifier, bar: @Composable (RangeBar) -> Unit) {
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
internal fun RangeBarChip(
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
internal fun EventChip(
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

@Composable
internal fun DayNumber(date: LocalDate, isToday: Boolean, dim: Boolean = false) {
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

/** The circle a day's number sits in. */
internal val DAY_NUMBER_SIZE = 24.dp

/** The text of an entry in the week's all-day strip and in the month, where a day is narrow. */
internal val CHIP_FONT = 10.sp
internal val CHIP_LINE = 12.sp
