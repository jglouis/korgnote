package com.korgnote.common.org.complete

import com.korgnote.common.org.OrgBlock
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.agenda.parseOrgTimestamp
import com.korgnote.common.org.edit.nodeAt
import com.korgnote.common.org.link.relativeNotePath
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlinx.datetime.DatePeriod

/**
 * What the suggestions are drawn from.
 *
 * All of it is supplied by the caller rather than derived here, because the engine is handed one
 * document — often a single element being edited — while the useful material lives outside it: the
 * tags are the whole replica's, the paths are every note's, and the date comes from a clock the
 * tests must be able to replace.
 */
data class CompletionContext(
    /** This document's `#+TODO:`, or [DEFAULT_TODO_KEYWORDS] when it names none. */
    val todoKeywords: OrgTodoConfig = DEFAULT_TODO_KEYWORDS,
    /** Tags used across the corpus, most-used first — that order is the suggestion order. */
    val knownTags: List<String> = emptyList(),
    /** Every note's path, for `[[file:…]]`. */
    val filePaths: List<String> = emptyList(),
    /**
     * The note being edited. A `file:` link is written relative to its folder, which is how Org
     * reads it back; written from the notes' root instead, it breaks in every subfolder.
     */
    val currentPath: String = "",
    /** A note's own `:ID:`, when it has one: linked by id, as org-roam links, so a rename cannot break it. */
    val noteIds: Map<String, String> = emptyMap(),
    /** A note's `#+TITLE:`, used as the link's description so reading mode shows a name, not a path. */
    val noteTitles: Map<String, String> = emptyMap(),
    /** Headings of the document being edited, for `[[*Heading]]`. */
    val headings: List<String> = emptyList(),
    val today: LocalDate,
)

/** What Org assumes when a document names no workflow of its own. */
val DEFAULT_TODO_KEYWORDS = OrgTodoConfig(todo = setOf("TODO"), done = setOf("DONE"))

/**
 * One suggestion.
 *
 * [replace] is the range of document text the item stands in for — the prefix that was typed, not
 * merely the caret position — so accepting it is a range replacement like every other edit in this
 * codebase. [caretOffset] is where the caret lands relative to the start of [insert], which is what
 * puts it inside `<…>` or between the lines of a block rather than after the whole thing.
 */
data class CompletionItem(
    val label: String,
    val insert: String,
    val replace: IntRange,
    val kind: Kind,
    val caretOffset: Int = insert.length,
) {
    enum class Kind {
        TODO_KEYWORD,
        PLANNING,
        DATE,
        /**
         * Insert nothing; the UI opens a date and time picker and inserts what comes back. When
         * [replace] covers a stamp already written, the picker starts from it.
         */
        DATE_PICKER,
        TAG,
        KEYWORD,
        LINK,
        TIMESTAMP,
        TEMPLATE,
    }
}

/** A document's text and where the caret sits in it, after a completion was accepted. */
data class CompletionResult(val text: String, val caret: Int)

/**
 * The text and caret after accepting this item.
 *
 * Accepting is a range replacement — the item carries the range of typed text it stands in for —
 * so it disturbs nothing else on the line, and the caret lands where the item asked for rather
 * than after the whole insertion: inside `<…>`, or between the two lines of a block.
 */
fun CompletionItem.applyTo(text: String): CompletionResult = spliceInto(text, insert, caretOffset)

/**
 * The same, with [replacement] standing in for the item's own text.
 *
 * This is what [CompletionItem.Kind.DATE_PICKER] needs: the item knows *what it replaces*, but
 * only the picker knows what goes there.
 */
fun CompletionItem.applyTo(text: String, replacement: String): CompletionResult =
    spliceInto(text, replacement, replacement.length)

private fun CompletionItem.spliceInto(text: String, insert: String, caretOffset: Int): CompletionResult {
    val updated = text.replaceRange(replace, insert)
    return CompletionResult(updated, (replace.first + caretOffset).coerceIn(0, updated.length))
}

/**
 * Suggestions for the caret at [cursor] in [doc].
 *
 * A pure function of its arguments, so every row of the trigger table in `docs/06-editor.md` is a
 * unit test with no UI involved. The rules are tried in order and the first one that recognises
 * what is being typed wins: `[[` is a link before it is a bracket, `<s` is a block template before
 * it is a timestamp, and a word right after the stars of a heading is a TODO keyword rather than
 * anything else.
 *
 * An empty list means "nothing to offer", which is the common case and must stay cheap: every rule
 * looks only at the current line, apart from the one that needs to know a heading is above it.
 */
fun complete(doc: OrgDocument, cursor: Int, ctx: CompletionContext): List<CompletionItem> {
    val text = doc.text
    if (cursor !in 0..text.length) return emptyList()
    // Inside a source block the text is code, not Org, and the parser already knows where those
    // are. Offering `[[file:…]]` in the middle of a shell script is noise.
    if (doc.cursorIsInsideBlockBody(cursor)) return emptyList()

    val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
    val prefix = text.substring(lineStart, cursor)
    val lineEnd = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
    val line = text.substring(lineStart, lineEnd)
    val previousLine = previousLineOf(text, lineStart)

    return linkItems(prefix, lineStart, cursor, ctx)
        ?: templateItems(prefix, lineStart, cursor)
        ?: planningStampItems(line, lineStart, cursor, ctx)
        ?: timestampItems(prefix, cursor, ctx)
        ?: dateItems(prefix, cursor, ctx)
        ?: planningItems(prefix, previousLine, lineStart, cursor, ctx)
        ?: tagItems(prefix, line, lineStart, cursor, ctx)
        ?: todoKeywordItems(prefix, cursor, ctx)
        ?: keywordItems(prefix, lineStart, cursor)
        ?: emptyList()
}

// --- Rules -------------------------------------------------------------------------------------
//
// Each returns null for "this is not what is being typed" and a list — possibly empty — for "this
// is mine". The difference matters: an empty list from a rule that recognised the trigger stops
// the later rules from offering something irrelevant, which is what keeps a half-typed `[[fil`
// from being answered with TODO keywords.

/** `[[` opens a link: notes first, then headings of this document. */
private fun linkItems(prefix: String, lineStart: Int, cursor: Int, ctx: CompletionContext): List<CompletionItem>? {
    val open = prefix.lastIndexOf("[[")
    if (open < 0) return null
    val typed = prefix.substring(open + 2)
    // A closed link is finished; what follows it is ordinary text again.
    if (']' in typed) return null

    val replace = (lineStart + open) until cursor
    val paths = ctx.filePaths
        .filter { it.contains(typed, ignoreCase = true) }
        .map { path ->
            val title = ctx.noteTitles[path] ?: path.substringAfterLast('/').removeSuffix(".org")
            val target = ctx.noteIds[path]?.let { "id:$it" } ?: "file:${relativeNotePath(path, ctx.currentPath)}"
            CompletionItem(
                label = path,
                insert = "[[$target][$title]]",
                replace = replace,
                kind = CompletionItem.Kind.LINK,
            )
        }
    val headings = ctx.headings
        .filter { it.contains(typed, ignoreCase = true) }
        .map { heading ->
            CompletionItem(
                label = "*$heading",
                insert = "[[*$heading]]",
                replace = replace,
                kind = CompletionItem.Kind.LINK,
            )
        }
    return paths + headings
}

/** `<s`, `<q`, `<e` at the start of a line: Org's `org-tempo` block templates. */
private fun templateItems(prefix: String, lineStart: Int, cursor: Int): List<CompletionItem>? {
    val match = TEMPLATE.matchEntire(prefix) ?: return null
    val letter = match.groupValues[1].lowercase()
    val block = TEMPLATES[letter] ?: return null
    val insert = "#+BEGIN_$block\n\n#+END_$block"
    return listOf(
        CompletionItem(
            label = "#+BEGIN_$block …",
            insert = insert,
            replace = lineStart until cursor,
            kind = CompletionItem.Kind.TEMPLATE,
            // Between the two lines, which is where the content goes.
            caretOffset = "#+BEGIN_$block\n".length,
        )
    )
}

/** A bare `<` or `[` asks for today's date: active in angle brackets, inactive in square ones. */
private fun timestampItems(prefix: String, cursor: Int, ctx: CompletionContext): List<CompletionItem>? {
    val opener = prefix.lastOrNull() ?: return null
    if (opener != '<' && opener != '[') return null
    val active = opener == '<'
    return listOf(
        CompletionItem(
            label = ctx.today.orgStamp(active),
            insert = ctx.today.orgStamp(active),
            replace = (cursor - 1) until cursor,
            kind = CompletionItem.Kind.TIMESTAMP,
        )
    )
}

/**
 * The caret on a stamp already written after `SCHEDULED:` or `DEADLINE:` — anywhere in it, or just
 * after it, which is where accepting a planning line leaves the caret.
 *
 * The picker comes first, since changing a written date is what it is for; the usual dates follow.
 * Each replaces the whole stamp but keeps what it does not ask about — the time, a repeater — so
 * moving a weekly entry to tomorrow does not turn it into a one-off.
 */
private fun planningStampItems(line: String, lineStart: Int, cursor: Int, ctx: CompletionContext): List<CompletionItem>? {
    val column = cursor - lineStart
    val stamp = OrgSyntax.timestamp.findAll(line)
        .firstOrNull { column in it.range.first..it.range.last + 1 } ?: return null
    if (!BEFORE_PLANNING_STAMP.containsMatchIn(line.substring(0, stamp.range.first))) return null
    val replace = (lineStart + stamp.range.first)..(lineStart + stamp.range.last)
    val old = parseOrgTimestamp(stamp.value)

    val dates = namedDates(ctx.today).map { (label, date) ->
        val insert = restamp(stamp.value, date, old?.startTime, old?.endTime)
        CompletionItem(label = "$label — $insert", insert = insert, replace = replace, kind = CompletionItem.Kind.DATE)
    }
    return listOf(datePicker(replace)) + dates
}

/**
 * After `SCHEDULED:` or `DEADLINE:`, a date — the ones actually used, plus a picker.
 *
 * With nothing typed yet the picker leads: on a phone the strip shows three or four chips, and
 * behind ten dates it would never be seen. A typed name is narrowing the dates, so they lead then.
 */
private fun dateItems(prefix: String, cursor: Int, ctx: CompletionContext): List<CompletionItem>? {
    val match = AFTER_PLANNING.find(prefix) ?: return null
    val typed = match.groupValues[2]
    val replace = (cursor - typed.length) until cursor

    val dates = namedDates(ctx.today)
        .filter { (label, _) -> typed.isEmpty() || label.startsWith(typed, ignoreCase = true) }
        .map { (label, date) ->
            CompletionItem(
                label = "$label — ${date.orgStamp(active = true)}",
                insert = date.orgStamp(active = true),
                replace = replace,
                kind = CompletionItem.Kind.DATE,
            )
        }
    val picker = datePicker(replace)
    return if (typed.isEmpty()) listOf(picker) + dates else dates + picker
}

/** The dates worth a shortcut, by the name they are typed as. */
private fun namedDates(today: LocalDate): List<Pair<String, LocalDate>> = buildList {
    add("today" to today)
    add("tomorrow" to today.plus(DatePeriod(days = 1)))
    add("+1w" to today.plus(DatePeriod(days = 7)))
    // The next occurrence of each weekday, so "fri" lands on the coming Friday.
    for (ahead in 1..7) {
        val date = today.plus(DatePeriod(days = ahead))
        add(date.dayOfWeek.fullName() to date)
    }
}

private fun datePicker(replace: IntRange) = CompletionItem(
    label = DATE_PICKER_LABEL,
    insert = "",
    replace = replace,
    kind = CompletionItem.Kind.DATE_PICKER,
)

/** `SCHE…` or `DEAD…` on the line under a heading: the planning line, with today already in it. */
private fun planningItems(
    prefix: String,
    previousLine: String,
    lineStart: Int,
    cursor: Int,
    ctx: CompletionContext,
): List<CompletionItem>? {
    if (!OrgSyntax.heading.containsMatchIn(previousLine)) return null
    val typed = prefix.trimStart()
    if (typed.isEmpty() || typed.any { it.isWhitespace() }) return null
    val keywords = listOf("SCHEDULED", "DEADLINE").filter { it.startsWith(typed, ignoreCase = true) }
    if (keywords.isEmpty()) return null
    return keywords.map { keyword ->
        val insert = "$keyword: ${ctx.today.orgStamp(active = true)}"
        CompletionItem(
            label = insert,
            insert = insert,
            replace = lineStart until cursor,
            kind = CompletionItem.Kind.PLANNING,
        )
    }
}

/** `:` on a heading line: the corpus's own tags, most-used first. */
private fun tagItems(
    prefix: String,
    line: String,
    lineStart: Int,
    cursor: Int,
    ctx: CompletionContext,
): List<CompletionItem>? {
    if (!OrgSyntax.heading.containsMatchIn(line)) return null
    val match = TAG_PREFIX.find(prefix) ?: return null
    val typed = match.groupValues[1]
    val replace = (lineStart + match.range.first) until cursor
    return ctx.knownTags
        .filter { it.startsWith(typed, ignoreCase = true) }
        .map { tag ->
            CompletionItem(
                label = ":$tag:",
                insert = ":$tag:",
                replace = replace,
                kind = CompletionItem.Kind.TAG,
            )
        }
}

/** The first word of a heading is its TODO keyword. */
private fun todoKeywordItems(
    prefix: String,
    cursor: Int,
    ctx: CompletionContext,
): List<CompletionItem>? {
    val match = HEADING_FIRST_WORD.matchEntire(prefix) ?: return null
    val typed = match.groupValues[2]
    val replace = (cursor - typed.length) until cursor
    val config = ctx.todoKeywords.orDefault()
    return (config.todo + config.done)
        .filter { it.startsWith(typed, ignoreCase = true) }
        .map { keyword ->
            CompletionItem(
                label = keyword,
                // The space is part of the insertion: the keyword is never the whole heading.
                insert = "$keyword ",
                replace = replace,
                kind = CompletionItem.Kind.TODO_KEYWORD,
            )
        }
}

/** `#+` at the start of a line: the keywords a note actually uses. */
private fun keywordItems(prefix: String, lineStart: Int, cursor: Int): List<CompletionItem>? {
    val match = KEYWORD_PREFIX.matchEntire(prefix) ?: return null
    val typed = match.groupValues[1]
    val replace = lineStart until cursor
    return KEYWORDS
        .filter { it.startsWith(typed, ignoreCase = true) }
        .map { keyword ->
            val block = keyword.removePrefix("BEGIN_").takeIf { keyword.startsWith("BEGIN_") }
            if (block != null) {
                val insert = "#+BEGIN_$block\n\n#+END_$block"
                CompletionItem(
                    label = "#+$keyword …",
                    insert = insert,
                    replace = replace,
                    kind = CompletionItem.Kind.TEMPLATE,
                    caretOffset = "#+BEGIN_$block\n".length,
                )
            } else {
                CompletionItem(
                    label = "#+$keyword:",
                    insert = "#+$keyword: ",
                    replace = replace,
                    kind = CompletionItem.Kind.KEYWORD,
                )
            }
        }
}

// --- Shared pieces -----------------------------------------------------------------------------

/** `<2026-09-22 Tue>` when active, `[2026-09-22 Tue]` when not. */
fun LocalDate.orgStamp(active: Boolean): String {
    val stamp = orgDate()
    return if (active) "<$stamp>" else "[$stamp]"
}

/**
 * [stamp] moved to [date] at [time] — or with no time, when [time] is null — or a new active stamp
 * when [stamp] is not one, such as the half-typed text a picker suggestion stands in for.
 *
 * [endTime] makes it a range within the day, `10:00-11:30`. It is written only after a start time,
 * and only when it is later than that start: Org reads an end before its start as nothing useful.
 *
 * What the picker does not ask about stays as written: the brackets, and the repeater and warning
 * of a recurring entry, which rescheduling must not quietly drop.
 */
fun restamp(stamp: String, date: LocalDate, time: LocalTime?, endTime: LocalTime? = null): String {
    val old = parseOrgTimestamp(stamp)
    val inner = buildString {
        append(date.orgDate())
        if (time != null) {
            append(' ').append(time.orgTime())
            if (endTime != null && endTime > time) append('-').append(endTime.orgTime())
        }
        if (old != null) {
            stamp.substring(1, stamp.length - 1).split(' ', '\t')
                .filter { STAMP_REPEATER_OR_DELAY.matches(it) }
                .forEach { append(' ').append(it) }
        }
    }
    return if (old?.active == false) "[$inner]" else "<$inner>"
}

/**
 * Where a range that ran [oldStart]–[oldEnd] ends once its start moves to [newStart]: the same
 * length later, so moving a meeting does not change how long it is. Null when there was no range,
 * or when the same length would run past midnight, which a range within one day cannot.
 */
fun shiftedEnd(oldStart: LocalTime?, oldEnd: LocalTime?, newStart: LocalTime): LocalTime? {
    if (oldStart == null || oldEnd == null || oldEnd <= oldStart) return null
    val end = newStart.toSecondOfDay() + (oldEnd.toSecondOfDay() - oldStart.toSecondOfDay())
    return if (end < SECONDS_PER_DAY) LocalTime.fromSecondOfDay(end) else null
}

private fun LocalDate.orgDate(): String =
    "${pad(year, 4)}-${pad(month.ordinal + 1, 2)}-${pad(day, 2)} ${dayOfWeek.shortName()}"

private fun LocalTime.orgTime(): String = "${pad(hour, 2)}:${pad(minute, 2)}"

private const val SECONDS_PER_DAY = 24 * 60 * 60

/** `[2026-09-22 Tue 14:05]`: the date as [orgStamp] writes it, with the time of day. */
fun LocalDateTime.orgStamp(active: Boolean): String {
    val stamp = "${date.orgDate()} ${time.orgTime()}"
    return if (active) "<$stamp>" else "[$stamp]"
}

private fun pad(value: Int, width: Int) = value.toString().padStart(width, '0')

/** Org writes the three-letter English abbreviation, whatever the device's locale is. */
private fun DayOfWeek.shortName(): String = fullName().take(3)

private fun DayOfWeek.fullName(): String = when (this) {
    DayOfWeek.MONDAY -> "Monday"
    DayOfWeek.TUESDAY -> "Tuesday"
    DayOfWeek.WEDNESDAY -> "Wednesday"
    DayOfWeek.THURSDAY -> "Thursday"
    DayOfWeek.FRIDAY -> "Friday"
    DayOfWeek.SATURDAY -> "Saturday"
    DayOfWeek.SUNDAY -> "Sunday"
}

/** A document that names no workflow gets Org's own. */
private fun OrgTodoConfig.orDefault(): OrgTodoConfig =
    if (todo.isEmpty() && done.isEmpty()) DEFAULT_TODO_KEYWORDS else this

/** True when [cursor] is in a block's contents rather than on its `#+BEGIN_` line. */
private fun OrgDocument.cursorIsInsideBlockBody(cursor: Int): Boolean {
    val block = nodeAt<OrgBlock>(cursor) ?: return false
    val firstLineEnd = text.indexOf('\n', block.range.first)
    return firstLineEnd in 0 until cursor
}

/** The line before the one starting at [lineStart], or empty at the top of the document. */
private fun previousLineOf(text: String, lineStart: Int): String {
    if (lineStart == 0) return ""
    val end = lineStart - 1
    val start = text.lastIndexOf('\n', end - 1) + 1
    return text.substring(start, end)
}

private val TEMPLATE = Regex("^<([sqeSQE])$")
private val TEMPLATES = mapOf("s" to "SRC", "q" to "QUOTE", "e" to "EXAMPLE")
private val AFTER_PLANNING = Regex("(SCHEDULED|DEADLINE):\\s*(\\S*)$", RegexOption.IGNORE_CASE)
private val BEFORE_PLANNING_STAMP = Regex("(SCHEDULED|DEADLINE):\\s*$", RegexOption.IGNORE_CASE)
private val STAMP_REPEATER_OR_DELAY = Regex("(\\+\\+|\\.\\+|\\+|--|-)\\d+[hdwmy]")
private val TAG_PREFIX = Regex(":([\\p{L}\\p{N}_@#%]*)$")
private val HEADING_FIRST_WORD = Regex("^(\\*+)\\s+(\\S*)$")
private val KEYWORD_PREFIX = Regex("^#\\+([A-Za-z_]*)$")

private const val DATE_PICKER_LABEL = "Pick date & time…"

/** The keywords these notes actually use, in the order they are worth offering. */
private val KEYWORDS = listOf(
    "TITLE", "FILETAGS", "TODO", "CATEGORY", "STARTUP", "AUTHOR", "DATE", "OPTIONS",
    "BEGIN_SRC", "BEGIN_QUOTE", "BEGIN_EXAMPLE",
)
