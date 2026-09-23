package com.korgnote.common.org.agenda

import com.korgnote.common.org.OrgSyntax
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** The units Org counts repeaters and delays in: `h`, `d`, `w`, `m`, `y`. */
enum class OrgTimeUnit { HOUR, DAY, WEEK, MONTH, YEAR }

/** An amount of [unit]s, such as the `1w` in `+1w`. */
data class OrgDuration(val amount: Int, val unit: OrgTimeUnit)

/**
 * How a repeating entry moves once it is marked done.
 *
 * All three repeat the same way in the agenda: every [OrgRepeater.every] from the stamp's date. They
 * only differ in where Org puts the next date on completion, which is the editor's business, not
 * the agenda's — but the agenda has to keep them apart so that rewriting the stamp can.
 */
enum class RepeatKind {
    /** `+1w`: shift by exactly one interval. */
    Cumulate,

    /** `++1w`: shift by whole intervals until the date is in the future. */
    CatchUp,

    /** `.+1d`: shift one interval from the day it was completed. */
    Restart,
}

data class OrgRepeater(val kind: RepeatKind, val every: OrgDuration)

/**
 * The `-2d` of a stamp.
 *
 * What it means depends on the planning keyword the stamp sits under: on a `DEADLINE` it is the
 * warning period — start showing the deadline this early — and on a `SCHEDULED` it is a delay —
 * do not show the entry until this long after its date. [firstOnly] is the `--2d` form, which on a
 * repeating stamp applies to the first occurrence only.
 */
data class OrgDelay(val by: OrgDuration, val firstOnly: Boolean)

/**
 * An Org timestamp, such as `<2026-09-20 Sun 10:00-11:30 +1w>` or `<2026-09-20>--<2026-09-22>`.
 *
 * [end] is set only for a range that spans several days; a range within one day keeps [end] null
 * and carries only [endTime]. For a multi-day range, [startTime] belongs to the first day and
 * [endTime] to the last.
 *
 * [range] is where the stamp sits in the text it was found in, so that rescheduling an entry is a
 * replacement over exactly those characters.
 */
data class OrgTimestamp(
    val start: LocalDate,
    val startTime: LocalTime? = null,
    val end: LocalDate? = null,
    val endTime: LocalTime? = null,
    val active: Boolean = true,
    val repeater: OrgRepeater? = null,
    val delay: OrgDelay? = null,
    val range: IntRange = IntRange.EMPTY,
)

/**
 * Parses [text] when all of it is one timestamp or one `<a>--<b>` range, else returns null.
 *
 * Offsets in the result's [OrgTimestamp.range] are relative to [text].
 */
fun parseOrgTimestamp(text: String): OrgTimestamp? =
    text.orgTimestamps().singleOrNull()?.takeIf { it.range == text.indices }

/**
 * Every timestamp in this text, in order.
 *
 * [offset] is added to each [OrgTimestamp.range], so a caller scanning one line of a document can
 * get ranges into the whole document without handing over the whole document: a regex search from
 * the middle of a file scans on to the next match wherever it is, and doing that once per line is
 * quadratic.
 *
 * Finding them uses [OrgSyntax.timestamp], the same pattern the highlighter colours, so the agenda
 * never counts something the editor does not show as a timestamp. What that pattern matches is then
 * parsed strictly: a stamp whose brackets do not pair up or whose date does not exist is left out
 * rather than guessed at. Inside a stamp, a token that is not recognised — a day name in another
 * language, a time that cannot be — is skipped and the rest kept, because the date is what matters.
 */
fun String.orgTimestamps(offset: Int = 0): List<OrgTimestamp> {
    val parts = OrgSyntax.timestamp.findAll(this)
        .mapNotNull { m -> parsePart(m.value)?.let { it to m.range } }
        .toList()

    val stamps = mutableListOf<OrgTimestamp>()
    var i = 0
    while (i < parts.size) {
        val (first, firstRange) = parts[i]
        val next = parts.getOrNull(i + 1)
        // `<a>--<b>`: two stamps of the same kind joined by exactly two dashes, ending no earlier
        // than they start. Anything else is two separate stamps.
        if (next != null &&
            next.second.first == firstRange.last + 3 &&
            regionMatches(firstRange.last + 1, "--", 0, 2) &&
            next.first.active == first.active &&
            next.first.date >= first.date
        ) {
            val second = next.first
            val sameDay = second.date == first.date
            stamps += first.toStamp(firstRange.first + offset..next.second.last + offset).copy(
                end = if (sameDay) null else second.date,
                endTime = second.time ?: if (sameDay) first.endTime else null,
            )
            i += 2
        } else {
            stamps += first.toStamp(firstRange.first + offset..firstRange.last + offset)
            i += 1
        }
    }
    return stamps
}

/** One bracketed stamp, before it is known whether it is half of a range. */
private data class Part(
    val date: LocalDate,
    val time: LocalTime?,
    val endTime: LocalTime?,
    val active: Boolean,
    val repeater: OrgRepeater?,
    val delay: OrgDelay?,
) {
    fun toStamp(range: IntRange) = OrgTimestamp(
        start = date,
        startTime = time,
        end = null,
        endTime = endTime,
        active = active,
        repeater = repeater,
        delay = delay,
        range = range,
    )
}

private val dateToken = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")
private val timeToken = Regex("^(\\d{1,2}):(\\d{2})(?:-(\\d{1,2}):(\\d{2}))?$")
private val repeaterToken = Regex("^(\\+\\+|\\.\\+|\\+)(\\d+)([hdwmy])$")
private val delayToken = Regex("^(--|-)(\\d+)([hdwmy])$")

private fun parsePart(stamp: String): Part? {
    val active = when {
        stamp.startsWith('<') && stamp.endsWith('>') -> true
        stamp.startsWith('[') && stamp.endsWith(']') -> false
        else -> return null
    }
    val tokens = stamp.substring(1, stamp.length - 1).split(' ', '\t').filter { it.isNotEmpty() }
    val date = tokens.firstOrNull()?.let(dateToken::find)?.let { m ->
        dateOrNull(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    } ?: return null

    var time: LocalTime? = null
    var endTime: LocalTime? = null
    var repeater: OrgRepeater? = null
    var delay: OrgDelay? = null
    for (token in tokens.drop(1)) {
        val timeMatch = timeToken.find(token)
        val repeaterMatch = repeaterToken.find(token)
        val delayMatch = delayToken.find(token)
        when {
            timeMatch != null -> if (time == null) {
                time = timeOrNull(timeMatch.groupValues[1].toInt(), timeMatch.groupValues[2].toInt())
                if (time != null && timeMatch.groupValues[3].isNotEmpty()) {
                    endTime = timeOrNull(timeMatch.groupValues[3].toInt(), timeMatch.groupValues[4].toInt())
                }
            }

            repeaterMatch != null -> if (repeater == null) {
                val kind = when (repeaterMatch.groupValues[1]) {
                    "++" -> RepeatKind.CatchUp
                    ".+" -> RepeatKind.Restart
                    else -> RepeatKind.Cumulate
                }
                repeater = OrgRepeater(kind, durationOf(repeaterMatch.groupValues[2], repeaterMatch.groupValues[3]))
            }

            delayMatch != null -> if (delay == null) {
                delay = OrgDelay(
                    by = durationOf(delayMatch.groupValues[2], delayMatch.groupValues[3]),
                    firstOnly = delayMatch.groupValues[1] == "--",
                )
            }
            // Anything else is a day name, in whatever language it was written, and says nothing
            // the date does not.
        }
    }
    return Part(date, time, endTime, active, repeater, delay)
}

private fun durationOf(amount: String, unit: String) = OrgDuration(
    amount = amount.toInt(),
    unit = when (unit) {
        "h" -> OrgTimeUnit.HOUR
        "d" -> OrgTimeUnit.DAY
        "w" -> OrgTimeUnit.WEEK
        "m" -> OrgTimeUnit.MONTH
        else -> OrgTimeUnit.YEAR
    },
)

private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? =
    runCatching { LocalDate(year, month, day) }.getOrNull()

private fun timeOrNull(hour: Int, minute: Int): LocalTime? =
    if (hour in 0..23 && minute in 0..59) LocalTime(hour, minute) else null
