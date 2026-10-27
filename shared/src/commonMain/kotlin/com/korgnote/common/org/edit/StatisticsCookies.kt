package com.korgnote.common.org.edit

import com.korgnote.common.org.OrgCheckboxItem
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgList
import com.korgnote.common.org.OrgListItem
import com.korgnote.common.org.OrgNode
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.toOrgDocument

/**
 * Org's statistics cookies, `[2/5]` and `[40%]`: how much of what is under a heading or a list item
 * is done, written in its own line and kept up to date as things are ticked off.
 *
 * On a heading, the cookie counts its direct sub-headings that carry a keyword, done against all —
 * or, when it has none of those, the checkboxes of its own body's lists. On a list item, it counts
 * the checkboxes directly under the item. Those are Org's defaults
 * (`org-hierarchical-todo-statistics` and `org-checkbox-hierarchical-statistics` both on).
 */
data class Progress(val done: Int, val total: Int) {
    /** Rounded down, as Org writes it; nothing to do counts as none done. */
    val percent: Int get() = if (total == 0) 0 else done * 100 / total
}

/** A cookie in a line: `[n/m]`, `[/]`, `[n%]` or `[%]`, with the parts as typed. */
val STATISTICS_COOKIE = Regex("\\[(\\d*)(?:/(\\d*)|%)]")

/** What the cookie [match] says, or null for an empty one (`[/]`, `[%]`) that has yet to be filled. */
fun progressOf(match: MatchResult): Progress? {
    val (done, total) = match.destructured
    return when {
        match.value.endsWith("%]") -> done.toIntOrNull()?.let { Progress(it.coerceAtMost(100), 100) }
        else -> done.toIntOrNull()?.let { d -> total.toIntOrNull()?.let { t -> Progress(d, t) } }
    }
}

/** The cookie [match] rewritten to say [progress], in the form it was typed: fraction or percent. */
private fun cookieFor(match: MatchResult, progress: Progress): String =
    if (match.value.endsWith("%]")) "[${progress.percent}%]" else "[${progress.done}/${progress.total}]"

/**
 * [text] with every statistics cookie saying what is now under it, and nothing else changed: each
 * cookie is a range replacement in its own line. Text with no cookie in it is returned as is,
 * without being parsed.
 */
fun updateStatisticsCookies(text: String, todoKeywords: OrgTodoConfig): String {
    if (!STATISTICS_COOKIE.containsMatchIn(text)) return text
    val document = text.toOrgDocument()
    val edits = mutableListOf<Pair<IntRange, String>>()
    for (node in document.allNodes()) {
        val progress = when (node) {
            is OrgSection -> headingProgress(node, todoKeywords)
            is OrgCheckboxItem, is OrgListItem -> Progress(
                done = node.childCheckboxes().count { it.checked },
                total = node.childCheckboxes().size,
            )
            else -> continue
        }
        val line = document.firstLineOf(node) ?: continue
        val cookie = STATISTICS_COOKIE.find(text.substring(line)) ?: continue
        val replacement = cookieFor(cookie, progress)
        if (replacement != cookie.value) {
            val start = line.first + cookie.range.first
            edits += (start..(line.first + cookie.range.last)) to replacement
        }
    }
    if (edits.isEmpty()) return text
    var updated = text
    for ((range, replacement) in edits.sortedByDescending { it.first.first }) {
        updated = updated.replaceRange(range, replacement)
    }
    return updated
}

/** What a heading's cookie counts: its keyworded sub-headings, else its own checkboxes. */
fun headingProgress(section: OrgSection, todoKeywords: OrgTodoConfig): Progress {
    val keyworded = section.children.filterIsInstance<OrgSection>()
        .filter { it.todo != null && (it.todo in todoKeywords.todo || it.todo in todoKeywords.done) }
    if (keyworded.isNotEmpty()) {
        return Progress(keyworded.count { it.todo in todoKeywords.done }, keyworded.size)
    }
    val boxes = section.children.filterIsInstance<OrgList>().flatMap { it.children }.filterIsInstance<OrgCheckboxItem>()
    return Progress(boxes.count { it.checked }, boxes.size)
}

/** The checkboxes of the lists directly under a list item. */
private fun OrgNode.childCheckboxes(): List<OrgCheckboxItem> =
    children.filterIsInstance<OrgList>().flatMap { it.children }.filterIsInstance<OrgCheckboxItem>()

/** The range of [node]'s first line in the document's text, or null for a node with no source. */
private fun OrgDocument.firstLineOf(node: OrgNode): IntRange? {
    if (node.range.isEmpty()) return null
    val start = node.range.first
    if (start !in 0..text.length) return null
    val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
    return start until end
}
