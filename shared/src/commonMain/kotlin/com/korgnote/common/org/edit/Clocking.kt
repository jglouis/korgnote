package com.korgnote.common.org.edit

import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgDrawer
import com.korgnote.common.org.OrgPlanning
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.complete.orgStamp
import com.korgnote.common.org.toOrgDocument
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Org's clocking (`C-c C-x C-i`, `C-c C-x C-o`): the time spent on a heading, written into the file
 * as Emacs writes it, so that either side sums what the other clocked.
 *
 * ```
 * :LOGBOOK:
 * CLOCK: [2026-11-01 Sun 14:05]
 * CLOCK: [2026-11-01 Sun 09:30]--[2026-11-01 Sun 10:45] =>  1:15
 * :END:
 * ```
 *
 * A running clock is a `CLOCK:` line with only a start. The file is the only place it is kept, so
 * it follows the note through sync, to another device and into Emacs.
 *
 * Times are wall-clock, as in the stamps: a clock across a daylight-saving change is off by the
 * hour, as Emacs's is.
 */
data class OrgClock(
    val start: LocalDateTime,
    /** Null while the clock runs. */
    val end: LocalDateTime?,
    /** The `CLOCK:` line in the document, without its line break. */
    val line: IntRange,
) {
    val isRunning: Boolean get() = end == null

    /** Minutes clocked: to [end], or to [now] while it runs; never fewer than none. */
    fun minutes(now: LocalDateTime): Int = minutesBetween(start, end ?: now).coerceAtLeast(0)
}

/** A clock running somewhere in a document: on which heading, by where it starts, and since when. */
data class RunningClock(val headingStart: Int, val title: String, val start: LocalDateTime)

/** The clocks in [heading]'s own entry — its line up to its first sub-heading — newest first, as written. */
fun OrgDocument.clocksOf(heading: OrgSection): List<OrgClock> {
    if (heading.range.isEmpty()) return emptyList()
    val start = heading.range.first
    val end = heading.children.firstOrNull { it is OrgSection && !it.range.isEmpty() }?.range?.first
        ?: (heading.range.last + 1)
    return clocksIn(start, end)
}

/**
 * The time clocked on [heading] and everything under it, in minutes, a running clock counted to
 * [now]: what Emacs's `C-c C-x C-d` shows beside the heading.
 */
fun OrgDocument.clockedMinutes(heading: OrgSection, now: LocalDateTime): Int {
    if (heading.range.isEmpty()) return 0
    return clocksIn(heading.range.first, heading.range.last + 1).sumOf { it.minutes(now) }
}

/** Every clock running in this document, in the order of their headings. */
fun OrgDocument.runningClocks(): List<RunningClock> =
    allNodes().filterIsInstance<OrgSection>().flatMap { heading ->
        clocksOf(heading).filter { it.isRunning }.map { RunningClock(heading.range.first, heading.title, it.start) }
    }

/** A heading clocked before, by where it starts, and when its last clock stopped. */
data class LastClocked(val headingStart: Int, val title: String, val end: LocalDateTime)

/**
 * Every heading in this document with a stopped clock, and the latest of its stops: what Emacs's
 * clock history offers to clock back into.
 */
fun OrgDocument.lastClocked(): List<LastClocked> =
    allNodes().filterIsInstance<OrgSection>().mapNotNull { heading ->
        val end = clocksOf(heading).mapNotNull { it.end }.maxOrNull() ?: return@mapNotNull null
        LastClocked(heading.range.first, heading.title, end)
    }

/** The heading's `:Effort:` property, as written (`1:30`, `2h`), or null when it has none. */
val OrgSection.effort: String?
    get() = children.filterIsInstance<OrgDrawer>()
        .firstOrNull { it.name.equals("PROPERTIES", ignoreCase = true) }
        ?.properties?.firstOrNull { it.first.equals("Effort", ignoreCase = true) }
        ?.second?.trim()?.takeIf { it.isNotEmpty() }

/**
 * [heading] with its effort set to [minutes], as `org-set-effort` writes it — `:Effort: 1:30` in its
 * property drawer, made when it has none — or, for null, without it, and without the drawer when
 * nothing else was in it.
 */
fun OrgDocument.setEffort(heading: OrgSection, minutes: Int?): OrgDocument {
    if (minutes != null) return setProperty(heading, "Effort", "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}")
    val drawer = heading.children.filterIsInstance<OrgDrawer>()
        .firstOrNull { it.name.equals("PROPERTIES", ignoreCase = true) && !it.unterminated && !it.range.isEmpty() }
        ?: return this
    val rest = drawer.properties.filterNot { it.first.equals("Effort", ignoreCase = true) }
    if (rest.size == drawer.properties.size) return this
    // Nothing else in it: the drawer goes with it.
    if (rest.isEmpty() && drawer.lines.count { it.isNotBlank() } == 1) {
        return replace(drawer.range.first..drawer.range.last.coerceAtMost(text.length - 1), "")
    }
    var lineStart = text.indexOf('\n', drawer.range.first) + 1
    while (lineStart in 1..drawer.range.last) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        if (text.substring(lineStart, lineEnd).trim().startsWith(":Effort:", ignoreCase = true)) {
            return replace(lineStart..minOf(lineEnd, text.length - 1), "")
        }
        lineStart = lineEnd + 1
    }
    return this
}

/**
 * [heading] clocked in [at], as `org-clock-in` does: a `CLOCK:` line at the top of its `:LOGBOOK:`,
 * the drawer made after the planning line and the property drawer when it has none. A heading
 * whose clock already runs is left as it is.
 *
 * A clock running on another heading is not this function's business: the caller clocks it out
 * first, as Emacs does, since it may be in another note.
 */
fun OrgDocument.clockIn(heading: OrgSection, at: LocalDateTime): OrgDocument {
    if (heading.range.isEmpty() || clocksOf(heading).any { it.isRunning }) return this
    val entry = "CLOCK: ${at.orgStamp(active = false)}"
    logbookOf(heading)?.let { drawer ->
        val opening = drawer.range.first
        val indent = text.substring(opening).takeWhile { it == ' ' || it == '\t' }
        // Newest first: straight after the drawer's opening line.
        return insertLines(text.indexOf('\n', opening) + 1, "$indent$entry\n")
    }
    var insertAt = text.indexOf('\n', heading.range.first).let { if (it < 0) text.length else it + 1 }
    var indent = ""
    for (child in heading.children) {
        if (child.range.isEmpty() || child.range.first != insertAt) break
        if (child is OrgPlanning || (child is OrgDrawer && child.name.equals("PROPERTIES", ignoreCase = true))) {
            indent = text.substring(child.range.first).takeWhile { it == ' ' || it == '\t' }
            insertAt = child.range.last + 1
            continue
        }
        break
    }
    return insertLines(insertAt, "$indent:LOGBOOK:\n$indent$entry\n$indent:END:\n")
}

/**
 * [heading]'s running clock stopped at [at], as `org-clock-out` writes it:
 * `CLOCK: [start]--[end] =>  1:15`. A heading with no running clock is left as it is. A stop
 * before the start — the clock started on another device whose clock runs ahead — stops it at its
 * start.
 */
fun OrgDocument.clockOut(heading: OrgSection, at: LocalDateTime): OrgDocument {
    val clock = clocksOf(heading).firstOrNull { it.isRunning } ?: return this
    val end = if (at < clock.start) clock.start else at
    val line = text.substring(clock.line.first, clock.line.last + 1)
    val written = CLOCK_LINE.matchEntire(line) ?: return this
    val indent = written.groupValues[1]
    val start = written.groupValues[2]
    return replace(clock.line, "${indent}CLOCK: $start--${end.orgStamp(active = false)} => ${duration(minutesBetween(clock.start, end))}")
}

/**
 * [heading] without its running clock, as `org-clock-cancel` leaves it: the open line gone, and
 * the drawer with it when nothing else is in it.
 */
fun OrgDocument.cancelClock(heading: OrgSection): OrgDocument {
    val clock = clocksOf(heading).firstOrNull { it.isRunning } ?: return this
    val start = heading.range.first
    val lineEnd = (clock.line.last + 1).let { if (it < text.length) it else it - 1 }
    val removed = replace(clock.line.first..lineEnd, "")
    val after = removed.nodeAt<OrgSection>(start)?.takeIf { it.range.first == start } ?: return removed
    val drawer = removed.logbookOf(after) ?: return removed
    if (drawer.lines.any { it.isNotBlank() }) return removed
    val drawerEnd = (drawer.range.last).coerceAtMost(removed.text.length - 1)
    return removed.replace(drawer.range.first..drawerEnd, "")
}

/**
 * [text] with each closed `CLOCK:` line's `=> H:MM` saying what its stamps now span, as Emacs's
 * `C-c C-c` on the line leaves it — only over what changed since [before], as statistics cookies
 * are brought up to date: a clock edited by hand is summed again, and the rest of the note stays
 * as it was.
 */
fun updateClockDurations(text: String, before: String? = null): String {
    if (!text.contains("CLOCK:")) return text
    val changed = before?.let { changedRegion(it, text) }
    if (before != null && changed == null) return text
    val from = changed?.start ?: 0
    val to = changed?.end ?: text.length
    val edits = mutableListOf<Pair<IntRange, String>>()
    var lineStart = from
    while (lineStart < to) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val match = CLOCK_LINE.matchEntire(line)
        val endStamp = match?.groupValues?.get(3)?.takeIf { it.isNotEmpty() }
        if (match != null && endStamp != null) {
            val start = stampTime(match.groupValues[2])
            val end = stampTime(endStamp)
            if (start != null && end != null) {
                val minutes = minutesBetween(start, end)
                val sum = if (minutes < 0) "-${-minutes / 60}:${(-minutes % 60).toString().padStart(2, '0')}" else duration(minutes)
                val rewritten = "${match.groupValues[1]}CLOCK: ${match.groupValues[2]}--$endStamp => $sum"
                if (rewritten != line) edits += (lineStart until lineEnd) to rewritten
            }
        }
        lineStart = lineEnd + 1
    }
    var updated = text
    for ((range, replacement) in edits.asReversed()) updated = updated.replaceRange(range, replacement)
    return updated
}

/** Minutes as a `CLOCK:` line sums them, `%2d:%02d`: ` 1:15`, `10:15`, `26:00`. */
private fun duration(minutes: Int): String =
    "${(minutes / 60).toString().padStart(2)}:${(minutes % 60).toString().padStart(2, '0')}"

/**
 * An `:Effort:` in minutes, as Org reads the usual ones: `1:30`, a bare `45` (minutes), `2h`,
 * `30min`, `1h 30min`. Null for anything else, which is then shown as written.
 */
fun effortMinutes(effort: String): Int? {
    val text = effort.trim()
    HOURS_MINUTES.matchEntire(text)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
    text.toIntOrNull()?.let { return it.takeIf { it >= 0 } }
    val units = UNITS.matchEntire(text) ?: return null
    val hours = units.groupValues[1].toIntOrNull() ?: 0
    val minutes = units.groupValues[2].toIntOrNull() ?: 0
    return if (units.groupValues[1].isEmpty() && units.groupValues[2].isEmpty()) null else hours * 60 + minutes
}

private val HOURS_MINUTES = Regex("^(\\d+):([0-5]\\d)$")
private val UNITS = Regex("^(?:(\\d+)\\s*h)?\\s*(?:(\\d+)\\s*min)?$")

/**
 * The clocks written anywhere in [text], whatever heading they belong to: for a heading's subtree,
 * as the reading view has it, its total without parsing it.
 */
fun clocksInText(text: String): List<OrgClock> = text.clocksIn(0, text.length)

/** The `:Effort:` written in [entry], a heading's own text, or null when there is none. */
fun effortIn(entry: String): String? =
    EFFORT.find(entry)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

private val EFFORT = Regex("^[ \\t]*:Effort:[ \\t]*(.*)$", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

private fun OrgDocument.clocksIn(from: Int, to: Int): List<OrgClock> = text.clocksIn(from, to)

/** The clocks between [from] and [to], whatever heading they belong to. */
private fun String.clocksIn(from: Int, to: Int): List<OrgClock> {
    val text = this
    if (!text.regionMatchesAnywhere("CLOCK:", from, to)) return emptyList()
    val clocks = mutableListOf<OrgClock>()
    var lineStart = from
    while (lineStart < to) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0 || it > to) minOf(to, text.length) else it }
        val match = CLOCK_LINE.matchEntire(text.substring(lineStart, lineEnd))
        if (match != null) {
            val start = stampTime(match.groupValues[2])
            val end = match.groupValues[3].takeIf { it.isNotEmpty() }?.let(::stampTime)
            // A closed line whose end will not parse is not a running clock: it is left out.
            if (start != null && (end != null || match.groupValues[3].isEmpty())) {
                clocks += OrgClock(start, end, lineStart until lineEnd)
            }
        }
        lineStart = lineEnd + 1
    }
    return clocks
}

private fun String.regionMatchesAnywhere(word: String, from: Int, to: Int): Boolean {
    val at = indexOf(word, from)
    return at in from until to
}

/** [heading]'s own `:LOGBOOK:` drawer, wherever it is in its entry, when it is terminated. */
private fun OrgDocument.logbookOf(heading: OrgSection): OrgDrawer? =
    heading.children.filterIsInstance<OrgDrawer>()
        .firstOrNull { it.name.equals("LOGBOOK", ignoreCase = true) && !it.unterminated && !it.range.isEmpty() }

private fun minutesBetween(start: LocalDateTime, end: LocalDateTime): Int =
    ((end.toInstant(TimeZone.UTC) - start.toInstant(TimeZone.UTC)).inWholeMinutes).toInt()

/** `[2026-11-01 Sun 14:05]`, whatever the day's name is written in, as a time; null without a time. */
private fun stampTime(stamp: String): LocalDateTime? {
    val m = CLOCK_STAMP.matchEntire(stamp) ?: return null
    val (year, month, day, hour, minute) = m.destructured
    return runCatching { LocalDateTime(year.toInt(), month.toInt(), day.toInt(), hour.toInt(), minute.toInt()) }.getOrNull()
}

/** A `CLOCK:` line: its indent (1), its start stamp (2), and its end stamp (3) when it has stopped. */
private val CLOCK_LINE =
    Regex("^([ \\t]*)CLOCK:[ \\t]*(\\[[^\\]\\n]*\\])(?:--(\\[[^\\]\\n]*\\])(?:[ \\t]*=>[ \\t]*[^\\n]*)?)?[ \\t]*$")

private val CLOCK_STAMP = Regex("^\\[(\\d{4})-(\\d{2})-(\\d{2})(?:[ \\t]+[^\\]\\s\\d]+)?[ \\t]+(\\d{1,2}):(\\d{2})\\]$")
