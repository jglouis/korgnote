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

/**
 * Who wrote a note and when, read from its `#+AUTHOR:` and `#+DATE:`, for reading mode to show as
 * one line under the title instead of as two lines of syntax.
 *
 * [indices] are the document children it stands for: the first of them shows the byline, the rest
 * show nothing.
 */
internal data class NoteByline(val author: String?, val date: String?, val indices: Set<Int>)

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
            if (date == null) date = match.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let(::readableDate)
            indices += index
        }
    }
    return if (indices.isEmpty()) null else NoteByline(author, date, indices)
}

/**
 * `[2026-10-27 Tue]` as `Tue 27 Oct 2026`, with its time when it has one. A date Org cannot read is
 * shown as written, without its brackets.
 */
internal fun readableDate(value: String): String {
    val stamp = parseOrgTimestamp(value) ?: return value.trim('[', ']', '<', '>', ' ')
    val date = stamp.start
    val day = date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.titlecase() }
    val month = date.month.name.take(3).lowercase().replaceFirstChar { it.titlecase() }
    val time = stamp.startTime?.let { " ${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}" }.orEmpty()
    return "$day ${date.day} $month ${date.year}$time"
}

@Composable
internal fun NoteBylineView(byline: NoteByline) {
    if (byline.author == null && byline.date == null) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(bottom = 6.dp),
    ) {
        byline.author?.let { BylinePart(Icons.Outlined.Person, it, "Author") }
        byline.date?.let { BylinePart(Icons.Outlined.CalendarToday, it, "Date") }
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
