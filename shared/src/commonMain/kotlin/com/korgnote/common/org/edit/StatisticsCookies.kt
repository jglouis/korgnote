package com.korgnote.common.org.edit

import com.korgnote.common.org.OrgCheckboxItem
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgDrawer
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
 *
 * Given [before], the text this edit started from, only the cookies over what changed are brought
 * up to date. One elsewhere in the note is left as it is, even if it counts differently: it may do
 * so on purpose, kept by Emacs with other settings, and an edit is promised to leave the rest of
 * the note byte for byte as it was.
 */
fun updateStatisticsCookies(text: String, todoKeywords: OrgTodoConfig, before: String? = null): String {
    if (!STATISTICS_COOKIE.containsMatchIn(text)) return text
    val changed = before?.let { changedRegion(it, text) }
    if (before != null && changed == null) return text
    val document = text.toOrgDocument()
    val edits = mutableListOf<Pair<IntRange, String>>()
    for (node in document.allNodes()) {
        // Over the change: it starts before where the change ends, and ends at or after where it
        // starts — just before, for something added at the very end of it.
        if (changed != null && (node.range.isEmpty() || node.range.first >= changed.end || node.range.last + 1 < changed.start)) {
            continue
        }
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

/** Where an edit changed a text: from [start] to [end], exclusive, in the text after it. */
private data class Change(val start: Int, val end: Int)

/**
 * Where [after] differs from [before], in whole lines, or null when they are equal. Whole lines,
 * because a character-wise diff is ambiguous where lines start alike: a heading added at the end of
 * a subtree would otherwise reach into the first character of the heading after it.
 */
private fun changedRegion(before: String, after: String): Change? {
    if (before == after) return null
    val shorter = minOf(before.length, after.length)
    var prefix = 0
    while (prefix < shorter && before[prefix] == after[prefix]) prefix++
    prefix = before.lastIndexOf('\n', prefix - 1) + 1
    var suffix = 0
    while (suffix < shorter - prefix && before[before.length - 1 - suffix] == after[after.length - 1 - suffix]) suffix++
    var end = after.length - suffix
    if (end > prefix && after[end - 1] != '\n') {
        end = after.indexOf('\n', end).let { if (it < 0) after.length else it + 1 }
    }
    return Change(prefix, end)
}
/**
 * What a heading's cookie counts: its keyworded sub-headings, else its own checkboxes — or what its
 * `:COOKIE_DATA:` property asks for, `todo` or `checkbox`, at every depth below with `recursive`.
 */
fun headingProgress(section: OrgSection, todoKeywords: OrgTodoConfig): Progress {
    val cookieData = section.children.filterIsInstance<OrgDrawer>()
        .firstOrNull { it.name.equals("PROPERTIES", ignoreCase = true) }
        ?.properties?.firstOrNull { it.first.equals("COOKIE_DATA", ignoreCase = true) }
        ?.second?.lowercase()?.split(' ', ',')?.filter { it.isNotBlank() }
        .orEmpty()
    val recursive = "recursive" in cookieData

    fun keyworded(): List<OrgSection> {
        val below = if (recursive) section.subHeadings() else section.children.filterIsInstance<OrgSection>()
        return below.filter { it.todo != null && (it.todo in todoKeywords.todo || it.todo in todoKeywords.done) }
    }
    fun boxes(): List<OrgCheckboxItem> {
        val items = section.children.filterIsInstance<OrgList>().flatMap { it.children }
        return (if (recursive) items.flatMap { listOf(it) + it.nestedItems() } else items).filterIsInstance<OrgCheckboxItem>()
    }
    fun headingsDone(headings: List<OrgSection>) = Progress(headings.count { it.todo in todoKeywords.done }, headings.size)
    fun boxesTicked(boxes: List<OrgCheckboxItem>) = Progress(boxes.count { it.checked }, boxes.size)

    return when {
        "checkbox" in cookieData -> boxesTicked(boxes())
        "todo" in cookieData -> headingsDone(keyworded())
        else -> keyworded().takeIf { it.isNotEmpty() }?.let(::headingsDone) ?: boxesTicked(boxes())
    }
}

/** Every heading below this one, at any depth. */
private fun OrgSection.subHeadings(): List<OrgSection> =
    children.filterIsInstance<OrgSection>().flatMap { listOf(it) + it.subHeadings() }

/** Every list item under this one, at any depth. */
private fun OrgNode.nestedItems(): List<OrgNode> =
    children.filterIsInstance<OrgList>().flatMap { it.children }.flatMap { listOf(it) + it.nestedItems() }

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
