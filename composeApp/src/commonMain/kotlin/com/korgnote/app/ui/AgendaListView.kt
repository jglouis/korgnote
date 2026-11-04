package com.korgnote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaOccurrence
import com.korgnote.common.org.agenda.ClockedTotal
import com.korgnote.common.org.agenda.report
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.cal_clocked_period
import korgnote.composeapp.generated.resources.cal_clocked_report
import korgnote.composeapp.generated.resources.cal_clocked_total
import korgnote.composeapp.generated.resources.cal_nothing_planned
import korgnote.composeapp.generated.resources.cal_overdue
import korgnote.composeapp.generated.resources.cal_today
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.LocalDateTime
import org.jetbrains.compose.resources.stringResource

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
 * Emacs's agenda clock report: the time clocked in the days shown, by note and heading, and the
 * total. A tap on a heading opens it; a heading whose clock runs is lit, its time still going up.
 */
@Composable
internal fun ClockReport(report: List<ClockedTotal>, from: LocalDate, to: LocalDate, onOpened: () -> Unit = {}) {
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
