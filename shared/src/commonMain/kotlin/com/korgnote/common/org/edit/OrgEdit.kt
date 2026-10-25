package com.korgnote.common.org.edit

import com.korgnote.common.org.OrgCheckboxItem
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
import com.korgnote.common.org.complete.DEFAULT_TODO_KEYWORDS
import com.korgnote.common.org.complete.orgStamp
import com.korgnote.common.org.complete.restamp
import com.korgnote.common.org.complete.shiftedEnd
import com.korgnote.common.org.parseTodoConfigFrom
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.org.todoKeywordsOf
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

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
    text.replaceRange(range, replacement).toOrgDocument()

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
    return replace(marker, if (checked) "[x]" else "[ ]")
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
        existing != null -> replace(existing.first..(existing.last + 1), "")
        keyword != null -> replace(parts.afterStars until parts.afterStars, "$keyword ")
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
 * Sets [heading]'s keyword as Org's `C-c C-t` does with state logging on: the keyword changes, and
 * a note stamped [at] records the change under the heading.
 *
 * The note is Org's own, `- State "DONE"       from "TODO"       [2026-10-24 Sat 14:30]`, so Emacs
 * reads it back as a state change. It goes where Org puts it — after the planning line and the
 * property drawer, newest first — and into the heading's `:LOGBOOK:` when it has one there, or when
 * the file asks for one with `#+STARTUP: logdrawer`.
 *
 * Clearing the keyword records nothing: there is no state left to log, and Org logs none either.
 *
 * Marking a repeating entry done — moving it into one of [keywords]' done states from one that is
 * not — repeats it instead; see [repeat].
 */
fun OrgDocument.changeTodoState(
    heading: OrgSection,
    keyword: String?,
    at: LocalDateTime,
    keywords: OrgTodoConfig = todoKeywordsOf(text, DEFAULT_TODO_KEYWORDS),
): OrgDocument {
    val previous = heading.todo?.takeIf { it.isNotEmpty() }
    if (previous == keyword) return this
    if (keyword == null) return setTodo(heading, null)
    val note = stateNote(keyword, previous, at)
    if (keyword in keywords.done && previous !in keywords.done) {
        val stamps = repeatingStampsOf(heading)
        if (stamps.isNotEmpty()) return repeat(heading, keyword, stamps, note, at, keywords)
    }
    val changed = setTodo(heading, keyword)
    if (changed === this) return changed
    // The keyword moved what follows it, so the heading is found again rather than reused. It
    // still starts where it did, and no section nested in it starts on its line.
    val updated = changed.nodeAt<OrgSection>(heading.range.first) ?: return changed
    return changed.insertLogNote(updated, note)
}

private fun stateNote(keyword: String, previous: String?, at: LocalDateTime): String {
    fun quoted(state: String?) = (state?.let { "\"$it\"" } ?: "").padEnd(12)
    return "- State ${quoted(keyword)} from ${quoted(previous)} ${at.orgStamp(active = false)}"
}

/**
 * Org's repeated task (`org-auto-repeat-maybe`): rather than staying [done], the entry goes back to
 * the first keyword of its sequence — or its `REPEAT_TO_STATE` — and each of its repeating [stamps]
 * moves on to its next occurrence. What remains of the completion is a record: `LAST_REPEAT` in
 * the property drawer and the [note] saying it was done, both as Org writes them by default.
 */
private fun OrgDocument.repeat(
    heading: OrgSection,
    done: String,
    stamps: List<OrgTimestamp>,
    note: String,
    at: LocalDateTime,
    keywords: OrgTodoConfig,
): OrgDocument {
    val start = heading.range.first
    val resetTo = repeatStateOf(heading, done, keywords)
    // From the last stamp up, so each replacement leaves the ranges above it where they were.
    val shifted = stamps.asReversed().fold(this) { doc, stamp ->
        doc.replace(stamp.range, nextOccurrence(text.substring(stamp.range), stamp, at))
    }
    // Every edit below moves what follows the heading, never its start, so it is found again there.
    val reset = shifted.nodeAt<OrgSection>(start)?.let { shifted.setTodo(it, resetTo) } ?: return shifted
    val stamped = reset.nodeAt<OrgSection>(start)
        ?.let { reset.setProperty(it, "LAST_REPEAT", at.orgStamp(active = false)) } ?: return reset
    return stamped.nodeAt<OrgSection>(start)?.let { stamped.insertLogNote(it, note) } ?: stamped
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
    val declared = heading.children.filterIsInstance<OrgDrawer>()
        .firstOrNull { it.name.equals("PROPERTIES", ignoreCase = true) }
        ?.properties?.firstOrNull { it.first.equals("REPEAT_TO_STATE", ignoreCase = true) }?.second?.trim()
    if (declared != null && (declared in keywords.todo || declared in keywords.done)) return declared
    val sequence = metadata.map { parseTodoConfigFrom(listOf(it)) }.firstOrNull { done in it.done }
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
        logsIntoDrawer() -> ":LOGBOOK:\n$note\n:END:\n"
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
private fun OrgDocument.insertLines(at: Int, lines: String): OrgDocument {
    // What comes before may be the file's last line, with no newline after it.
    if (at >= text.length && !text.endsWith('\n')) return replace(text.length until text.length, "\n$lines")
    return replace(at until at, lines)
}

private val STARTUP = Regex("^#\\+STARTUP:(.*)", RegexOption.IGNORE_CASE)

/** Whether the file sets `#+STARTUP: logdrawer`, Org's per-file `org-log-into-drawer`. */
private fun OrgDocument.logsIntoDrawer(): Boolean =
    metadata.mapNotNull { STARTUP.find(it.trim())?.groupValues?.get(1) }
        .flatMap { it.trim().split(Regex("\\s+")) }
        .any { it.equals("logdrawer", ignoreCase = true) }

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
 * Sets a planning line for [heading], or removes it when [timestamp] is null.
 *
 * A new planning line goes directly under the heading, which is where Org puts it.
 */
fun OrgDocument.setPlanning(
    heading: OrgSection,
    kind: PlanningKind,
    timestamp: String?,
): OrgDocument {
    val existing = heading.children
        .filterIsInstance<OrgPlanning>()
        .firstOrNull { it.planType == kind.name && !it.range.isEmpty() }

    if (existing != null) {
        // The node's range covers its line and the newline that ends it.
        return if (timestamp == null) replace(existing.range, "")
        else replace(existing.range, "${kind.name}: $timestamp\n")
    }
    if (timestamp == null) return this
    val parts = headingPartsOf(heading) ?: return this
    val insertAt = minOf(parts.lineEnd + 1, text.length)
    return replace(insertAt until insertAt, "${kind.name}: $timestamp\n")
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
