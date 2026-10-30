package com.korgnote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgKeyword
import com.korgnote.common.org.agenda.parseOrgTimestamp
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.author
import korgnote.composeapp.generated.resources.notes_date
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Month
import org.jetbrains.compose.resources.stringResource

/**
 * Who wrote a note and when, read from its `#+AUTHOR:` and `#+DATE:`, for reading mode to show as
 * one line under the title instead of as two lines of syntax.
 *
 * [indices] are the document children it stands for: the first of them shows the byline, the rest
 * show nothing. [writtenDate] is the date as the note writes it.
 */
internal data class NoteByline(val author: String?, val writtenDate: String?, val indices: Set<Int>) {
    /** [writtenDate] written out in English, see [readableDate]; the screen writes it in the app's language. */
    val date: String? get() = writtenDate?.let(::readableDate)
}

/** The byline of [document], or null when it names neither an author nor a date. */
internal fun noteBylineOf(document: OrgDocument): NoteByline? {
    var author: String? = null
    var date: String? = null
    val indices = mutableSetOf<Int>()
    document.children.forEachIndexed { index, child ->
        val line = (child as? OrgKeyword)?.content?.trim() ?: return@forEachIndexed
        AUTHOR.matchEntire(line)?.let { match ->
            if (author == null) author = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
            indices += index
        }
        DATE.matchEntire(line)?.let { match ->
            if (date == null) date = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
            indices += index
        }
    }
    return if (indices.isEmpty()) null else NoteByline(author, date, indices)
}

/**
 * `[2026-10-27 Tue]` as `Tue 27 Oct 2026`, with its time when it has one. A date Org cannot read is
 * shown as written, without its brackets. [dayName] and [monthName] name the day and the month,
 * in English unless they say otherwise.
 */
internal fun readableDate(
    value: String,
    dayName: (DayOfWeek) -> String = { it.name.take(3).lowercase().capitalised() },
    monthName: (Month) -> String = { it.name.take(3).lowercase().capitalised() },
): String {
    val stamp = parseOrgTimestamp(value) ?: return value.trim('[', ']', '<', '>', ' ')
    val date = stamp.start
    val time = stamp.startTime?.let { " ${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}" }.orEmpty()
    return "${dayName(date.dayOfWeek)} ${date.day} ${monthName(date.month)} ${date.year}$time"
}

/** [readableDate] in the app's language. */
@Composable
private fun shownDate(value: String): String {
    val date = parseOrgTimestamp(value)?.start ?: return readableDate(value)
    val day = date.dayOfWeek.shortName()
    val month = date.month.shortName()
    return readableDate(value, { day }, { month })
}

@Composable
internal fun NoteBylineView(byline: NoteByline) {
    if (byline.author == null && byline.writtenDate == null) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(bottom = 6.dp),
    ) {
        byline.author?.let { BylinePart(Icons.Outlined.Person, it, stringResource(Res.string.author)) }
        byline.writtenDate?.let { BylinePart(Icons.Outlined.CalendarToday, shownDate(it), stringResource(Res.string.notes_date)) }
    }
}

@Composable
private fun BylinePart(icon: ImageVector, text: String, what: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = what, tint = color, modifier = Modifier.size(16.dp))
        Text(
            buildOrgInlineString(text),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

private val AUTHOR = Regex("^#\\+AUTHOR:\\s*(.*)$", RegexOption.IGNORE_CASE)
private val DATE = Regex("^#\\+DATE:\\s*(.*)$", RegexOption.IGNORE_CASE)
