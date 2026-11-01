package com.korgnote.common.org

/**
 * A subtree cut out of a note, as `org-cut-subtree` cuts it but for the blank lines after it, which
 * stay: [subtree] is the heading with its body and every descendant, always ending in a newline; [rest] is the note without it; [start] until
 * [end] is where it was, and [level] its heading's level.
 */
data class CutOrgSubtree(val subtree: String, val rest: String, val start: Int, val end: Int, val level: Int)

/** The heading at element [index] cut out with its subtree; null for the preamble or a missing index. */
fun String.cutOrgSubtree(index: Int): CutOrgSubtree? {
    val elements = orgElements()
    val element = elements.getOrNull(index) ?: return null
    if (element.isPreamble) return null
    var end = elements[elements.subtreeEnd(index) - 1].endExclusive
    // The blank lines that end it stay where they are, keeping the spacing between what was around
    // it; the heading line itself always goes.
    val firstLineEnd = indexOf('\n', element.start).let { if (it < 0) length else it + 1 }
    while (end > firstLineEnd) {
        val lineStart = lastIndexOf('\n', end - 2) + 1
        if (lineStart < firstLineEnd || substring(lineStart, end).isNotBlank()) break
        end = lineStart
    }
    val subtree = substring(element.start, end).let { if (it.endsWith('\n')) it else it + "\n" }
    return CutOrgSubtree(subtree, removeRange(element.start, end), element.start, end, element.level)
}

/**
 * [subtree] — a heading and what is under it, as [cutOrgSubtree] gives it — with its top heading at
 * [level] and every heading below it moved by as much, as `org-paste-subtree` levels what it pastes.
 */
fun orgSubtreeAtLevel(subtree: String, level: Int): String {
    val top = subtree.takeWhile { it == '*' }.length
    if (top == 0 || top == level) return subtree
    return subtree.withOrgSubtreeShifted(0, level - top) ?: subtree
}

/**
 * This note with [subtree] filed under the heading that starts at [headingStart]: after everything
 * already under it, one level below it, as `org-refile` files. With no heading, at the end of the
 * note as a top-level heading. Null when no heading starts at [headingStart].
 *
 * Blank lines that end the place it goes stay after it, so the heading that follows keeps its
 * spacing; nothing else in the note is touched.
 */
fun String.withOrgSubtreeFiled(subtree: String, headingStart: Int?): String? {
    val (end, level) = if (headingStart == null) {
        length to 1
    } else {
        val elements = orgElements()
        val index = elements.indexOfFirst { !it.isPreamble && it.start == headingStart }
        if (index < 0) return null
        elements[elements.subtreeEnd(index) - 1].endExclusive to elements[index].level + 1
    }
    var at = end
    while (at > 0) {
        val lineStart = lastIndexOf('\n', at - 2) + 1
        if (lineStart >= at || substring(lineStart, at).isNotBlank()) break
        at = lineStart
    }
    val before = substring(0, at)
    val separator = if (before.isEmpty() || before.endsWith('\n')) "" else "\n"
    return before + separator + orgSubtreeAtLevel(subtree, level) + substring(at)
}

/**
 * This note with the heading at element [index] and its subtree refiled under the heading that
 * starts at [headingStart] in the same note, or to the end of it with none. Null when there is
 * nothing to move, when that heading is gone, or when it lies inside the subtree itself, which
 * cannot hold itself.
 */
fun String.withOrgSubtreeRefiled(index: Int, headingStart: Int?): String? {
    val cut = cutOrgSubtree(index) ?: return null
    if (headingStart != null && headingStart in cut.start until cut.end) return null
    val target = headingStart?.let { if (it > cut.start) it - (cut.end - cut.start) else it }
    return cut.rest.withOrgSubtreeFiled(cut.subtree, target)
}
