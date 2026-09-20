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
    for (line in splitToSequence('\n')) {
        if (isHeadingLine(line)) {
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

private val checkboxLineRegex = Regex("^\\s*[-+]\\s+\\[([ xX])\\]\\s*.*")

/**
 * How many checkbox items precede [offset].
 *
 * Reading mode renders each element separately but reports checkbox toggles by their position in
 * the whole document, so each element needs to know where its own numbering starts.
 */
fun String.orgCheckboxesBefore(offset: Int): Int {
    var count = 0
    var lineStart = 0
    for (line in splitToSequence('\n')) {
        if (lineStart >= offset) break
        if (checkboxLineRegex.matches(line)) count++
        lineStart += line.length + 1
    }
    return count
}
