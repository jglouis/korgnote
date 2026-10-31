package com.korgnote.common.org.agenda

import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.DEFAULT_TODO_KEYWORDS
import com.korgnote.common.org.orgElements
import com.korgnote.common.org.orgHeadingOf
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.corpusTodoKeywords
import com.korgnote.common.org.todoKeywordsOf
import com.korgnote.common.org.search.fileTagsOf
import com.korgnote.common.org.sliceOf
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.monthsUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.Serializable

/** Where an entry's date came from. */
enum class AgendaKind {
    /** `SCHEDULED:` — when to start working on it. */
    Scheduled,

    /** `DEADLINE:` — when it is due. */
    Deadline,

    /** An active stamp anywhere else under the heading, or in the heading itself: an appointment. */
    Timestamp,

    /** `CLOSED:` — when it was done. Only shown when asked for. */
    Closed,
}

/**
 * One dated thing: a heading together with one of its stamps.
 *
 * A heading carrying both a `SCHEDULED` and a `DEADLINE` is two entries, because the two are shown
 * differently and on different days.
 *
 * [headingOffset] is where the heading starts in its file, which is the coordinate the rest of the
 * app opens a document at — the same one a search hit carries.
 */
@Serializable
data class AgendaEntry(
    val file: String,
    val headingOffset: Int,
    val title: String,
    /** The TODO keyword, if the heading has one its file declares — or, declaring none, the corpus. */
    val todo: String?,
    val done: Boolean,
    /** The heading's own tags, then those it inherits from its parents and from `#+FILETAGS:`. */
    val tags: List<String>,
    val priority: Char?,
    val kind: AgendaKind,
    val stamp: OrgTimestamp,
)

/** Which part of a multi-day range an occurrence is, so a view can draw one continuous bar. */
enum class DaySpan { Single, Start, Middle, End }

/**
 * An entry on one particular day.
 *
 * [daysUntilDeadline] is set for deadlines only: 0 on the day itself, positive inside the warning
 * window, negative when an overdue deadline is carried to today.
 *
 * [isCarriedOver] marks an unfinished scheduled item or deadline from the past, shown on today
 * rather than on its own date — org-agenda's behaviour, and optional here.
 */
data class AgendaOccurrence(
    val entry: AgendaEntry,
    val date: LocalDate,
    val time: LocalTime?,
    val endTime: LocalTime?,
    val daysUntilDeadline: Int? = null,
    val isCarriedOver: Boolean = false,
    val span: DaySpan = DaySpan.Single,
) {
    /**
     * True when this is the entry on its own date: not a deadline's advance warning, and not
     * something overdue brought forward to today. A plain calendar shows exactly these.
     */
    val isOnOwnDate: Boolean get() = !isCarriedOver && (daysUntilDeadline == null || daysUntilDeadline == 0)
}

data class AgendaOptions(
    val today: LocalDate,
    /** Show unfinished past scheduled items and deadlines again on today. */
    val carryOver: Boolean = true,
    /** Include inactive `[…]` stamps from heading bodies. */
    val includeInactive: Boolean = false,
    /** Include `CLOSED:` entries on the day they were closed. */
    val includeClosed: Boolean = false,
    /** Warning period for a deadline that does not give one — `org-deadline-warning-days`. */
    val deadlineWarningDays: Int = 14,
)

// --- Finding entries ---------------------------------------------------------------------------

/**
 * Every entry in every file.
 *
 * A file that declares no keywords of its own is read with [keywords] — by default, those the
 * whole corpus declares, so that a `FINISHED` entry in a note that relies on the editor's global
 * setting is still finished.
 */
fun agendaEntries(
    files: Map<String, String>,
    keywords: OrgTodoConfig = corpusTodoKeywords(files.values),
): List<AgendaEntry> =
    files.keys.sorted().flatMap { agendaEntries(it, files.getValue(it), keywords) }

/**
 * Every dated heading in one file.
 *
 * Walks the same element split as the editor and search, so a stamp inside a source block, an
 * example block or a drawer is not an appointment: those are verbatim text. Text before the first
 * heading has no heading to attach a date to, and is passed over.
 */
fun agendaEntries(path: String, text: String, fallback: OrgTodoConfig = DEFAULT_TODO_KEYWORDS): List<AgendaEntry> {
    // A file without a stamp has nothing to offer, and most notes have none.
    if (!OrgSyntax.timestamp.containsMatchIn(text)) return emptyList()

    val todoConfig = todoKeywordsOf(text, fallback)
    val fileTags = fileTagsOf(text)
    val ancestors = mutableListOf<Pair<Int, List<String>>>()
    val entries = mutableListOf<AgendaEntry>()

    for (element in text.orgElements()) {
        if (element.isPreamble) continue
        val slice = element.sliceOf(text)
        val heading = orgHeadingOf(slice.substringBefore('\n'), todoConfig)
        while (ancestors.isNotEmpty() && ancestors.last().first >= element.level) ancestors.removeAt(ancestors.size - 1)
        ancestors += element.level to heading.tags
        if (!OrgSyntax.timestamp.containsMatchIn(slice)) continue

        val keyword = heading.todo
        val inherited = ancestors.dropLast(1).flatMap { it.second } + fileTags
        val title = heading.title.withoutStamps()
        val tags = (heading.tags + inherited).distinct()
        fun entry(kind: AgendaKind, stamp: OrgTimestamp) = AgendaEntry(
            file = path,
            headingOffset = element.start,
            title = title,
            todo = keyword,
            done = keyword != null && keyword in todoConfig.done,
            tags = tags,
            priority = heading.priority,
            kind = kind,
            stamp = stamp,
        )

        forEachStampedLine(slice) { lineStart, line, isHeadingLine ->
            val absolute = element.start + lineStart
            val planning = if (isHeadingLine) null else OrgSyntax.planning.find(line)
            if (planning != null) {
                // One planning line can carry several keywords: `SCHEDULED: <…> DEADLINE: <…>`.
                val keywords = OrgSyntax.planningKeyword.findAll(line).toList()
                keywords.forEachIndexed { i, kw ->
                    val segmentEnd = keywords.getOrNull(i + 1)?.range?.first ?: line.length
                    val stamp = line.substring(kw.range.last + 1, segmentEnd)
                        .orgTimestamps(absolute + kw.range.last + 1).firstOrNull() ?: return@forEachIndexed
                    val kind = when (kw.groupValues[1]) {
                        "SCHEDULED" -> AgendaKind.Scheduled
                        "DEADLINE" -> AgendaKind.Deadline
                        else -> AgendaKind.Closed
                    }
                    entries += entry(kind, stamp)
                }
            } else {
                line.orgTimestamps(absolute).forEach { entries += entry(AgendaKind.Timestamp, it) }
            }
        }
    }
    return entries
}

/**
 * Calls [action] for each line of [element] that can carry a stamp: the heading line and body lines
 * outside blocks and drawers.
 */
private inline fun forEachStampedLine(element: String, action: (lineStart: Int, line: String, isHeadingLine: Boolean) -> Unit) {
    var blockType: String? = null
    var inDrawer = false
    var lineStart = 0
    var first = true
    while (lineStart <= element.length) {
        val newline = element.indexOf('\n', lineStart)
        val lineEnd = if (newline < 0) element.length else newline
        val line = element.substring(lineStart, lineEnd)
        when {
            first -> action(lineStart, line, true)
            blockType != null -> {
                val end = OrgSyntax.blockEnd.find(line)
                if (end != null && (end.groupValues[1].isEmpty() || end.groupValues[1].equals(blockType, ignoreCase = true))) {
                    blockType = null
                }
            }

            inDrawer -> if (OrgSyntax.drawerEnd.matches(line)) inDrawer = false
            else -> {
                val begin = OrgSyntax.blockBegin.find(line)
                when {
                    begin != null -> blockType = begin.groupValues[1]
                    OrgSyntax.drawerBegin.matches(line) && !OrgSyntax.drawerEnd.matches(line) -> inDrawer = true
                    // Cheap filter before the regex: every stamp starts with a digit after a bracket.
                    line.contains('<') || line.contains('[') -> action(lineStart, line, false)
                }
            }
        }
        first = false
        if (newline < 0) break
        lineStart = newline + 1
    }
}

/** The title as it should read in a list: a stamp in the heading is shown as the date, not as text. */
private fun String.withoutStamps(): String =
    // A range's two stamps go with the `--` between them, which means nothing left on its own.
    OrgSyntax.timestamp.replace(replace(STAMP_RANGE, ""), "").replace(Regex("\\s{2,}"), " ").trim()

private val STAMP_RANGE = Regex("(?:${OrgSyntax.timestamp.pattern})--(?:${OrgSyntax.timestamp.pattern})")

// --- Expanding entries into days ---------------------------------------------------------------

/** Every occurrence of every entry in [files] between [from] and [to], both inclusive. */
fun agenda(files: Map<String, String>, from: LocalDate, to: LocalDate, options: AgendaOptions): List<AgendaOccurrence> =
    agendaEntries(files).occurrences(from, to, options)

/**
 * Every occurrence of these entries between [from] and [to], both inclusive, ordered by date, then
 * all-day before timed, then by time, then by file and position.
 */
fun List<AgendaEntry>.occurrences(from: LocalDate, to: LocalDate, options: AgendaOptions): List<AgendaOccurrence> {
    if (to < from) return emptyList()
    val result = mutableListOf<AgendaOccurrence>()
    for (entry in this) entry.expandInto(result, from, to, options)
    return result.sortedWith(
        compareBy<AgendaOccurrence> { it.date }
            .thenBy { it.time != null }
            .thenBy { it.time }
            .thenBy { it.entry.file }
            .thenBy { it.entry.stamp.range.first },
    )
}

private fun AgendaEntry.expandInto(into: MutableList<AgendaOccurrence>, from: LocalDate, to: LocalDate, options: AgendaOptions) {
    val shown = when (kind) {
        AgendaKind.Closed -> options.includeClosed
        AgendaKind.Timestamp -> stamp.active || options.includeInactive
        // Org ignores an inactive stamp after SCHEDULED or DEADLINE, and so does the agenda.
        AgendaKind.Scheduled, AgendaKind.Deadline -> stamp.active
    }
    if (!shown) return
    val today = options.today

    // A done entry that repeats is not over: what is left of it is the next time it comes round.
    if (done && stamp.repeater != null) {
        val next = stamp.repeats(onOrAfter = today).firstOrNull() ?: return
        if (next.date in from..to) into += AgendaOccurrence(this, next.date, next.time, stamp.endTime, deadlineCountdown(0))
        return
    }

    when (kind) {
        AgendaKind.Timestamp, AgendaKind.Closed -> {
            val spanDays = stamp.spanDays
            for (repeat in stamp.repeats(onOrAfter = from.minus(spanDays, DateTimeUnit.DAY))) {
                if (repeat.date > to) break
                addDays(into, repeat, spanDays, from, to)
            }
        }

        AgendaKind.Scheduled -> {
            val delay = stamp.delay
            val maxDelayDays = delay?.let { stamp.start.daysUntil(stamp.start.plus(it.by, 1)) } ?: 0
            // `SCHEDULED: <a>--<b>` is how a trip or a week off is usually written, and it is on
            // every day it covers, not only the first. org-agenda shows only the first.
            val spanDays = stamp.spanDays
            var firstShown: LocalDate? = null
            for (repeat in stamp.repeats(onOrAfter = from.minus(maxDelayDays + spanDays, DateTimeUnit.DAY))) {
                if (repeat.date > to) break
                val shift = delay?.takeIf { !it.firstOnly || repeat.index == 0 }?.by
                val date = shift?.let { repeat.date.plus(it, 1) } ?: repeat.date
                if (repeat.index == 0) firstShown = date
                addDays(into, repeat.copy(date = date), spanDays, from, to)
            }
            // Not yet done and already due: org-agenda keeps it in front of you, on today. A range
            // still running is on today already, and the check below keeps it from showing twice.
            val start = firstShown ?: stamp.start.plus(delay?.by ?: OrgDuration(0, OrgTimeUnit.DAY), 1)
            if (options.carryOver && !done && start < today && today in from..to &&
                into.none { it.entry === this && it.date == today }
            ) {
                into += AgendaOccurrence(this, today, null, null, isCarriedOver = true)
            }
        }

        AgendaKind.Deadline -> {
            for (repeat in stamp.repeats(onOrAfter = from)) {
                if (repeat.date > to) break
                into += AgendaOccurrence(this, repeat.date, repeat.time, stamp.endTime, daysUntilDeadline = 0)
            }
            if (done) return
            // Missed and not done: today says so, below, and a warning of the next repeat would only
            // hide that — a repeating deadline's date moves on when it is done, not when it passes.
            val missed = options.carryOver && stamp.start < today
            // Advance warning for the next deadline to come — not for every repeat of it, or a daily
            // deadline would fill each day with a fortnight of copies of itself.
            val next = if (missed) null else stamp.repeats(onOrAfter = today).firstOrNull()
            if (next != null) {
                val warning = stamp.delay?.takeIf { !it.firstOnly || next.index == 0 }?.by
                    ?: OrgDuration(options.deadlineWarningDays, OrgTimeUnit.DAY)
                var day = maxOf(today, next.date.minus(warning), from)
                while (day < next.date && day <= to) {
                    into += AgendaOccurrence(this, day, null, null, daysUntilDeadline = day.daysUntil(next.date))
                    day = day.plus(1, DateTimeUnit.DAY)
                }
            }
            // Missed: shown on today, counting how late it is, until it is done.
            if (missed && today in from..to &&
                into.none { it.entry === this && it.date == today }
            ) {
                into += AgendaOccurrence(
                    this, today, null, null,
                    daysUntilDeadline = today.daysUntil(stamp.start),
                    isCarriedOver = true,
                )
            }
        }
    }
}

private fun AgendaEntry.deadlineCountdown(days: Int): Int? = if (kind == AgendaKind.Deadline) days else null

/** How many days a multi-day range runs past its first: 0 for anything within one day. */
private val OrgTimestamp.spanDays: Int get() = end?.let { start.daysUntil(it) } ?: 0

private fun AgendaEntry.addDays(into: MutableList<AgendaOccurrence>, repeat: Repeat, spanDays: Int, from: LocalDate, to: LocalDate) {
    for (offset in 0..spanDays) {
        val date = repeat.date.plus(offset, DateTimeUnit.DAY)
        if (date < from) continue
        if (date > to) break
        val span = when {
            spanDays == 0 -> DaySpan.Single
            offset == 0 -> DaySpan.Start
            offset == spanDays -> DaySpan.End
            else -> DaySpan.Middle
        }
        into += AgendaOccurrence(
            entry = this,
            date = date,
            // The first day starts at the stamp's time and the last ends at its end time; the days
            // between are all-day.
            time = if (offset == 0) repeat.time else null,
            endTime = if (offset == spanDays) stamp.endTime else null,
            daysUntilDeadline = deadlineCountdown(0),
            span = span,
        )
    }
}

// --- Repeats -----------------------------------------------------------------------------------

/** The [index]th occurrence of a stamp: its date, and its time when it has one. */
internal data class Repeat(val index: Int, val date: LocalDate, val time: LocalTime?)

/** Hard stop on how many repeats one stamp can produce, however wide the range asked for. */
private const val MAX_REPEATS = 5_000

/**
 * The stamp's occurrences dated on or after [onOrAfter], in order: just the stamp itself when it
 * does not repeat, and otherwise every interval from its date, starting from the first one that
 * reaches [onOrAfter] rather than walking there from the stamp's date.
 */
internal fun OrgTimestamp.repeats(onOrAfter: LocalDate): Sequence<Repeat> {
    val every = repeater?.every?.takeIf { it.amount > 0 }
        ?: return if (start >= onOrAfter) sequenceOf(Repeat(0, start, startTime)) else emptySequence()

    if (every.unit == OrgTimeUnit.HOUR) {
        // Hourly repeats move the time, and past midnight the date with it; UTC is only the
        // arithmetic here, so no daylight-saving jump can shift a wall-clock time.
        val origin = LocalDateTime(start, startTime ?: LocalTime(0, 0)).toInstant(TimeZone.UTC)
        val stepMinutes = every.amount * 60L
        val target = LocalDateTime(onOrAfter, LocalTime(0, 0)).toInstant(TimeZone.UTC)
        val first = ((target - origin).inWholeMinutes / stepMinutes).coerceAtLeast(0).toInt()
        return generateSequence(first) { it + 1 }.take(MAX_REPEATS).map { k ->
            val at = (origin + (stepMinutes * k).minutes).toLocalDateTime(TimeZone.UTC)
            Repeat(k, at.date, at.time)
        }.filter { it.date >= onOrAfter }
    }

    // Roughly where to begin, never past the first repeat that is due: each date is computed from
    // the stamp's own date rather than from the previous repeat, so the 31st repeated monthly is
    // the 30th in a short month and the 31st again after it.
    val first = when (every.unit) {
        OrgTimeUnit.DAY -> start.daysUntil(onOrAfter) / every.amount
        OrgTimeUnit.WEEK -> start.daysUntil(onOrAfter) / (every.amount * 7)
        OrgTimeUnit.MONTH -> start.monthsUntil(onOrAfter) / every.amount - 1
        OrgTimeUnit.YEAR -> start.monthsUntil(onOrAfter) / (every.amount * 12) - 1
        OrgTimeUnit.HOUR -> 0
    }.coerceAtLeast(0)
    return generateSequence(first) { it + 1 }.take(MAX_REPEATS)
        .map { k -> Repeat(k, start.plus(every, k), startTime) }
        .filter { it.date >= onOrAfter }
}

/** [this] moved on by [times] lots of [duration]. Hours count whole days only. */
private fun LocalDate.plus(duration: OrgDuration, times: Int): LocalDate {
    val n = duration.amount * times
    return when (duration.unit) {
        OrgTimeUnit.HOUR -> plus(n / 24, DateTimeUnit.DAY)
        OrgTimeUnit.DAY -> plus(n, DateTimeUnit.DAY)
        OrgTimeUnit.WEEK -> plus(n * 7, DateTimeUnit.DAY)
        OrgTimeUnit.MONTH -> plus(n, DateTimeUnit.MONTH)
        OrgTimeUnit.YEAR -> plus(n, DateTimeUnit.YEAR)
    }
}

private fun LocalDate.minus(duration: OrgDuration): LocalDate = plus(duration.copy(amount = -duration.amount), 1)

// --- The index the app keeps -------------------------------------------------------------------

/**
 * The entries of the whole corpus, with occurrences computed a month at a time as a view asks for
 * them.
 *
 * Built once per change to the notes; expanding repeaters is deferred, because a view only ever
 * looks at a few months and a daily repeater has an occurrence on every one of the others.
 * Not thread-safe: it is read from the UI thread.
 */
class AgendaIndex(val entries: List<AgendaEntry>, val options: AgendaOptions) {
    private val months = mutableMapOf<Int, Map<LocalDate, List<AgendaOccurrence>>>()

    /** Occurrences on [date], in the order [occurrences] gives. */
    fun on(date: LocalDate): List<AgendaOccurrence> {
        val key = date.year * 12 + date.month.ordinal
        val month = months.getOrPut(key) {
            val first = LocalDate(date.year, date.month, 1)
            val last = first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
            entries.occurrences(first, last, options).groupBy { it.date }
        }
        return month[date].orEmpty()
    }

    /** Occurrences between [from] and [to], both inclusive. */
    fun between(from: LocalDate, to: LocalDate): List<AgendaOccurrence> = entries.occurrences(from, to, options)

    /** The same entries, carried over and warned of as on [today]; this one when it already is. */
    fun asOf(today: LocalDate): AgendaIndex =
        if (today == options.today) this else AgendaIndex(entries, options.copy(today = today))

    companion object {
        fun of(files: Map<String, String>, options: AgendaOptions) = AgendaIndex(agendaEntries(files), options)
        val EMPTY = AgendaIndex(emptyList(), AgendaOptions(today = LocalDate(1970, 1, 1)))
    }
}
