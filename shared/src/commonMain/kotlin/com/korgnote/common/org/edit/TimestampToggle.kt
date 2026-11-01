package com.korgnote.common.org.edit

import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.agenda.OrgTimestamp
import com.korgnote.common.org.agenda.forEachStampedLine
import com.korgnote.common.org.agenda.orgTimestamps
import com.korgnote.common.org.agenda.parseOrgTimestamp

/**
 * [stamp] — one stamp, or a `<a>--<b>` range — made inactive when it is active and active when it
 * is not, as Org's `org-toggle-timestamp-type` does: only the brackets change. Both halves of a
 * range change together, since a range whose halves differ is two stamps, not one range. Null when
 * [stamp] is not a timestamp.
 */
fun toggledTimestamp(stamp: String): String? {
    val parsed = parseOrgTimestamp(stamp) ?: return null
    val (open, close) = if (parsed.active) '[' to ']' else '<' to '>'
    val chars = stamp.toCharArray()
    for (half in OrgSyntax.timestamp.findAll(stamp)) {
        chars[half.range.first] = open
        chars[half.range.last] = close
    }
    return chars.concatToString()
}

/**
 * This text with the stamp that begins at [offset] toggled between active and inactive — the whole
 * range, when it is one — or null when no stamp begins there.
 */
fun String.withTimestampToggledAt(offset: Int): String? {
    if (offset !in indices) return null
    val lineStart = lastIndexOf('\n', offset - 1) + 1
    val lineEnd = indexOf('\n', offset).let { if (it < 0) length else it }
    val stamp = substring(lineStart, lineEnd).orgTimestamps(lineStart).firstOrNull { it.range.first == offset } ?: return null
    return replaceRange(stamp.range, toggledTimestamp(substring(stamp.range)) ?: return null)
}

/**
 * The stamps of one element that are the writer's to make active or inactive: those in its heading
 * and its text, in order.
 *
 * Planning lines are left out — a `SCHEDULED` or `DEADLINE` stamp is active and a `CLOSED` one is
 * inactive by definition — and so are drawers, whose stamps are a logbook's record, and blocks,
 * whose text is verbatim. Offsets are into the element.
 */
fun String.toggleableTimestamps(): List<OrgTimestamp> = buildList {
    if (!OrgSyntax.timestamp.containsMatchIn(this@toggleableTimestamps)) return@buildList
    val startsWithHeading = OrgSyntax.heading.containsMatchIn(substringBefore('\n'))
    forEachStampedLine(this@toggleableTimestamps, startsWithHeading) { lineStart, line, isHeadingLine ->
        if (isHeadingLine || !OrgSyntax.planning.containsMatchIn(line)) addAll(line.orgTimestamps(lineStart))
    }
}
