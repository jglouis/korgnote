package com.korgnote.common.org.edit

import com.korgnote.common.org.OrgCheckboxItem
import com.korgnote.common.org.OrgList
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgDrawer
import com.korgnote.common.org.OrgNode
import com.korgnote.common.org.OrgPlanning
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.agenda.OrgTimeUnit
import com.korgnote.common.org.agenda.OrgTimestamp
import com.korgnote.common.org.agenda.RepeatKind
import com.korgnote.common.org.agenda.parseOrgTimestamp
import com.korgnote.common.org.complete.orgStamp
import com.korgnote.common.org.complete.restamp
import com.korgnote.common.org.complete.shiftedEnd
import com.korgnote.common.org.property
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.org.todoSequencesOf
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import com.korgnote.common.org.OrgPriority
import com.korgnote.common.org.LogKind
import com.korgnote.common.org.TodoLogging
import com.korgnote.common.org.todoLoggingOf

/**
 * Structural edits, each of which is a text replacement over a range.
 *
 * That is the whole design. Because every operation reduces to [replace], the rest of the file is
 * untouched *by construction* rather than by care — the bug that started this work, where saving a
 * document rewrote blank lines it had never been asked to touch, is unrepresentable here.
 *
 * Operations take the node they act on rather than an ordinal. Ordinals shift whenever anything
 * above them changes; a node carries the source range it was parsed from.
 */

/**
 * The one mutation primitive: replace [range] with [replacement] and re-parse.
 *
 * Invariant I4 of `docs/04-org-core.md`: `doc.replace(r, s).text == doc.text.replaceRange(r, s)`.
 */
fun OrgDocument.replace(range: IntRange, replacement: String): OrgDocument =
    text.replaceRange(range, replacement).toOrgDocument(todoFallback)

/** Every node in the document, parents before children. */
fun OrgDocument.allNodes(): List<OrgNode> {
    fun walk(node: OrgNode): List<OrgNode> = buildList {
        add(node)
        node.children.forEach { addAll(walk(it)) }
    }
    return walk(this)
}

/** The node of type [T] whose source contains [offset], innermost first. */
inline fun <reified T : OrgNode> OrgDocument.nodeAt(offset: Int): T? =
    allNodes().filterIsInstance<T>()
        .filter { !it.range.isEmpty() && offset >= it.range.first && offset <= it.range.last }
        .minByOrNull { it.range.last - it.range.first }

// --- Checkboxes ---

/** Ticks or unticks [item], changing only its `[ ]` marker. */
fun OrgDocument.toggleCheckbox(item: OrgCheckboxItem): OrgDocument =
    setCheckbox(item, !item.checked)

/** Sets [item]'s state, changing only its `[ ]` marker. */
fun OrgDocument.setCheckbox(item: OrgCheckboxItem, checked: Boolean): OrgDocument {
    if (item.range.isEmpty()) return this
    val line = lineRangeAt(item.range.first) ?: return this
    val marker = markerRangeIn(line) ?: return this
    return replace(marker, if (checked) "[X]" else "[ ]")
}

/**
 * Ticks or unticks [item] as Org's `C-c C-c` leaves a list: a box with boxes under it says what they
 * say — `[X]` when all are ticked, `[ ]` when none are, `[-]` when some are — so ticking one updates
 * every box above it.
 *
 * Org would put a box with boxes under it straight back to what they say, so a tap on one would do
 * nothing. Here it ticks or unticks them all instead, and so ends where Org would have.
 *
 * Every marker is three characters, so this is a set of same-length replacements: nothing else in
 * the file moves.
 */
fun OrgDocument.tickAsOrgDoes(item: OrgCheckboxItem): OrgDocument {
    if (item.range.isEmpty()) return this
    // The outermost list holding the item: Org settles the boxes of the whole list.
    val list = allNodes().filterIsInstance<OrgList>()
        .filter { item.range.first in it.range }
        .maxByOrNull { it.range.last - it.range.first }
        ?: return toggleCheckbox(item)
    val target = list.allBoxes().firstOrNull { it.range.first == item.range.first } ?: return toggleCheckbox(item)
    val ticked = !target.checked

    val wanted = mutableMapOf<Int, Char>() // a box's line start → the mark it should have
    fun setAll(box: OrgCheckboxItem) {
        wanted[box.range.first] = if (ticked) 'X' else ' '
        box.childBoxes().forEach(::setAll)
    }
    setAll(target)
    // Bottom-up: a box with boxes under it takes its mark from theirs, once theirs are settled.
    fun settle(box: OrgCheckboxItem): Char {
        val children = box.childBoxes().map(::settle)
        val mark = when {
            children.isEmpty() -> wanted[box.range.first] ?: box.mark()
            children.all { it == 'X' } -> 'X'
            children.all { it == ' ' } -> ' '
            else -> '-'
        }
        wanted[box.range.first] = mark
        return mark
    }
    list.topBoxes().forEach(::settle)

    var updated = text
    for (box in list.allBoxes()) {
        val mark = wanted[box.range.first] ?: continue
        if (mark == box.mark()) continue
        val line = lineRangeAt(box.range.first) ?: continue
        val marker = markerRangeIn(line) ?: continue
        updated = updated.replaceRange(marker, "[$mark]")
    }
    return if (updated == text) this else updated.toOrgDocument(todoFallback)
}

private fun OrgCheckboxItem.mark(): Char = if (checked) 'X' else if (partial) '-' else ' '

/** The boxes of the lists directly under this item. */
private fun OrgNode.childBoxes(): List<OrgCheckboxItem> =
    children.filterIsInstance<OrgList>().flatMap { it.children }.filterIsInstance<OrgCheckboxItem>()

/** A list's own boxes, not those of the lists nested in it. */
private fun OrgList.topBoxes(): List<OrgCheckboxItem> = children.filterIsInstance<OrgCheckboxItem>()

/** Every box in a list, nested ones included, as they come in the text. */
private fun OrgList.allBoxes(): List<OrgCheckboxItem> = buildList {
    fun walk(node: OrgNode) {
        if (node is OrgCheckboxItem) add(node)
        node.children.forEach(::walk)
    }
    children.forEach(::walk)
}

private fun OrgDocument.markerRangeIn(line: IntRange): IntRange? {
    val source = text.substring(line.first, line.last + 1)
    val match = OrgSyntax.listItem.find(source) ?: return null
    val checkbox = match.groupValues[4]
    if (checkbox.isEmpty()) return null
    val start = line.first + match.value.length - checkbox.length
    return start until (start + checkbox.length)
}

// --- Headings ---

/** Sets the TODO keyword of [heading], or removes it when [keyword] is null. */
fun OrgDocument.setTodo(heading: OrgSection, keyword: String?): OrgDocument {
    val parts = headingPartsOf(heading) ?: return this
    val existing = parts.todo
    return when {
        existing != null && keyword != null -> replace(existing, keyword)
        // Take the space after it too, so removing a keyword does not leave a double space.
        existing != null -> {
            val end = if (existing.last + 1 < parts.lineEnd && text[existing.last + 1] == ' ') existing.last + 1 else existing.last
            replace(existing.first..end, "")
        }
        keyword != null -> replace(parts.afterStars until parts.afterStars, "$keyword ")
        else -> this
    }
}

/**
 * Sets [heading]'s priority cookie, `[#A]`, or removes it when [priority] is null — as Org's
 * `C-c ,` does: the cookie goes after the TODO keyword, or first in the title when there is none,
 * and only the cookie and the space after it change.
 */
fun OrgDocument.setPriority(heading: OrgSection, priority: OrgPriority?): OrgDocument {
    val parts = headingPartsOf(heading) ?: return this
    // Where the cookie is, or goes: after the keyword and its space, else right after the stars.
    var at = parts.todo?.let { it.last + 1 } ?: parts.afterStars
    if (parts.todo != null) while (at < parts.lineEnd && text[at] == ' ') at++
    val existing = OrgSyntax.priority.find(text.substring(at, parts.lineEnd))?.let { at until (at + it.value.length) }
    val cookie = priority?.let { "[#${it.text}]" }
    return when {
        existing != null && cookie != null -> replace(existing, cookie)
        // Take the space after it too, so removing a cookie does not leave a double space.
        existing != null -> {
            val end = if (existing.last + 1 < parts.lineEnd && text[existing.last + 1] == ' ') existing.last + 1 else existing.last
            replace(existing.first..end, "")
        }
        cookie != null -> {
            // A heading that is only its keyword, `* TODO`, has no space after it to write into.
            val before = if (at > 0 && text[at - 1] != ' ') " " else ""
            val after = if (at < parts.lineEnd && text[at] != ' ') " " else ""
            replace(at until at, before + cookie + after)
        }
        else -> this
    }
}

/**
 * Advances [heading] to the next TODO keyword: through the not-done keywords, then the done ones,
 * then back to no keyword at all — the cycle Org itself uses.
 */
fun OrgDocument.cycleTodo(heading: OrgSection, config: OrgTodoConfig): OrgDocument {
    // A set has no order to cycle through; the keywords are declared in sequence, so keep that.
    val sequence = (config.todo + config.done).toList()
    if (sequence.isEmpty()) return this
    val current = heading.todo?.takeIf { it.isNotEmpty() }
    val next = when (val index = sequence.indexOf(current)) {
        -1 -> sequence.first()
        sequence.lastIndex -> null
        else -> sequence[index + 1]
    }
    return setTodo(heading, next)
}

/**
 * What changing [heading]'s keyword to [keyword] records, by the file's settings (see
 * [todoLoggingOf]): nothing, a time stamp, or a note. A note is the user's to write, so the caller
 * asks for one when this says [LogKind.Note], and hands it to [changeTodoState].
 */
fun OrgDocument.stateChangeLog(heading: OrgSection, keyword: String?, keywords: OrgTodoConfig = todoKeywords): LogKind? {
    val previous = heading.todo?.takeIf { it.isNotEmpty() }
    if (previous == keyword || keyword == null) return null
    val logging = todoLoggingOf(text)
    val logged = logging.forChange(previous, keyword)
    val nowDone = keyword in keywords.done && previous !in keywords.done
    if (nowDone && repeatingStampsOf(heading).isNotEmpty()) return repeatLog(logging, logged)
    if (logged != null) return logged
    // A closing note, `#+STARTUP: lognotedone`, when the keyword itself records nothing.
    return if (nowDone && logging.done == LogKind.Note) LogKind.Note else null
}

/**
 * Sets [heading]'s keyword as Org's `C-c C-t` does, recording what the file asks for and nothing
 * more (see [todoLoggingOf]):
 *
 * - The keyword's own setting, `!` or `@` on the state entered — else `/!` or `/@` on the one
 *   left — gives a line Org reads back as a state change,
 *   `- State "WAIT"       from "TODO"       [2026-10-24 Sat 14:30]`, followed by [note] under it
 *   when a note is asked for and one is given. Without a setting, nothing is logged.
 * - `#+STARTUP: logdone` stamps an entry `CLOSED:` when it becomes done; `lognotedone` adds a
 *   closing note when the keyword records nothing itself. While anything is tracked, an entry that
 *   is not done any more loses its `CLOSED:` stamp.
 * - Marking a repeating entry done repeats it instead; see [repeat].
 *
 * Log lines go where Org puts them — after the planning line and the property drawer, newest
 * first — and into the heading's `:LOGBOOK:` when it has one there, or when the file asks for one
 * with `#+STARTUP: logdrawer`. Clearing the keyword records nothing.
 */
fun OrgDocument.changeTodoState(
    heading: OrgSection,
    keyword: String?,
    at: LocalDateTime,
    keywords: OrgTodoConfig = todoKeywords,
    /** What the user wrote when [stateChangeLog] asked for a note; null or blank for none. */
    note: String? = null,
): OrgDocument {
    val previous = heading.todo?.takeIf { it.isNotEmpty() }
    if (previous == keyword) return this
    val start = heading.range.first
    val logging = todoLoggingOf(text)
    if (keyword == null) {
        val cleared = setTodo(heading, null)
        return if (logging.isActive) cleared.withoutClosed(start) else cleared
    }
    val logged = logging.forChange(previous, keyword)
    val nowDone = keyword in keywords.done && previous !in keywords.done
    if (nowDone) {
        val stamps = repeatingStampsOf(heading)
        if (stamps.isNotEmpty()) return repeat(heading, keyword, previous, stamps, at, keywords, repeatLog(logging, logged), note)
    }
    var changed = setTodo(heading, keyword)
    if (changed === this) return changed
    // The keyword moved what follows it, so the heading is found again by where it starts, each
    // time: it still starts there, and no section nested in it starts on its line.
    if (logging.isActive && keyword !in keywords.done && previous !in keywords.todo) changed = changed.withoutClosed(start)
    if (nowDone && logging.done != null) {
        changed = changed.withClosed(start, at)
        if (logged == null && logging.done == LogKind.Note) {
            changed = changed.withLogLine(start, logEntry("- CLOSING NOTE ${at.orgStamp(active = false)}", note))
        }
    }
    if (logged != null) changed = changed.withLogLine(start, logEntry(stateLine(keyword, previous, at), note.takeIf { logged == LogKind.Note }))
    return changed
}

/**
 * What a repeating entry marked done records, as `org-auto-repeat-maybe` decides: what the keywords
 * ask for — made a note when `lognoterepeat` wants one — else what `org-log-repeat` asks for, a
 * time stamp unless the file says `nologrepeat`.
 */
private fun repeatLog(logging: TodoLogging, logged: LogKind?): LogKind? =
    if (logged != null) (if (logging.repeat == LogKind.Note) LogKind.Note else logged) else logging.repeat

/** Org's state line, `- State "DONE"       from "TODO"       [2026-10-24 Sat 14:30]`. */
private fun stateLine(keyword: String, previous: String?, at: LocalDateTime): String {
    fun quoted(state: String?) = (state?.let { "\"$it\"" } ?: "").padEnd(12)
    return "- State ${quoted(keyword)} from ${quoted(previous)} ${at.orgStamp(active = false)}"
}

/**
 * [header] with [note] under it as `org-store-log-note` writes one: the header ends in `\\` and
 * each line of the note follows, indented under the item; a blank note leaves the header alone.
 */
internal fun logEntry(header: String, note: String?): String {
    val lines = note?.trimEnd()?.takeIf { it.isNotBlank() }?.lines() ?: return header
    return (listOf("$header \\\\") + lines.map { if (it.isBlank()) "" else "  " + it.trimEnd() }).joinToString("\n")
}

/** [entry] added to the log of the heading that starts at [start]. */
private fun OrgDocument.withLogLine(start: Int, entry: String): OrgDocument =
    nodeAt<OrgSection>(start)?.let { insertLogNote(it, entry) } ?: this

/** The planning line directly under [heading], where Org keeps `SCHEDULED:`, `DEADLINE:` and `CLOSED:`. */
private fun OrgDocument.planningLineOf(heading: OrgSection): IntRange? {
    val parts = headingPartsOf(heading) ?: return null
    val planning = heading.children.firstOrNull { it is OrgPlanning && it.range.first == parts.lineEnd + 1 } ?: return null
    val end = text.indexOf('\n', planning.range.first).let { if (it < 0) text.length else it }
    return planning.range.first until end
}

/**
 * The heading at [start] stamped `CLOSED:` [at], as `org-add-planning-info` does: first on its
 * planning line, or on a new one under the heading; a stamp already there is replaced.
 */
private fun OrgDocument.withClosed(start: Int, at: LocalDateTime): OrgDocument {
    val heading = nodeAt<OrgSection>(start) ?: return this
    val stamp = at.orgStamp(active = false)
    val line = planningLineOf(heading)
    if (line == null) {
        val parts = headingPartsOf(heading) ?: return this
        return insertLines(parts.lineEnd + 1, "CLOSED: $stamp\n")
    }
    val source = text.substring(line.first, line.last + 1)
    CLOSED_STAMP.find(source)?.let { return replace((line.first + it.range.first)..(line.first + it.range.last), "CLOSED: $stamp") }
    val indent = source.takeWhile { it == ' ' || it == '\t' }.length
    return replace((line.first + indent) until (line.first + indent), "CLOSED: $stamp ")
}

/** The heading at [start] without its `CLOSED:` stamp, and without its planning line if that leaves it empty. */
private fun OrgDocument.withoutClosed(start: Int): OrgDocument {
    val heading = nodeAt<OrgSection>(start) ?: return this
    val line = planningLineOf(heading) ?: return this
    val source = text.substring(line.first, line.last + 1)
    val closed = CLOSED_STAMP.find(source) ?: return this
    val rest = source.removeRange(closed.range).let { it.replace(Regex("[ \t]{2,}"), " ").trimEnd() }
    if (rest.isBlank()) return replace(line.first..minOf(line.last + 1, text.length - 1), "")
    return replace(line, rest.let { r -> if (source.first().isWhitespace()) r else r.trimStart() })
}

private val CLOSED_STAMP = Regex("CLOSED:[ \t]*\\[[^\\]\n]*\\][ \t]*")

/**
 * Org's repeated task (`org-auto-repeat-maybe`): rather than staying [done], the entry goes back to
 * the first keyword of its sequence — or its `REPEAT_TO_STATE` — and each of its repeating [stamps]
 * moves on to its next occurrence, any `CLOSED:` stamp going. What remains of the completion is
 * what the file asks for, [log]: by default, `LAST_REPEAT` in the property drawer and the state
 * line saying it was done; with a note under it when one is asked for.
 */
private fun OrgDocument.repeat(
    heading: OrgSection,
    done: String,
    previous: String?,
    stamps: List<OrgTimestamp>,
    at: LocalDateTime,
    keywords: OrgTodoConfig,
    log: LogKind?,
    note: String?,
): OrgDocument {
    val start = heading.range.first
    val logging = todoLoggingOf(text)
    val resetTo = repeatStateOf(heading, done, keywords)
    // From the last stamp up, so each replacement leaves the ranges above it where they were.
    val shifted = stamps.asReversed().fold(this) { doc, stamp ->
        doc.replace(stamp.range, nextOccurrence(text.substring(stamp.range), stamp, at))
    }
    // Every edit below moves what follows the heading, never its start, so it is found again there.
    var result = shifted.nodeAt<OrgSection>(start)?.let { shifted.setTodo(it, resetTo) } ?: return shifted
    result = result.withoutClosed(start)
    if (logging.repeat != null) {
        result = result.nodeAt<OrgSection>(start)?.let { result.setProperty(it, "LAST_REPEAT", at.orgStamp(active = false)) } ?: result
    }
    if (log != null) result = result.withLogLine(start, logEntry(stateLine(done, previous, at), note.takeIf { log == LogKind.Note }))
    return result
}

/**
 * The active stamps in [heading]'s own entry — its line up to the next heading — whose repeater
 * moves them. That is where Org looks: the planning line, and also the heading and its body.
 *
 * Each half of a `<a>--<b>` range is a stamp of its own here, as Org shifts each. An hourly
 * repeater on a stamp without a time cannot move, and Org refuses it too.
 */
private fun OrgDocument.repeatingStampsOf(heading: OrgSection): List<OrgTimestamp> {
    if (heading.range.isEmpty()) return emptyList()
    val start = heading.range.first
    val end = heading.children.firstOrNull { it is OrgSection && !it.range.isEmpty() }?.range?.first
        ?: (heading.range.last + 1)
    return OrgSyntax.timestamp.findAll(text.substring(start, end)).mapNotNull { match ->
        val stamp = parseOrgTimestamp(match.value) ?: return@mapNotNull null
        val every = stamp.repeater?.every ?: return@mapNotNull null
        if (!stamp.active || every.amount <= 0) return@mapNotNull null
        if (every.unit == OrgTimeUnit.HOUR && stamp.startTime == null) return@mapNotNull null
        stamp.copy(range = (start + match.range.first)..(start + match.range.last))
    }.toList()
}

/**
 * [source], the text of [stamp], moved to its next occurrence once its entry is done at [now]:
 *
 * - `+1w` moves one interval, wherever that lands.
 * - `++1w` moves whole intervals until it is past today — or past now, for hours.
 * - `.+1d` moves one interval from today, keeping its time of day — or from now, for hours.
 *
 * A month or a year on keeps the day of the month where that exists, and otherwise takes the
 * month's last day; Emacs would spill into the next month instead.
 */
internal fun nextOccurrence(source: String, stamp: OrgTimestamp, now: LocalDateTime): String {
    val repeater = stamp.repeater ?: return source
    val every = repeater.every
    val hourly = every.unit == OrgTimeUnit.HOUR
    fun LocalDateTime.next(): LocalDateTime = when (every.unit) {
        OrgTimeUnit.HOUR -> {
            val minutes = time.hour * 60 + time.minute + every.amount * 60
            LocalDateTime(
                date.plus(minutes.floorDiv(MINUTES_PER_DAY), DateTimeUnit.DAY),
                LocalTime.fromSecondOfDay(minutes.mod(MINUTES_PER_DAY) * 60),
            )
        }
        OrgTimeUnit.DAY -> LocalDateTime(date.plus(every.amount, DateTimeUnit.DAY), time)
        OrgTimeUnit.WEEK -> LocalDateTime(date.plus(every.amount, DateTimeUnit.WEEK), time)
        OrgTimeUnit.MONTH -> LocalDateTime(date.plus(every.amount, DateTimeUnit.MONTH), time)
        OrgTimeUnit.YEAR -> LocalDateTime(date.plus(every.amount, DateTimeUnit.YEAR), time)
    }

    val written = LocalDateTime(stamp.start, stamp.startTime ?: LocalTime(0, 0))
    val next = when (repeater.kind) {
        RepeatKind.Cumulate -> written.next()
        RepeatKind.CatchUp -> {
            var next = written.next()
            while (if (hourly) next <= now else next.date <= now.date) next = next.next()
            next
        }
        RepeatKind.Restart ->
            if (hourly) LocalDateTime(now.date, LocalTime(now.hour, now.minute)).next()
            else LocalDateTime(now.date, written.time).next()
    }
    val startTime = stamp.startTime?.let { next.time }
    val endTime = startTime?.let { shiftedEnd(stamp.startTime, stamp.endTime, it) }
    return restamp(source, next.date, startTime, endTime)
}

private const val MINUTES_PER_DAY = 24 * 60

/**
 * The keyword a repeating entry marked [done] goes back to: its own `REPEAT_TO_STATE` when that is
 * a keyword, or else the first keyword of the `#+TODO:` sequence [done] is declared in — `TODO`
 * for `#+TODO: TODO NEXT | DONE` — as Org does unless told otherwise.
 */
private fun OrgDocument.repeatStateOf(heading: OrgSection, done: String, keywords: OrgTodoConfig): String {
    val declared = heading.property("REPEAT_TO_STATE")
    if (declared != null && (declared in keywords.todo || declared in keywords.done)) return declared
    val sequence = todoSequencesOf(metadata).firstOrNull { done in it.done }
    return sequence?.let { (it.todo + it.done).first() } ?: keywords.todo.firstOrNull() ?: done
}

/** Puts [note] at the top of [heading]'s log; see [changeTodoState] for where that is. */
private fun OrgDocument.insertLogNote(heading: OrgSection, note: String): OrgDocument {
    val parts = headingPartsOf(heading) ?: return this
    var insertAt = parts.lineEnd + 1
    var logbook: OrgDrawer? = null
    // Only what sits directly under the heading, in order: a planning line further down is body.
    for (child in heading.children) {
        if (child.range.isEmpty() || child.range.first != insertAt) break
        if (child is OrgPlanning || (child is OrgDrawer && child.name.equals("PROPERTIES", ignoreCase = true))) {
            insertAt = child.range.last + 1
            continue
        }
        if (child is OrgDrawer && child.name.equals("LOGBOOK", ignoreCase = true) && !child.unterminated) {
            logbook = child
        }
        break
    }

    val entry = when {
        // Newest first, so straight after the drawer's opening line.
        logbook != null -> {
            insertAt = text.indexOf('\n', logbook.range.first) + 1
            "$note\n"
        }
        todoLoggingOf(text).intoDrawer -> ":LOGBOOK:\n$note\n:END:\n"
        else -> "$note\n"
    }
    return insertLines(insertAt, entry)
}

/**
 * Sets [key] to [value] in [heading]'s property drawer, as `org-entry-put` does: in place when the
 * drawer has the key, else as its last line, and in a new drawer — under the heading and its
 * planning line — when there is none.
 */
fun OrgDocument.setProperty(heading: OrgSection, key: String, value: String): OrgDocument {
    val parts = headingPartsOf(heading) ?: return this
    var insertAt = parts.lineEnd + 1
    var drawer: OrgDrawer? = null
    for (child in heading.children) {
        if (child.range.isEmpty() || child.range.first != insertAt) break
        if (child is OrgPlanning) {
            insertAt = child.range.last + 1
            continue
        }
        if (child is OrgDrawer && child.name.equals("PROPERTIES", ignoreCase = true) && !child.unterminated) {
            drawer = child
        }
        break
    }
    if (drawer == null) return insertLines(insertAt, ":PROPERTIES:\n:$key: $value\n:END:\n")

    // The drawer's lines after its opening one, keeping the indentation they are written with.
    var lineStart = text.indexOf('\n', drawer.range.first) + 1
    while (lineStart in 1..drawer.range.last) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val trimmed = line.trim()
        if (trimmed.startsWith(":$key:", ignoreCase = true)) return replace(lineStart until lineEnd, "$indent:$key: $value")
        if (trimmed.equals(":END:", ignoreCase = true)) return replace(lineStart until lineStart, "$indent:$key: $value\n")
        lineStart = lineEnd + 1
    }
    return this
}

/** Inserts whole [lines] at [at], the start of a line or the end of the text. */
internal fun OrgDocument.insertLines(at: Int, lines: String): OrgDocument {
    // What comes before may be the file's last line, with no newline after it.
    if (at >= text.length && !text.endsWith('\n')) return replace(text.length until text.length, "\n$lines")
    return replace(at until at, lines)
}

/** Replaces [heading]'s tags, or removes them when [tags] is empty. */
fun OrgDocument.setTags(heading: OrgSection, tags: List<String>): OrgDocument {
    val parts = headingPartsOf(heading) ?: return this
    val rendered = if (tags.isEmpty()) "" else " :" + tags.joinToString(":") + ":"
    val existing = parts.tags
    return when {
        existing != null -> replace(existing, rendered.trimStart().let { if (it.isEmpty()) "" else " $it" })
        rendered.isEmpty() -> this
        else -> replace(parts.lineEnd until parts.lineEnd, rendered)
    }
}

/** Which planning line to set. */
enum class PlanningKind { SCHEDULED, DEADLINE, CLOSED }

/**
 * Sets [kind]'s stamp on [heading], or removes it when [timestamp] is null, leaving any other stamp
 * on the same line alone. The line goes when that was its last stamp.
 *
 * A new planning line goes directly under the heading, which is where Org puts it.
 */
fun OrgDocument.setPlanning(
    heading: OrgSection,
    kind: PlanningKind,
    timestamp: String?,
): OrgDocument {
    for (node in heading.children.filterIsInstance<OrgPlanning>()) {
        if (node.range.isEmpty()) continue
        val line = lineRangeAt(node.range.first) ?: continue
        val source = text.substring(line.first, line.last + 1)
        val keywords = OrgSyntax.planningKeyword.findAll(source).toList()
        val at = keywords.indexOfFirst { it.groupValues[1] == kind.name }
        if (at < 0) continue
        val keywordEnd = keywords[at].range.last + 1
        val next = keywords.getOrNull(at + 1)?.range?.first
        val written = source.substring(keywordEnd, next ?: source.length)
        if (timestamp == null) {
            // Nothing else on it: the node's range covers the line and the newline that ends it.
            if (keywords.size == 1) return replace(node.range, "")
            // Last on the line, the space before it goes with it; otherwise the one after it does.
            val from = if (next != null) keywords[at].range.first else source.substring(0, keywords[at].range.first).trimEnd().length
            return replace((line.first + from) until (line.first + (next ?: source.length)), "")
        }
        val valueStart = keywordEnd + (written.length - written.trimStart().length)
        val valueEnd = valueStart + written.trim().length
        val gap = if (valueStart == keywordEnd) " " else ""
        return replace((line.first + valueStart) until (line.first + valueEnd), gap + timestamp)
    }
    if (timestamp == null) return this
    val parts = headingPartsOf(heading) ?: return this
    return insertLines(parts.lineEnd + 1, "${kind.name}: $timestamp\n")
}

/** Makes [heading] and everything under it one level shallower. */
fun OrgDocument.promote(heading: OrgSection): OrgDocument = shiftLevel(heading, -1)

/** Makes [heading] and everything under it one level deeper. */
fun OrgDocument.demote(heading: OrgSection): OrgDocument = shiftLevel(heading, +1)

/**
 * Restars every heading in [heading]'s subtree.
 *
 * The subtree is one range — that is what T4 bought — so this is a single replacement, and a
 * promotion cannot detach a child from its parent because they move together.
 */
private fun OrgDocument.shiftLevel(heading: OrgSection, delta: Int): OrgDocument {
    if (heading.range.isEmpty()) return this
    if (heading.level + delta < 1) return this

    val subtree = text.substring(heading.range.first, heading.range.last + 1)
    val shifted = buildString {
        var inBlock = false
        subtree.split('\n').forEachIndexed { index, line ->
            if (index > 0) append('\n')
            // A star inside a block is code, not a heading.
            val begin = OrgSyntax.blockBegin.containsMatchIn(line)
            val end = OrgSyntax.blockEnd.containsMatchIn(line)
            val stars = if (!inBlock && !begin) line.takeWhile { it == '*' } else ""
            if (stars.isNotEmpty() && OrgSyntax.heading.containsMatchIn(line)) {
                append("*".repeat((stars.length + delta).coerceAtLeast(1)))
                append(line.substring(stars.length))
            } else {
                append(line)
            }
            if (begin) inBlock = true
            if (end) inBlock = false
        }
    }
    return replace(heading.range, shifted)
}

// --- Locating the parts of a heading line ---

private class HeadingParts(
    val afterStars: Int,
    val todo: IntRange?,
    val tags: IntRange?,
    val lineEnd: Int,
)

/** Absolute ranges of the pieces of [heading]'s own line, for edits that touch only one of them. */
private fun OrgDocument.headingPartsOf(heading: OrgSection): HeadingParts? {
    if (heading.range.isEmpty()) return null
    val line = lineRangeAt(heading.range.first) ?: return null
    val source = text.substring(line.first, line.last + 1)
    val stars = OrgSyntax.heading.find(source)?.groupValues?.get(1) ?: return null

    var pos = stars.length
    while (pos < source.length && source[pos].isWhitespace()) pos++
    val afterStars = line.first + pos

    val firstWord = source.substring(pos).takeWhile { !it.isWhitespace() }
    val todo = if (firstWord.isNotEmpty() && firstWord == heading.todo && firstWord.isNotBlank()) {
        (line.first + pos) until (line.first + pos + firstWord.length)
    } else {
        null
    }

    val tagMatch = OrgSyntax.tags.find(source)
    val tags = tagMatch?.let { (line.first + it.range.first)..(line.first + it.range.last) }

    return HeadingParts(afterStars = afterStars, todo = todo, tags = tags, lineEnd = line.last + 1)
}

/** The range of the line containing [offset], excluding its newline. */
private fun OrgDocument.lineRangeAt(offset: Int): IntRange? {
    if (offset < 0 || offset > text.length) return null
    val start = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val newline = text.indexOf('\n', start)
    val end = if (newline < 0) text.length else newline
    return if (end <= start) start until start else start until end
}
