package com.korgnote.common.org.edit

import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.complete.CompletionResult

/**
 * What the editor's insert buttons put into an element being edited, so that a link, a tag, a date
 * or a place is a tap rather than syntax to remember and type on a phone keyboard.
 *
 * Each writes the start of the construct and leaves the caret where completion takes over: after
 * `[[` for a note, after the heading's tag colon for a tag, after `SCHEDULED:` for a date, after
 * `:LOCATION:` for a place. The rest is the suggestions' job, as when it is typed.
 */
enum class DraftInsert {
    LINK,
    TAG,
    SCHEDULE,
    LOCATION,
    CHECKBOX,
}

/** Whether [what] has somewhere to go in [text]: the heading's own things need a heading. */
fun DraftInsert.fitsIn(text: String): Boolean = when (this) {
    DraftInsert.LINK, DraftInsert.CHECKBOX -> true
    DraftInsert.TAG, DraftInsert.SCHEDULE, DraftInsert.LOCATION -> OrgSyntax.heading.containsMatchIn(firstLine(text))
}

/** [text] with [what] begun at, or for, [caret]; null when it does not fit, see [fitsIn]. */
fun insertInto(text: String, caret: Int, what: DraftInsert): CompletionResult? {
    if (!what.fitsIn(text)) return null
    val at = caret.coerceIn(0, text.length)
    return when (what) {
        DraftInsert.LINK -> splice(text, at, at, "[[")
        DraftInsert.TAG -> startTag(text)
        DraftInsert.SCHEDULE -> startSchedule(text)
        DraftInsert.LOCATION -> startLocation(text)
        DraftInsert.CHECKBOX -> startCheckbox(text, at)
    }
}

/** At the end of the heading's tags, or a new `:` after its title, which the tag suggestions answer. */
private fun startTag(text: String): CompletionResult {
    val end = lineEnd(text, 0)
    val heading = text.substring(0, end).trimEnd()
    val tail = if (OrgSyntax.tags.containsMatchIn(heading)) "" else " :"
    return splice(text, heading.length, end, tail)
}

/** On the heading's `SCHEDULED:`, writing one into its planning line, or that line, when missing. */
private fun startSchedule(text: String): CompletionResult {
    val headingEnd = lineEnd(text, 0)
    val planningStart = headingEnd + 1
    val planning = if (planningStart <= text.length) text.substring(planningStart, lineEnd(text, planningStart)) else ""
    if (planningStart > text.length || !OrgSyntax.planning.containsMatchIn(planning)) {
        return splice(text, headingEnd, headingEnd, "\nSCHEDULED: ")
    }
    val keyword = SCHEDULED.find(planning)
    if (keyword == null) {
        // Before the line's other dates, with a space left between, where the new one goes.
        val indent = planning.takeWhile { it == ' ' || it == '\t' }.length
        val result = splice(text, planningStart + indent, planningStart + indent, "SCHEDULED:  ")
        return CompletionResult(result.text, result.caret - 1)
    }
    // Just after its date, where the suggestions offer to move it; or after the keyword, for one.
    val stamp = OrgSyntax.timestamp.find(planning, keyword.range.last + 1)
        ?.takeIf { planning.substring(keyword.range.last + 1, it.range.first).isBlank() }
    val caret = planningStart + (stamp?.range?.last?.plus(1) ?: (keyword.range.last + 1))
    return CompletionResult(text, caret)
}

/**
 * At the end of the heading's `:LOCATION:`, adding the property — and its drawer, under the heading
 * and its planning line — when it is not there yet.
 */
private fun startLocation(text: String): CompletionResult {
    var lineStart = lineEnd(text, 0) + 1
    if (lineStart <= text.length && OrgSyntax.planning.containsMatchIn(lineAt(text, lineStart))) {
        lineStart = lineEnd(text, lineStart) + 1
    }
    if (lineStart > text.length || !PROPERTIES.matches(lineAt(text, lineStart))) {
        val at = (lineStart - 1).coerceAtMost(text.length)
        val result = splice(text, at, at, "\n:PROPERTIES:\n:LOCATION: \n:END:")
        return CompletionResult(result.text, at + "\n:PROPERTIES:\n:LOCATION: ".length)
    }
    var line = lineEnd(text, lineStart) + 1
    while (line <= text.length) {
        val content = lineAt(text, line)
        if (LOCATION.containsMatchIn(content)) {
            val end = lineEnd(text, line)
            // An empty one gets a space after its key, so what is picked does not run into it.
            return if (content.trim().equals(":LOCATION:", ignoreCase = true)) {
                splice(text, line + content.trimEnd().length, end, " ")
            } else {
                CompletionResult(text, end)
            }
        }
        if (OrgSyntax.drawerEnd.containsMatchIn(content)) {
            val result = splice(text, line, line, ":LOCATION: \n")
            return CompletionResult(result.text, line + ":LOCATION: ".length)
        }
        line = lineEnd(text, line) + 1
    }
    return CompletionResult(text, text.length)
}

/**
 * An empty checkbox item: in place of an empty line, after the item the caret is on, or as the
 * element's last line — never between a heading and its planning line or drawer.
 */
private fun startCheckbox(text: String, caret: Int): CompletionResult {
    val lineStart = text.lastIndexOf('\n', caret - 1) + 1
    val end = lineEnd(text, lineStart)
    val line = text.substring(lineStart, end)
    val inHead = lineStart == 0 && OrgSyntax.heading.containsMatchIn(line) ||
        OrgSyntax.planning.containsMatchIn(line) ||
        OrgSyntax.property.containsMatchIn(line) ||
        OrgSyntax.drawerBegin.containsMatchIn(line)
    return when {
        line.isBlank() && !inHead -> splice(text, lineStart, end, "- [ ] ")
        !inHead -> {
            val indent = OrgSyntax.listItem.find(line)?.groupValues?.get(1).orEmpty()
            splice(text, end, end, "\n$indent- [ ] ")
        }
        else -> {
            val body = text.trimEnd('\n')
            splice(text, body.length, text.length, "\n- [ ] ")
        }
    }
}

/** [text] with [from]–[to] replaced by [insert], and the caret after it. */
private fun splice(text: String, from: Int, to: Int, insert: String): CompletionResult =
    CompletionResult(text.replaceRange(from, to, insert), from + insert.length)

private fun lineEnd(text: String, from: Int): Int = text.indexOf('\n', from).let { if (it < 0) text.length else it }

private fun lineAt(text: String, start: Int): String = text.substring(start, lineEnd(text, start))

private fun firstLine(text: String): String = lineAt(text, 0)

private val SCHEDULED = Regex("SCHEDULED:", RegexOption.IGNORE_CASE)
private val PROPERTIES = Regex("^\\s*:PROPERTIES:\\s*$", RegexOption.IGNORE_CASE)
private val LOCATION = Regex("^\\s*:LOCATION:", RegexOption.IGNORE_CASE)
