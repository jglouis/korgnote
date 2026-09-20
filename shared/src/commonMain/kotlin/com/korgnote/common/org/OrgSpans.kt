package com.korgnote.common.org

/**
 * What a stretch of Org text *is*. The kinds carry no styling: `shared/` decides what something is,
 * `composeApp/` decides what colour that is.
 */
enum class OrgSpanKind {
    // Line-level
    Heading, Stars, Todo, Done, Priority, Tag,
    Planning, PlanningKeyword,
    Keyword, Comment, HorizontalRule,
    Drawer, DrawerKey, DrawerValue,
    BlockDelimiter, BlockBody, BlockBodyMonospace, BlockBodyQuote,
    Bullet, Checkbox, DescriptionTerm,
    TableLine, TablePipe, TableRule,

    // Inline
    Timestamp, Footnote, Statistics,
    LinkMarkup, LinkTarget, LinkDescription, Url,
    Bold, Italic, Underline, StrikeThrough, Verbatim, Code,

    /** Markup characters that reading mode hides — emphasis markers and link brackets. */
    Markup,
}

/**
 * [kind] applies to [range] of the document text.
 *
 * Spans may overlap: a heading emits one span over the whole line and finer ones inside it. They are
 * produced in the order a renderer should apply them, outermost first, so applying each in turn
 * reproduces what nesting would have given.
 *
 * [nesting] counts how deeply an inline construct sits inside others, so a renderer can tell nested
 * emphasis apart.
 */
data class OrgSpan(val range: IntRange, val kind: OrgSpanKind, val nesting: Int = 0)

/**
 * Classifies [text], optionally restricted to the lines overlapping [within].
 *
 * This is the single definition of Org syntax for both the highlighter and the reading-mode
 * renderer (D5). It deliberately reproduces one quirk of the old highlighter: a span that carries a
 * colour absorbs the whitespace beside it, because the Skia text backend measures a style run that
 * holds only whitespace as zero width. Splitting the whitespace off would make the caret land in the
 * wrong place, which is the bug `OrgWhitespaceRunTest` exists to prevent.
 */
fun orgSpans(text: String, within: IntRange? = null): List<OrgSpan> {
    val spans = mutableListOf<OrgSpan>()
    val state = OrgLineState()

    var lineStart = 0
    while (lineStart <= text.length) {
        val newline = text.indexOf('\n', lineStart)
        val lineEnd = if (newline == -1) text.length else newline
        val line = text.substring(lineStart, lineEnd)

        // State has to advance over every line, even the ones outside the window, or a visible line
        // inside a block would be classified as ordinary text.
        val visible = within == null || (lineStart <= within.last && lineEnd >= within.first)
        scanLine(line, lineStart, state, if (visible) spans else null)

        if (newline == -1) break
        lineStart = newline + 1
    }
    return spans
}

/**
 * How far into a block or drawer the scan is, carried from one line to the next.
 *
 * Public because a client that highlights line by line — an editor keeping a per-line cache — has to
 * thread the state itself. [copy] lets it snapshot the state before and after each line, so only the
 * lines that actually changed need rescanning.
 */
class OrgLineState {
    /** The block currently open, lowercased, or null outside one. */
    var blockType: String? = null

    /** Whether the scan is inside a drawer. */
    var inDrawer: Boolean = false

    fun copy(): OrgLineState = OrgLineState().also { it.blockType = blockType; it.inDrawer = inDrawer }

    /** Resets this state to [other], so a cached line can be resumed from its recorded start. */
    fun restoreFrom(other: OrgLineState) {
        blockType = other.blockType
        inDrawer = other.inDrawer
    }

    override fun equals(other: Any?) =
        other is OrgLineState && other.blockType == blockType && other.inDrawer == inDrawer

    override fun hashCode() = 31 * (blockType?.hashCode() ?: 0) + inDrawer.hashCode()

    override fun toString() = "OrgLineState(blockType=$blockType, inDrawer=$inDrawer)"
}

/**
 * Classifies a single [line], advancing [state] to what the next line starts from.
 *
 * Offsets in the result are relative to the start of the line. Pass `classify = false` to advance
 * the state without producing spans, which is what a client does for lines it is not styling.
 */
fun orgSpansForLine(line: String, state: OrgLineState, classify: Boolean = true): List<OrgSpan> {
    val spans = if (classify) mutableListOf<OrgSpan>() else null
    scanLine(line, 0, state, spans)
    return spans ?: emptyList()
}

private fun scanLine(
    line: String,
    offset: Int,
    state: OrgLineState,
    sink: MutableList<OrgSpan>?,
) {
    fun add(range: IntRange, kind: OrgSpanKind, nesting: Int = 0) {
        if (sink != null && !range.isEmpty()) sink.add(OrgSpan(range, kind, nesting))
    }
    fun lineRange() = offset until (offset + line.length)

    if (state.blockType != null) {
        if (OrgSyntax.blockEnd.containsMatchIn(line)) {
            add(lineRange(), OrgSpanKind.BlockDelimiter)
            state.blockType = null
        } else {
            // Which flavour of block this is remains grammar; only the colours are the client's.
            val kind = when (state.blockType) {
                in OrgSyntax.monospaceBlocks -> OrgSpanKind.BlockBodyMonospace
                "quote", "verse" -> OrgSpanKind.BlockBodyQuote
                else -> OrgSpanKind.BlockBody
            }
            add(lineRange(), kind)
        }
        return
    }

    if (state.inDrawer) {
        state.inDrawer = !OrgSyntax.drawerEnd.containsMatchIn(line)
        addDrawerLine(line, offset, ::add)
        return
    }

    when {
        OrgSyntax.heading.containsMatchIn(line) -> addHeading(line, offset, ::add)

        OrgSyntax.blockBegin.containsMatchIn(line) -> {
            add(lineRange(), OrgSpanKind.BlockDelimiter)
            state.blockType = OrgSyntax.blockBegin.find(line)!!.groupValues[1].lowercase()
        }

        OrgSyntax.blockEnd.containsMatchIn(line) -> add(lineRange(), OrgSpanKind.BlockDelimiter)

        OrgSyntax.keyword.containsMatchIn(line) -> add(lineRange(), OrgSpanKind.Keyword)

        OrgSyntax.comment.containsMatchIn(line) -> add(lineRange(), OrgSpanKind.Comment)

        OrgSyntax.drawerBegin.containsMatchIn(line) -> {
            state.inDrawer = !OrgSyntax.drawerEnd.containsMatchIn(line)
            addDrawerLine(line, offset, ::add)
        }

        OrgSyntax.planning.containsMatchIn(line) -> {
            add(lineRange(), OrgSpanKind.Planning)
            for (m in OrgSyntax.planningKeyword.findAll(line)) {
                add(offset + m.range.first..offset + m.range.last, OrgSpanKind.PlanningKeyword)
            }
            addInline(line, offset, 0, ::add)
        }

        OrgSyntax.isTableLine(line) -> {
            add(lineRange(), OrgSpanKind.TableLine)
            if (OrgSyntax.isTableRuleLine(line)) {
                add(lineRange(), OrgSpanKind.TableRule)
            } else {
                line.forEachIndexed { i, c ->
                    if (c == '|') add(offset + i..offset + i, OrgSpanKind.TablePipe)
                }
                addInline(line, offset, 0, ::add)
            }
        }

        OrgSyntax.horizontalRule.containsMatchIn(line) -> add(lineRange(), OrgSpanKind.HorizontalRule)

        else -> {
            val item = OrgSyntax.listItem.find(line)
            if (item != null) addListItem(line, offset, item, ::add) else addInline(line, offset, 0, ::add)
        }
    }
}

private fun addDrawerLine(line: String, offset: Int, add: (IntRange, OrgSpanKind, Int) -> Unit) {
    add(offset until (offset + line.length), OrgSpanKind.Drawer, 0)
    OrgSyntax.property.find(line)?.let { prop ->
        val key = prop.groupValues[1]
        add(offset until (offset + key.length), OrgSpanKind.DrawerKey, 0)
        if (prop.groupValues[2].isNotEmpty()) {
            add(offset + key.length until (offset + line.length), OrgSpanKind.DrawerValue, 0)
        }
    }
}

/**
 * A heading line.
 *
 * The pieces absorb the whitespace between them rather than leaving it in runs of its own; see the
 * note on [orgSpans].
 */
private fun addHeading(line: String, offset: Int, add: (IntRange, OrgSpanKind, Int) -> Unit) {
    val stars = OrgSyntax.heading.find(line)!!.groupValues[1]
    add(offset until (offset + line.length), OrgSpanKind.Heading, stars.length)

    // Pieces are collected first so the whitespace between them can be folded in before anything is
    // emitted: a span that holds only whitespace is measured as zero width by the Skia text backend,
    // which moves the caret. See the note on [orgSpans] and `OrgWhitespaceRunTest`.
    val pieces = mutableListOf<Pair<IntRange, OrgSpanKind?>>()
    var pos = stars.length
    pieces.add(0 until pos to OrgSpanKind.Stars)

    fun skipWhitespace() { while (pos < line.length && line[pos].isWhitespace()) pos++ }
    skipWhitespace()

    val firstWord = line.substring(pos).takeWhile { !it.isWhitespace() }
    if (firstWord.isNotEmpty() &&
        firstWord.all { it.isUpperCase() || it.isDigit() || it == '_' || it == '-' }
    ) {
        // Whether this counts as a TODO keyword depends on the document's configuration, which the
        // client knows; the span says only that this is the heading's keyword position.
        pieces.add(pos until (pos + firstWord.length) to OrgSpanKind.Todo)
        pos += firstWord.length
        skipWhitespace()
    }

    OrgSyntax.priority.find(line.substring(pos))?.let { priority ->
        pieces.add(pos until (pos + priority.value.length) to OrgSpanKind.Priority)
        pos += priority.value.length
        skipWhitespace()
    }

    val tags = OrgSyntax.tags.find(line.substring(pos))
    val bodyStart = pos
    val bodyEnd = if (tags != null) pos + tags.range.first else line.length
    pieces.add(bodyStart until bodyEnd to null)
    if (tags != null) pieces.add((pos + tags.range.first) until line.length to OrgSpanKind.Tag)

    // Every gap goes to the piece that follows it; whatever is left at the end goes to the piece
    // before it, so no whitespace is ever left in a run of its own.
    var cursor = 0
    val emitted = mutableListOf<Pair<IntRange, OrgSpanKind?>>()
    for ((range, kind) in pieces) {
        if (range.isEmpty() && kind == null) continue
        emitted.add(cursor until (range.last + 1) to kind)
        cursor = range.last + 1
    }
    if (cursor < line.length && emitted.isNotEmpty()) {
        val (last, kind) = emitted.removeAt(emitted.lastIndex)
        emitted.add(last.first until line.length to kind)
    }

    for ((range, kind) in emitted) {
        if (range.isEmpty()) continue
        if (kind != null) {
            add(offset + range.first..offset + range.last, kind, 0)
        } else {
            addInline(line.substring(range.first, range.last + 1), offset + range.first, 0, add)
        }
    }
}

private fun addListItem(line: String, offset: Int, item: MatchResult, add: (IntRange, OrgSpanKind, Int) -> Unit) {
    // The indent, the bullet and the whitespace after it share one span, for the same reason the
    // heading pieces do.
    val checkbox = item.groupValues[4]
    val bulletEnd = item.value.length - checkbox.length
    add(offset until (offset + bulletEnd), OrgSpanKind.Bullet, 0)
    if (checkbox.isNotEmpty()) {
        add(offset + bulletEnd until (offset + item.value.length), OrgSpanKind.Checkbox, 0)
    }

    val textStart = item.value.length
    val text = line.substring(textStart)
    OrgSyntax.descriptionTerm.find(text)?.let { term ->
        add(offset + textStart until (offset + textStart + term.value.length), OrgSpanKind.DescriptionTerm, 0)
    }
    addInline(text, offset + textStart, 0, add)
}

/** Links, emphasis, timestamps, footnotes and statistics inside one line of text. */
private fun addInline(
    text: String,
    offset: Int,
    nesting: Int,
    add: (IntRange, OrgSpanKind, Int) -> Unit,
) {
    var i = 0
    while (i < text.length) {
        var consumed = 0
        when (text[i]) {
            '[' -> {
                OrgSyntax.link.matchAt(text, i)?.let { m ->
                    addLink(m, offset + i, nesting, add)
                    consumed = m.value.length
                } ?: OrgSyntax.footnoteReference.matchAt(text, i)?.let { m ->
                    add(offset + i until (offset + i + m.value.length), OrgSpanKind.Footnote, nesting)
                    consumed = m.value.length
                } ?: OrgSyntax.statistics.matchAt(text, i)?.let { m ->
                    add(offset + i until (offset + i + m.value.length), OrgSpanKind.Statistics, nesting)
                    consumed = m.value.length
                } ?: OrgSyntax.timestamp.matchAt(text, i)?.let { m ->
                    add(offset + i until (offset + i + m.value.length), OrgSpanKind.Timestamp, nesting)
                    consumed = m.value.length
                }
            }

            '<' -> OrgSyntax.timestamp.matchAt(text, i)?.let { m ->
                add(offset + i until (offset + i + m.value.length), OrgSpanKind.Timestamp, nesting)
                consumed = m.value.length
            }

            'h', 'f', 'm' -> if (i == 0 || !text[i - 1].isLetterOrDigit()) {
                OrgSyntax.url.matchAt(text, i)?.let { m ->
                    add(offset + i until (offset + i + m.value.length), OrgSpanKind.Url, nesting)
                    consumed = m.value.length
                }
            }

            '*', '/', '_', '+', '=', '~' -> if (OrgSyntax.emphasisMayOpenAt(text, i)) {
                val marker = text[i]
                OrgSyntax.emphasis.getValue(marker).matchAt(text, i)?.let { m ->
                    val end = i + m.value.length
                    if (OrgSyntax.emphasisMayCloseAt(text, end)) {
                        addEmphasis(marker, m, offset + i, nesting, add)
                        consumed = m.value.length
                    }
                }
            }
        }
        i += if (consumed == 0) 1 else consumed
    }
}

private fun emphasisKind(marker: Char) = when (marker) {
    '*' -> OrgSpanKind.Bold
    '/' -> OrgSpanKind.Italic
    '_' -> OrgSpanKind.Underline
    '+' -> OrgSpanKind.StrikeThrough
    '=' -> OrgSpanKind.Verbatim
    else -> OrgSpanKind.Code
}

private fun addEmphasis(
    marker: Char,
    m: MatchResult,
    offset: Int,
    nesting: Int,
    add: (IntRange, OrgSpanKind, Int) -> Unit,
) {
    val content = m.groupValues[1]
    // The style belongs to the content; the markers are markup the reading mode hides.
    add(offset + 1 until (offset + 1 + content.length), emphasisKind(marker), nesting)
    add(offset until (offset + 1), OrgSpanKind.Markup, nesting)
    add(offset + m.value.length - 1 until (offset + m.value.length), OrgSpanKind.Markup, nesting)
    // Verbatim and code are literal; other emphasis may contain nested constructs.
    if (marker != '=' && marker != '~') addInline(content, offset + 1, nesting + 1, add)
}

private fun addLink(m: MatchResult, offset: Int, nesting: Int, add: (IntRange, OrgSpanKind, Int) -> Unit) {
    val target = m.groupValues[1]
    val description = m.groupValues[2]
    add(offset until (offset + 2), OrgSpanKind.Markup, nesting)
    add(offset + 2 until (offset + 2 + target.length), OrgSpanKind.LinkTarget, nesting)
    if (m.value.length > target.length + 4) {
        val descStart = offset + 2 + target.length
        add(descStart until (descStart + 2), OrgSpanKind.Markup, nesting)
        add(descStart + 2 until (descStart + 2 + description.length), OrgSpanKind.LinkDescription, nesting)
    }
    add(offset + m.value.length - 2 until (offset + m.value.length), OrgSpanKind.Markup, nesting)
    add(offset until (offset + m.value.length), OrgSpanKind.LinkMarkup, nesting)
}

/** Spans for a slice of this document — the slice is what makes viewport highlighting possible. */
fun OrgDocument.spans(range: IntRange): List<OrgSpan> = orgSpans(text, range)
