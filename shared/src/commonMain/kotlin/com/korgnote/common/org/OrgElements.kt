package com.korgnote.common.org

/**
 * A directly editable piece of a document: a heading together with its own body — planning lines,
 * drawers, lists, tables and paragraphs — but *not* its child headings, which are elements of their
 * own. Text before the first heading (`#+TITLE:` and friends) is the element at index 0, with
 * [level] 0.
 *
 * [start] is inclusive, [endExclusive] exclusive, both into the document text the element came
 * from. Together they make editing one element a pure text replacement over that range, which is
 * what keeps the rest of the file byte-identical.
 */
data class OrgElement(
    val start: Int,
    val endExclusive: Int,
    val level: Int,
    val title: String,
) {
    /** True for the text preceding the first heading. */
    val isPreamble: Boolean get() = level == 0
}

/** The element's own source text. */
fun OrgElement.sliceOf(text: String): String = text.substring(start, endExclusive)

/**
 * A line begins a heading.
 *
 * Deliberately the same rule as [toOrgDocument]'s section detection rather than the stricter
 * "stars followed by whitespace" that Org itself uses: if the splitter and the parser disagreed
 * about where a heading starts, the range shown for editing would not be the range written back.
 * Consistency between the two matters more here than either being individually right — a
 * disagreement corrupts a file, whereas a shared quirk merely splits an element oddly.
 *
 * Both should move to the real grammar together; see `docs/04-org-core.md`.
 */
private fun isHeadingLine(line: String): Boolean = line.startsWith("*")

/** Number of leading `*` characters. */
private fun headingLevel(line: String): Int = line.takeWhile { it == '*' }.length

/** Heading text with the stars and surrounding whitespace removed. */
private fun headingTitle(line: String): String = line.dropWhile { it == '*' }.trim()

/**
 * Splits the document into directly editable [OrgElement]s, in document order and covering the
 * whole text with no gaps or overlaps.
 */
fun String.orgElements(): List<OrgElement> {
    val headings = mutableListOf<OrgElement>()
    var offset = 0
    // Blocks and drawers are opaque, exactly as they are to the parser: a `* foo` inside a source
    // block is code. The two must agree line for line, or the range offered for editing would not be
    // the range written back — which is what `headingCountMatchesTheParser` guards.
    var blockType: String? = null
    var inDrawer = false

    for (line in splitToSequence('\n')) {
        val isHeading = when {
            blockType != null -> {
                val end = OrgSyntax.blockEnd.find(line)
                if (end != null && (end.groupValues[1].isEmpty() ||
                        end.groupValues[1].equals(blockType, ignoreCase = true))
                ) {
                    blockType = null
                }
                false
            }

            inDrawer -> when {
                OrgSyntax.drawerEnd.matches(line) -> { inDrawer = false; false }
                // A heading closes a drawer that was never terminated, as it does in the parser.
                isHeadingLine(line) -> { inDrawer = false; true }
                else -> false
            }

            else -> {
                val begin = OrgSyntax.blockBegin.find(line)
                when {
                    begin != null -> { blockType = begin.groupValues[1]; false }
                    OrgSyntax.drawerBegin.matches(line) && !OrgSyntax.drawerEnd.matches(line) -> {
                        inDrawer = true
                        false
                    }
                    else -> isHeadingLine(line)
                }
            }
        }
        if (isHeading) {
            // endExclusive is filled in below, once the following heading is known.
            headings.add(OrgElement(offset, offset, headingLevel(line), headingTitle(line)))
        }
        offset += line.length + 1
    }

    val elements = mutableListOf<OrgElement>()
    val firstHeadingStart = headings.firstOrNull()?.start ?: length
    // A document opening on a heading has no preamble; an empty document is all preamble.
    if (firstHeadingStart > 0 || headings.isEmpty()) {
        elements.add(OrgElement(0, firstHeadingStart, 0, ""))
    }
    headings.forEachIndexed { index, heading ->
        val end = headings.getOrNull(index + 1)?.start ?: length
        elements.add(heading.copy(endExclusive = end))
    }
    return elements
}

/**
 * Replaces [element]'s text with [replacement], returning the whole document.
 *
 * Refuses when [element] no longer describes [expected] — the document moved underneath the edit,
 * and writing at a stale range would overwrite something the user never saw.
 *
 * A trailing newline is added when one is needed to keep the following element on its own line, so
 * an edit cannot accidentally weld two headings together.
 */
fun String.replaceOrgElement(element: OrgElement, expected: String, replacement: String): String? {
    if (element.start < 0 || element.endExclusive > length || element.start > element.endExclusive) return null
    if (substring(element.start, element.endExclusive) != expected) return null

    val hasFollowingElement = element.endExclusive < length
    val separated =
        if (hasFollowingElement && !replacement.endsWith("\n")) replacement + "\n" else replacement
    return replaceRange(element.start, element.endExclusive, separated)
}

/** Result of [withNewOrgElement]: the updated document, and where the new element and caret sit. */
data class OrgInsertion(val text: String, val elementStart: Int, val caret: Int)

/**
 * Inserts an empty heading of [level] at [at], returning the new document together with the offsets
 * the editor needs.
 *
 * The heading carries no template beyond its stars: what follows is ordinary Org text the user
 * types. A newline is added before it when [at] does not already sit at the start of a line, so a
 * heading appended to a document lacking a final newline still gets a line of its own.
 */
fun String.withNewOrgElement(at: Int, level: Int): OrgInsertion {
    val stars = "*".repeat(level.coerceAtLeast(1))
    val prefix = if (at > 0 && this[at - 1] != '\n') "\n" else ""
    val insertion = "$prefix$stars \n"
    val elementStart = at + prefix.length
    return OrgInsertion(
        text = replaceRange(at, at, insertion),
        elementStart = elementStart,
        caret = elementStart + stars.length + 1,
    )
}

/**
 * One past the last element of [index]'s subtree: the next element at its level or above, or the
 * end of the list.
 *
 * Elements are flat — a heading's children are elements of their own — so this is how a heading
 * finds everything beneath it, the same range T4 gives a section. The preamble owns no subtree.
 */
fun List<OrgElement>.subtreeEnd(index: Int): Int {
    val element = getOrNull(index) ?: return index
    if (element.isPreamble) return index + 1
    var end = index + 1
    while (end < size && this[end].level > element.level) end++
    return end
}

/** The headings whose subtree holds [index], outermost first. */
fun List<OrgElement>.ancestorsOf(index: Int): List<Int> {
    val element = getOrNull(index) ?: return emptyList()
    if (element.isPreamble) return emptyList()
    val ancestors = mutableListOf<Int>()
    var level = element.level
    for (i in index - 1 downTo 0) {
        val candidate = this[i]
        if (candidate.isPreamble) break
        if (candidate.level < level) {
            ancestors.add(i)
            level = candidate.level
        }
    }
    return ancestors.asReversed()
}

/**
 * The document without the heading at [index] and its whole subtree — body and every descendant,
 * as Org's `org-cut-subtree` removes them. Null for the preamble, which is not a heading, or an
 * index that is not there.
 *
 * Elements start at line starts, so the text before the removed range already ends in a newline,
 * and what follows keeps its own line: nothing around the cut is touched.
 */
fun String.withoutOrgSubtree(index: Int): String? {
    val elements = orgElements()
    val element = elements.getOrNull(index) ?: return null
    if (element.isPreamble) return null
    val end = elements[elements.subtreeEnd(index) - 1].endExclusive
    return removeRange(element.start, end)
}

/**
 * The heading before [index] at the same level and under the same parent, or null when there is
 * none — where `org-move-subtree-up` refuses too. Deeper headings on the way are its children.
 */
fun List<OrgElement>.previousSibling(index: Int): Int? {
    val element = getOrNull(index) ?: return null
    if (element.isPreamble) return null
    for (i in index - 1 downTo 0) {
        val candidate = this[i]
        if (candidate.isPreamble || candidate.level < element.level) return null
        if (candidate.level == element.level) return i
    }
    return null
}

/** The heading after [index]'s subtree at the same level, or null when its parent ends first. */
fun List<OrgElement>.nextSibling(index: Int): Int? {
    val element = getOrNull(index) ?: return null
    if (element.isPreamble) return null
    val next = subtreeEnd(index)
    return next.takeIf { it < size && this[it].level == element.level }
}

/**
 * The document with the subtree at [first] and the one at its next sibling [second] exchanged —
 * `org-move-subtree-down` on [first], or `-up` on [second]. Null unless [second] is [first]'s next
 * sibling.
 *
 * Only the two subtrees' text is touched. When the second one ends the document without a final
 * newline, the swapped pair keeps that: the newline moves between them rather than being added.
 */
fun String.withOrgSubtreesSwapped(first: Int, second: Int): String? {
    val elements = orgElements()
    if (elements.nextSibling(first) != second) return null
    val start = elements[first].start
    val middle = elements[second].start
    val end = elements[elements.subtreeEnd(second) - 1].endExclusive
    val upper = substring(start, middle)
    val lower = substring(middle, end)
    val swapped = if (lower.endsWith("\n")) lower + upper else lower + "\n" + upper.removeSuffix("\n")
    return replaceRange(start, end, swapped)
}

/**
 * The document with the heading at [index] and every heading in its subtree [delta] levels deeper
 * — `org-demote-subtree` for 1, `org-promote-subtree` for -1. Null for the preamble, or when the
 * heading would go above level 1.
 *
 * Only the stars change. The headings are the elements' own, so a `*` line inside a block is left
 * alone exactly when the splitter left it alone.
 */
fun String.withOrgSubtreeShifted(index: Int, delta: Int): String? {
    val elements = orgElements()
    val element = elements.getOrNull(index) ?: return null
    if (element.isPreamble || element.level + delta < 1) return null
    val text = this
    return buildString {
        var copied = 0
        for (i in index until elements.subtreeEnd(index)) {
            val heading = elements[i]
            appendRange(text, copied, heading.start)
            append("*".repeat((heading.level + delta).coerceAtLeast(1)))
            copied = heading.start + heading.level
        }
        appendRange(text, copied, text.length)
    }
}
