package com.korgnote.common.org

/**
 * A node in an org document.
 *
 * [range] is where this node's source sits in the document it came from, so that changing a node is
 * a text replacement over that range rather than a re-render of the tree — see
 * `docs/04-org-core.md`.
 *
 * It is deliberately not a constructor property. These are data classes, so a constructor property
 * would join `equals`, and every expected node in every parser test would have to spell out exact
 * offsets. Parsing fills the range in; a node built by hand keeps an empty one.
 */
sealed class OrgNode {
    var range: IntRange = IntRange.EMPTY
        internal set

    /**
     * This node's children, so that a walk over the tree does not have to know every node type.
     *
     * Leaves keep the empty default. A node that holds others overrides it, including the ones that
     * name their children something else (a table's rows, a row's cells).
     */
    open val children: List<OrgNode> get() = emptyList()
}

// Top-level org document with parsed children and collected file keywords (#+TITLE, #+AUTHOR, etc.).
data class OrgDocument(
    override val children: List<OrgNode>,
    val metadata: List<String>,
) : OrgNode() {
    /**
     * The exact text this document was parsed from; empty when it was built by hand.
     *
     * Holding the source is what makes the model lossless: a construct the parser does not model
     * still survives an edit, because edits apply to this text rather than being regenerated from
     * the tree.
     */
    var text: String = ""
        internal set

    /**
     * The TODO keywords this document's headings were read with: its own `#+TODO:` lines, or
     * [todoFallback] when it has none. A heading's [OrgSection.todo] is one of them or null.
     */
    var todoKeywords: OrgTodoConfig = DEFAULT_TODO_KEYWORDS
        internal set

    /**
     * The keywords it was told to fall back on, kept so that an edit's re-parse reads its headings
     * the same way. See [toOrgDocument].
     */
    var todoFallback: OrgTodoConfig = DEFAULT_TODO_KEYWORDS
        internal set
}

/**
 * A heading together with everything under it: its body and its sub-headings.
 *
 * [level] is the number of leading stars; [tags] are the trailing `:tag1:tag2:` without the colons.
 *
 * The body is [children], not a pre-trimmed string. That is what makes a section's [range] cover its
 * whole subtree, so promoting, moving or deleting a heading with everything beneath it is one text
 * replacement — and it is why a sub-heading is a child of its parent rather than a sibling.
 */
data class OrgSection(
    val title: String,
    val todo: String? = null,
    val level: Int = 1,
    val tags: List<String> = emptyList(),
    override val children: List<OrgNode> = emptyList(),
    /** The `[#A]` cookie, without its brackets, or null when the heading has none. */
    val priority: OrgPriority? = null,
) : OrgNode()

/** [key]'s value in this heading's own property drawer, not a sub-heading's; null when it has none. */
fun OrgSection.property(key: String): String? =
    children.asSequence()
        .filterIsInstance<OrgDrawer>()
        .filter { it.name.equals("PROPERTIES", ignoreCase = true) }
        .flatMap { it.properties }
        .firstOrNull { (name, _) -> name.equals(key, ignoreCase = true) }
        ?.second?.trim()

/** This heading's own `:ID:`; a blank one is none. */
val OrgSection.ownId: String? get() = property("ID")?.takeIf { it.isNotEmpty() }

/**
 * A list. [bulletType] is the character a plain list is marked with (`-`, `+`, `*`); an ordered list
 * reports [ordered] instead, since its markers are `1.`/`1)` and differ per item.
 *
 * [indent] is the column the list starts at, which is what distinguishes a nested list from a new
 * one: a more-indented item opens a list that is a child of the item above it.
 */
data class OrgList(
    val bulletType: Char,
    override val children: List<OrgNode>,
    val ordered: Boolean = false,
    val indent: Int = 0,
) : OrgNode()

// Lines that start with #+
data class OrgKeyword(val content: String) : OrgNode()

// A SCHEDULED, DEADLINE or CLOSED timestamp line under a heading
data class OrgPlanning(val planType: String, val timestamp: String) : OrgNode()

// Any raw text paragraph or line content that doesn't fit another construct.
data class OrgRaw(val content: String) : OrgNode()

// TODO state for headings is a free-form string (e.g., TODO, DONE, WAITING) defined by document metadata.
// We intentionally avoid an enum to allow arbitrary states.

// Checkbox list item like: - [ ] Task, - [X] Done or - [-] Some of it
data class OrgCheckboxItem(
    val checked: Boolean,
    val text: String,
    override val children: List<OrgNode> = emptyList(),
    /** `[-]`: some of the boxes under it are ticked and some are not. Never [checked] as well. */
    val partial: Boolean = false,
    /** The `[@3]` that sets this item's number in an ordered list; see [orderedNumbers]. */
    val counter: Int? = null,
) : OrgNode()

/** A plain (non-checkbox) list item, which may own a nested list. */
data class OrgListItem(
    val text: String,
    override val children: List<OrgNode> = emptyList(),
    /** The `[@3]` that sets this item's number in an ordered list; see [orderedNumbers]. */
    val counter: Int? = null,
) : OrgNode()

/**
 * A `#+BEGIN_x … #+END_x` block.
 *
 * [body] is kept as raw text on purpose: the whole point of a block is that its contents are *not*
 * Org. A `* heading` inside a source block is a line of code, and parsing it as a heading would both
 * misrender it and let an element edit splice over the wrong range.
 */
data class OrgBlock(
    val type: String,
    val parameters: String,
    val body: String,
    /** True when no `#+END_` was found: the block runs to the end of its enclosing section. */
    val unterminated: Boolean = false,
) : OrgNode()

/**
 * A `:NAME: … :END:` drawer, such as `:PROPERTIES:`.
 *
 * Like a block, its lines are kept verbatim; [properties] additionally exposes `:key: value` pairs
 * in order, because that is what a drawer is normally read for.
 */
data class OrgDrawer(
    val name: String,
    val lines: List<String>,
    val properties: List<Pair<String, String>> = emptyList(),
    val unterminated: Boolean = false,
) : OrgNode()

/** A `# comment` line (not `#+keyword`). */
data class OrgComment(val text: String) : OrgNode()

/** A `-----` horizontal rule. */
class OrgHorizontalRule : OrgNode() {
    override fun equals(other: Any?) = other is OrgHorizontalRule
    override fun hashCode() = "OrgHorizontalRule".hashCode()
    override fun toString() = "OrgHorizontalRule"
}

/** A `term :: definition` item of a description list. */
data class OrgDescriptionItem(
    val term: String,
    val description: String,
    /** The `[@3]` that sets this item's number in an ordered list; see [orderedNumbers]. */
    val counter: Int? = null,
    /** What follows the definition under the item: further paragraphs, blocks, tables, lists. */
    override val children: List<OrgNode> = emptyList(),
) : OrgNode()

/**
 * A run of fixed-width lines, `: text`, which Org shows verbatim in a monospace font: a quicker
 * example block for a line or two of output. [lines] are the source lines as written.
 */
data class OrgFixedWidth(val lines: List<String>) : OrgNode() {
    /** What the lines show: each without its indent, its colon and the one space after it. */
    val contents: List<String> get() = lines.map { it.trimStart().removePrefix(":").removePrefix(" ") }
}

/**
 * A LaTeX environment, `\begin{name}` … `\end{name}`, kept verbatim: its lines are math or
 * markup for an exporter, and joining them into a paragraph would only scramble them.
 */
data class OrgLatexEnvironment(val name: String, val lines: List<String>) : OrgNode()

/** A `[fn:name] text` definition at the start of a line. */
data class OrgFootnoteDefinition(val label: String, val text: String) : OrgNode()

/** The `[@3]` counter of a list item, or null for an item without one or a node that is no item. */
val OrgNode.listCounter: Int?
    get() = when (this) {
        is OrgListItem -> counter
        is OrgCheckboxItem -> counter
        is OrgDescriptionItem -> counter
        else -> null
    }

/**
 * The number each of this list's items shows, as Org numbers them: by position, from 1, except
 * that an item's `[@n]` counter makes it n and the items after it count on from there. What the
 * source writes before the `.` is not read, which is how Org renumbers a list.
 */
fun OrgList.orderedNumbers(): List<Int> {
    var next = 1
    return children.map { item ->
        val number = item.listCounter ?: next
        next = number + 1
        number
    }
}

// Table model: consecutive lines starting with '|' become a single table. Each line is a row; a line
// starting with "|-" is a horizontal rule row (isRule = true, no cells).
data class OrgTable(val rows: List<OrgTableRow>) : OrgNode() {
    val columnCount: Int get() = rows.maxOfOrNull { it.cells.size } ?: 0
    override val children: List<OrgNode> get() = rows
}

data class OrgTableRow(val cells: List<OrgNode>, val isRule: Boolean = false) : OrgNode() {
    override val children: List<OrgNode> get() = cells
}

/**
 * A heading line split into its parts, exactly as [OrgSection] records them.
 *
 * Public and shared on purpose: anything that has to recognise the heading a parsed [OrgSection]
 * came from — the calendar opening an entry, for one — must agree with the parser about where the
 * TODO keyword and the trailing tags end and the title begins. Two separate notions of "the title"
 * silently fail to match the moment a heading carries a tag.
 */
data class OrgHeading(
    val level: Int,
    /** The heading's TODO keyword, one of those it was read with; null when it has none. */
    val todo: String?,
    val title: String,
    val tags: List<String>,
    /** The `[#A]` or `[#10]` cookie's priority, or null when the heading carries none. */
    val priority: OrgPriority? = null,
)

/**
 * Splits a heading line — with or without its leading stars — into [OrgHeading].
 *
 * The first word is the heading's keyword only when it is one of [keywords], as in Org: the file's
 * `#+TODO:` lines decide, so `* NASA plans` is titled `NASA plans` wherever `NASA` is not declared.
 * Pass the file's keywords ([todoKeywordsOf]), not a guess: a keyword missed here is a word of the
 * title, and setting one would then put a second keyword in front of it.
 */
fun orgHeadingOf(line: String, keywords: OrgTodoConfig): OrgHeading {
    // Trimmed only at the start: the space that makes `** ` a heading may be all that follows it.
    val unindented = line.trimStart()
    val stars = OrgSyntax.heading.find(unindented)?.groupValues?.get(1)
    val level = stars?.length ?: 1
    var rest = stars?.let { unindented.substring(it.length).trim() }
    val word = rest?.let { headingKeywordRegex.find(it) }
    val todo = word?.groupValues?.get(1)?.takeIf { it in keywords.todo || it in keywords.done }
    if (todo != null) rest = rest!!.substring(word.value.length)
    // A heading that is only its keyword, `* TODO`, or only its stars has an empty title, as in Org.
    var title = rest ?: line.trim()
    // Trailing :tag1:tag2: are stripped from the title and kept separately
    val tags = OrgSyntax.tags.find(title)?.let { tm ->
        title = title.substring(0, tm.range.first).trimEnd()
        tm.groupValues[1].trim(':').split(':')
    } ?: emptyList()
    // A leading [#A] cookie is a priority, not part of the title.
    val priority = OrgSyntax.priority.find(title)?.let { pm ->
        title = title.substring(pm.value.length).trimStart()
        OrgPriority.of(pm.groupValues[1])
    }
    return OrgHeading(level = level, todo = todo, title = title, tags = tags, priority = priority)
}

private val headingKeywordRegex = Regex("^(${OrgSyntax.TODO_WORD})(?:\\s+|$)")

// True when the line is part of a table (starts with '|' after optional indentation).
fun String.isOrgTableLine(): Boolean = trimStart().startsWith("|")

// True when the line is a table horizontal rule such as |---+---| .
fun String.isOrgTableRuleLine(): Boolean = trimStart().startsWith("|-")

// Parses consecutive table lines (all starting with '|') into an OrgTable.
fun parseOrgTable(lines: List<String>): OrgTable {
    val rows = lines.map { l ->
        if (l.isOrgTableRuleLine()) {
            OrgTableRow(emptyList(), isRule = true)
        } else {
            var body = l.trim().removePrefix("|")
            if (body.endsWith("|")) body = body.dropLast(1)
            val cells = body.split('|').map { OrgRaw(it.trim()) as OrgNode }
            OrgTableRow(cells)
        }
    }
    return OrgTable(rows)
}

/**
 * Parses this text into an [OrgDocument].
 *
 * Its headings are read with the keywords it declares with `#+TODO:`, or with [fallback] when it
 * declares none: what the editor-wide setting would hold, which for the app is what the other notes
 * declare (Org's own `TODO` and `DONE`). A slice of a note, which never holds the note's `#+TODO:` lines,
 * must be given the note's keywords here.
 */
fun String.toOrgDocument(fallback: OrgTodoConfig = DEFAULT_TODO_KEYWORDS): OrgDocument {
    val source = this
    val keywords = todoKeywordsOf(source, fallback)
    val lines = source.split('\n')
    val children = mutableListOf<OrgNode>()
    val metadata = mutableListOf<String>()

    // Offset of each line's first character, so a span of lines converts to a span of characters.
    val lineStarts = IntArray(lines.size)
    var offset = 0
    for (index in lines.indices) {
        lineStarts[index] = offset
        offset += lines[index].length + 1
    }

    /** Source range covering lines `[first, lastExclusive)`, including their line breaks. */
    fun span(first: Int, lastExclusive: Int): IntRange {
        if (first >= lines.size) return IntRange.EMPTY
        val from = lineStarts[first]
        val to = if (lastExclusive >= lines.size) source.length else lineStarts[lastExclusive]
        return from until to
    }

    fun <T : OrgNode> T.spanning(first: Int, lastExclusive: Int): T {
        range = span(first, lastExclusive)
        return this
    }

    var i = 0
    var pendingParagraph = StringBuilder()
    var paragraphStart = 0

    // Declared before the section stack exists, so it asks for the sink through a holder that is
    // filled in below rather than capturing `children` directly.
    lateinit var sinkOf: () -> MutableList<OrgNode>

    fun flushParagraph() {
        if (pendingParagraph.isNotEmpty()) {
            sinkOf().add(OrgRaw(pendingParagraph.toString().trimEnd()).spanning(paragraphStart, i))
            pendingParagraph = StringBuilder()
        }
    }

    fun parseTable(start: Int, limit: Int = lines.size): Pair<OrgNode, Int> {
        var idx = start
        while (idx < limit && lines[idx].isOrgTableLine()) idx++
        val table = parseOrgTable(lines.subList(start, idx))
        // Cells keep an empty range: locating them needs intra-line offsets, and the grid editor
        // replaces whole tables.
        table.rows.forEachIndexed { row, tableRow -> tableRow.spanning(start + row, start + row + 1) }
        return table.spanning(start, idx) to idx
    }

    /**
     * A `#+BEGIN_x … #+END_x` block, consumed whole.
     *
     * Everything between the delimiters is kept as text and never looked at again, which is the
     * point: a `* heading` or a `- item` inside a source block is code, not Org.
     */
    fun parseBlock(start: Int, begin: MatchResult): Pair<OrgNode, Int> {
        val type = begin.groupValues[1]
        val parameters = begin.groupValues[2]
        var idx = start + 1
        val body = StringBuilder()
        var terminated = false
        while (idx < lines.size) {
            if (OrgSyntax.closesBlock(lines[idx], type)) {
                terminated = true
                idx++
                break
            }
            body.appendLine(lines[idx])
            idx++
        }
        val node = OrgBlock(
            type = type,
            parameters = parameters,
            body = body.toString(),
            unterminated = !terminated,
        )
        return node.spanning(start, idx) to idx
    }

    /** A `:NAME: … :END:` drawer, consumed whole for the same reason as a block. */
    fun parseDrawer(start: Int): Pair<OrgNode, Int> {
        val name = lines[start].trim().trim(':')
        var idx = start + 1
        val body = mutableListOf<String>()
        val properties = mutableListOf<Pair<String, String>>()
        var terminated = false
        while (idx < lines.size) {
            if (OrgSyntax.drawerEnd.matches(lines[idx])) {
                terminated = true
                idx++
                break
            }
            // A heading ends an unterminated drawer rather than swallowing the rest of the file.
            if (OrgSyntax.heading.containsMatchIn(lines[idx])) break
            body.add(lines[idx])
            OrgSyntax.property.find(lines[idx])?.let { m ->
                properties.add(m.groupValues[1].trim().trim(':') to m.groupValues[2].trim())
            }
            idx++
        }
        val node = OrgDrawer(
            name = name,
            lines = body.toList(),
            properties = properties.toList(),
            unterminated = !terminated,
        )
        return node.spanning(start, idx) to idx
    }

    /**
     * True when the main loop below would take [line] as part of a paragraph: it starts no other
     * construct. The two have to agree, or a line folded into an item would be a heading or a block.
     */
    fun isPlainLine(line: String): Boolean =
        line.isNotBlank() &&
            !OrgSyntax.blockBegin.containsMatchIn(line) &&
            !OrgSyntax.keyword.containsMatchIn(line) &&
            !OrgSyntax.comment.containsMatchIn(line) &&
            !OrgSyntax.drawerBegin.matches(line) &&
            !OrgSyntax.horizontalRule.matches(line) &&
            !OrgSyntax.fixedWidth.containsMatchIn(line) &&
            !OrgSyntax.footnoteDefinition.containsMatchIn(line) &&
            !line.isOrgTableLine() &&
            !OrgSyntax.heading.containsMatchIn(line) &&
            !OrgSyntax.planning.containsMatchIn(line) &&
            !OrgSyntax.listItem.containsMatchIn(line)

    /**
     * The lines from [start] that carry on the text above them, trimmed, and the index after them.
     *
     * Filling (`M-q`, `auto-fill-mode`) breaks a long item or footnote over several lines; Org reads
     * them as one, and so does this. [minIndent] is the indentation a line needs to belong: an item's
     * text continues only on lines indented past its bullet.
     */
    fun continuationOf(start: Int, minIndent: Int): Pair<List<String>, Int> {
        var idx = start
        val more = mutableListOf<String>()
        while (idx < lines.size) {
            val line = lines[idx].trimEnd()
            if (!isPlainLine(line) || OrgSyntax.heading.containsMatchIn(lines[idx]) ||
                line.length - line.trimStart().length < minIndent
            ) {
                break
            }
            more.add(line.trim())
            idx++
        }
        return more to idx
    }

    /**
     * [parseList], which an item's contents use for the lists nested in them, before it is declared:
     * the two call each other, so this is filled in once [parseList] exists.
     */
    lateinit var nestedList: (start: Int) -> Pair<OrgNode, Int>

    /**
     * Where the contents of an item end, from [from], the line after its first paragraph, for an
     * item whose bullet is indented [indent]: as Org ends one, at the first line indented no further
     * than the bullet — a heading among them — or at two blank lines; a single blank line between
     * its paragraphs keeps it going. A block's own lines count as the item's however they are
     * indented, as the lines of a greater element do in Org. Trailing blank lines are left after it.
     */
    fun itemBodyEnd(from: Int, indent: Int): Int {
        var at = from
        var end = from
        var blanks = 0
        while (at < lines.size) {
            val line = lines[at]
            if (line.isBlank()) {
                if (++blanks >= 2) break
                at++
                continue
            }
            if (OrgSyntax.heading.containsMatchIn(line)) break
            if (line.length - line.trimStart().length <= indent) break
            blanks = 0
            val begin = OrgSyntax.blockBegin.find(line)
            at = if (begin != null) parseBlock(at, begin).second else at + 1
            end = at
        }
        return end
    }

    /**
     * The contents of an item from [from] to [to], as [itemBodyEnd] found them: paragraphs, blocks,
     * tables, nested lists and the rest, each read as it would be outside a list. A paragraph's
     * lines are kept without the indentation that put them under the item.
     */
    fun parseItemBody(from: Int, to: Int): List<OrgNode> {
        val nodes = mutableListOf<OrgNode>()
        val paragraph = mutableListOf<String>()
        var paragraphFrom = from
        fun flush(end: Int) {
            if (paragraph.isEmpty()) return
            nodes += OrgRaw(paragraph.joinToString("\n")).spanning(paragraphFrom, end)
            paragraph.clear()
        }
        var at = from
        while (at < to) {
            val line = lines[at].trimEnd()
            val begin = OrgSyntax.blockBegin.find(line)
            val next: Int = when {
                line.isBlank() -> { flush(at); at + 1 }
                begin != null -> { flush(at); parseBlock(at, begin).let { (block, after) -> nodes += block; after } }
                OrgSyntax.listItem.containsMatchIn(line) -> { flush(at); nestedList(at).let { (list, after) -> nodes += list; after } }
                line.isOrgTableLine() -> { flush(at); parseTable(at, to).let { (table, after) -> nodes += table; after } }
                OrgSyntax.fixedWidth.containsMatchIn(line) -> {
                    flush(at)
                    var after = at + 1
                    while (after < to && OrgSyntax.fixedWidth.containsMatchIn(lines[after])) after++
                    nodes += OrgFixedWidth(lines.subList(at, after).map { it.trimEnd() }).spanning(at, after)
                    after
                }
                OrgSyntax.drawerBegin.matches(line) && !OrgSyntax.drawerEnd.matches(line) -> {
                    flush(at)
                    parseDrawer(at).let { (drawer, after) -> nodes += drawer; after }
                }
                OrgSyntax.keyword.containsMatchIn(line) -> { flush(at); nodes += OrgKeyword(line.trim()).spanning(at, at + 1); at + 1 }
                OrgSyntax.comment.containsMatchIn(line) -> { flush(at); nodes += OrgComment(line.trim()).spanning(at, at + 1); at + 1 }
                else -> {
                    if (paragraph.isEmpty()) paragraphFrom = at
                    paragraph += line.trim()
                    at + 1
                }
            }
            at = next
        }
        flush(to)
        return nodes
    }

    /**
     * A list starting at [start], together with any lists nested under its items.
     *
     * Indentation is what separates a nested list from a new one: an item indented further than the
     * list it follows belongs to the item above it.
     */
    fun parseList(start: Int): Pair<OrgNode, Int> {
        val first = OrgSyntax.listItem.find(lines[start])!!
        val indent = first.groupValues[1].length
        val firstMarker = first.groupValues[2]
        val ordered = firstMarker.first().isDigit()
        val bullet = if (ordered) firstMarker.last() else firstMarker.first()

        /** True when [line] is an item of this list rather than of a nested or an enclosing one. */
        fun continuesList(line: String): Boolean {
            val m = OrgSyntax.listItem.find(line) ?: return false
            val marker = m.groupValues[2]
            // A shallower item belongs to an enclosing list; a deeper one is handled below, as a
            // child of the item it follows.
            if (m.groupValues[1].length != indent) return false
            // A different marker starts a different list, as it does in Org. This is also what keeps
            // a list from eating the headings after it: the grammar matches `*` as a bullet, so
            // without this rule a `- ` list swallowed every following `* heading` as an item.
            if (ordered != marker.first().isDigit()) return false
            return ordered || marker.first() == bullet
        }

        /**
         * The line after [at] when [at] is one blank line followed by a line that [belongs], else
         * null: Org ends a list only at two blank lines, so a single one between items keeps it going.
         */
        fun overOneBlank(at: Int, belongs: (String) -> Boolean): Int? =
            (at + 1).takeIf { at < lines.size && lines[at].isBlank() && it < lines.size && belongs(lines[it]) }

        val items = mutableListOf<OrgNode>()
        var idx = start
        while (idx < lines.size) {
            if (!continuesList(lines[idx])) {
                // The blank line stays outside both items, in the list: the item before keeps its
                // own lines, so nothing that edits it reaches into the gap.
                idx = overOneBlank(idx, ::continuesList) ?: break
            }
            val m = OrgSyntax.listItem.find(lines[idx])!!

            val itemStart = idx
            val checkbox = m.groupValues[4]
            val counter = OrgSyntax.listCounter.find(m.value)?.groupValues?.get(1)?.toIntOrNull()

            // The checkbox group ends before its trailing space (the regex only looks ahead), so
            // trim it rather than carrying it into the item's text.
            val firstLine = lines[idx].substring(m.value.length).trimStart()
            idx++
            val (more, afterText) = continuationOf(idx, minIndent = indent + 1)
            idx = afterText
            val text = (listOf(firstLine) + more).joinToString("\n")

            // The rest of the item: further paragraphs, blocks, tables and lists under it, as Org
            // reads an item's contents — whatever is indented past its bullet.
            val bodyEnd = itemBodyEnd(idx, indent)
            val nested = parseItemBody(idx, bodyEnd)
            idx = bodyEnd

            val description = OrgSyntax.descriptionTerm.find(text)
            val item: OrgNode = when {
                checkbox.isNotEmpty() -> OrgCheckboxItem(
                    checkbox.contains('x', ignoreCase = true), text, nested.toList(), partial = '-' in checkbox, counter,
                )
                description != null -> OrgDescriptionItem(
                    term = description.groupValues[2].trim(),
                    description = text.substring(description.value.length).trim(),
                    counter = counter,
                    children = nested.toList(),
                )
                else -> OrgListItem(text, nested.toList(), counter)
            }
            items.add(item.spanning(itemStart, idx))
        }
        return OrgList(bullet, items.toList(), ordered, indent).spanning(start, idx) to idx
    }
    nestedList = ::parseList

    /**
     * An open heading: everything parsed after it belongs to it until a heading of the same or a
     * shallower level closes it. Built as a mutable frame because a section's children and its end
     * are only known once the following heading — or the end of the file — is reached.
     */
    class OpenSection(val startLine: Int, val heading: OrgHeading) {
        val children = mutableListOf<OrgNode>()
    }

    val openSections = mutableListOf<OpenSection>()

    /** Where a newly parsed block belongs: the innermost open heading, else the document. */
    fun sink(): MutableList<OrgNode> = openSections.lastOrNull()?.children ?: children
    sinkOf = ::sink

    /** Closes the innermost open heading at [endLineExclusive] and hands it to its parent. */
    fun closeSection(endLineExclusive: Int) {
        // removeAt(lastIndex), not removeLast(): Kotlin compiles removeLast() to the JDK 21
        // SequencedCollection method, which Android's java.util.List does not have and which is not
        // desugared, so it compiles everywhere and then throws NoSuchMethodError on device.
        val open = openSections.removeAt(openSections.lastIndex)
        val section = OrgSection(
            title = open.heading.title,
            todo = open.heading.todo,
            level = open.heading.level,
            tags = open.heading.tags,
            children = open.children.toList(),
            priority = open.heading.priority,
        ).spanning(open.startLine, endLineExclusive)
        sink().add(section)
    }

    /** Closes every open heading at [level] or deeper, so the next one nests under the right parent. */
    fun closeSectionsDownTo(level: Int, endLineExclusive: Int) {
        while (openSections.isNotEmpty() && openSections.last().heading.level >= level) {
            closeSection(endLineExclusive)
        }
    }

    mainLoop@ while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        if (line.isBlank()) {
            flushParagraph()
            i++
            continue
        }
        val blockBegin = OrgSyntax.blockBegin.find(line)
        if (blockBegin != null) {
            flushParagraph()
            val (block, next) = parseBlock(i, blockBegin)
            sink().add(block)
            i = next
            continue
        }
        if (OrgSyntax.keyword.containsMatchIn(line)) {
            flushParagraph()
            val keyword = line.trim()
            metadata.add(keyword)
            sink().add(OrgKeyword(keyword).spanning(i, i + 1))
            i++
            continue
        }
        // A `# comment` line, as opposed to a `#+keyword`.
        if (OrgSyntax.comment.containsMatchIn(line)) {
            flushParagraph()
            sink().add(OrgComment(line).spanning(i, i + 1))
            i++
            continue
        }
        if (OrgSyntax.drawerBegin.matches(line) && !OrgSyntax.drawerEnd.matches(line)) {
            flushParagraph()
            val (drawer, next) = parseDrawer(i)
            sink().add(drawer)
            i = next
            continue
        }
        // A LaTeX environment, when it is closed before the next heading; Org reads an unclosed
        // `\begin{…}` as text, and so does this.
        val latexEnd = OrgSyntax.latexBegin.find(line)?.let { begin ->
            val end = OrgSyntax.latexEnd(begin.groupValues[1])
            (i until lines.size).asSequence()
                .takeWhile { it == i || !OrgSyntax.heading.containsMatchIn(lines[it]) }
                .firstOrNull { end.containsMatchIn(lines[it]) }
                ?.let { begin.groupValues[1] to it + 1 }
        }
        if (latexEnd != null) {
            flushParagraph()
            val (name, next) = latexEnd
            sink().add(OrgLatexEnvironment(name, lines.subList(i, next).map { it.trimEnd() }).spanning(i, next))
            i = next
            continue
        }
        if (OrgSyntax.fixedWidth.containsMatchIn(line)) {
            flushParagraph()
            var next = i + 1
            while (next < lines.size && OrgSyntax.fixedWidth.containsMatchIn(lines[next])) next++
            sink().add(OrgFixedWidth(lines.subList(i, next).map { it.trimEnd() }).spanning(i, next))
            i = next
            continue
        }
        if (OrgSyntax.horizontalRule.matches(line)) {
            flushParagraph()
            sink().add(OrgHorizontalRule().spanning(i, i + 1))
            i++
            continue
        }
        OrgSyntax.footnoteDefinition.find(line)?.let { fn ->
            flushParagraph()
            val (more, next) = continuationOf(i + 1, minIndent = 0)
            val node = OrgFootnoteDefinition(fn.groupValues[1], (listOf(fn.groupValues[2].trim()) + more).joinToString("\n"))
            sink().add(node.spanning(i, next))
            i = next
            continue@mainLoop
        }
        if (line.trimStart().startsWith("|")) {
            flushParagraph()
            val (table, next) = parseTable(i)
            sink().add(table)
            i = next
            continue
        }
        // Headings (stars, then a space) open sections before `*` lists are tried. A line that only
        // starts with a star, as `*bold* text` does, is a paragraph. Read before the trim above: the
        // trailing space is what makes `* `, a heading with no title yet, a heading at all.
        if (OrgSyntax.heading.containsMatchIn(raw)) {
            flushParagraph()
            val heading = orgHeadingOf(raw, keywords)
            // A heading ends every open heading at its level or deeper; what remains is its parent.
            closeSectionsDownTo(heading.level, i)
            openSections.add(OpenSection(i, heading))
            i++
            continue
        }
        // Planning lines, from the shared grammar, which is what also taught this CLOSED:.
        run {
            val pm = OrgSyntax.planning.find(line)
            if (pm != null) {
                flushParagraph()
                val type = pm.groupValues[1]
                val ts = line.substring(pm.value.length).trim()
                sink().add(OrgPlanning(planType = type, timestamp = ts).spanning(i, i + 1))
                i++
                continue@mainLoop
            }
        }
        // Lists come from the shared grammar now, which also covers `1.`/`1)` and nesting. A `*` at
        // column 0 is no bullet: stars and a space are a heading, above, and anything else is text.
        if (OrgSyntax.listItem.containsMatchIn(line)) {
            flushParagraph()
            val (listNode, next) = parseList(i)
            sink().add(listNode)
            i = next
            continue
        }
        // default: part of a paragraph
        if (pendingParagraph.isEmpty()) paragraphStart = i
        if (pendingParagraph.isNotEmpty()) pendingParagraph.append('\n')
        pendingParagraph.append(line)
        i++
    }
    flushParagraph()
    // Whatever is still open runs to the end of the file.
    closeSectionsDownTo(1, lines.size)

    val document = OrgDocument(children, metadata)
    document.text = source
    document.todoKeywords = keywords
    document.todoFallback = fallback
    document.range = if (source.isEmpty()) IntRange.EMPTY else 0 until source.length
    return document
}

private fun OrgNode.cellText(): String = (this as? OrgRaw)?.content ?: this.toOrgString()

/**
 * The table as Org text, aligned as Emacs aligns it (`org-table-align`), so that a table saved here
 * comes out as Emacs would have left it, byte for byte, and editing it on either side does not
 * re-pad it on the other.
 *
 * Every column is as wide as its widest cell *as displayed* — a link shows only its description, a
 * CJK character or an emoji takes two columns — and at least one. A whole column is aligned the same
 * way: to the right when at least half of its cells that are not empty look like numbers, unless a
 * `<l>`, `<c>` or `<r>` cookie in it says otherwise.
 */
fun OrgTable.toAlignedLines(): List<String> {
    val cells = rows.filterNot { it.isRule }.map { row -> row.cells.map { it.cellText().trim() } }
    // A table of rules only keeps a single column: the parser does not keep their `+`s.
    if (cells.isEmpty()) return rows.map { "|---|" }
    val columns = cells.maxOf { it.size }
    val widths = IntArray(columns) { 1 }
    val alignments = Array(columns) { column ->
        var cookie: Char? = null
        var numbers = 0
        var nonEmpty = 0
        for (row in cells) {
            val cell = row.getOrElse(column) { "" }
            widths[column] = maxOf(widths[column], orgDisplayWidth(cell))
            when {
                cookie != null || cell.isEmpty() -> {}
                // A width-only cookie, `<10>`, says nothing about alignment, but it is no text either.
                alignmentCookie.matches(cell) -> if (cell[1] in "lrc") cookie = cell[1]
                else -> {
                    nonEmpty++
                    if (orgTableNumber.matches(cell)) numbers++
                }
            }
        }
        cookie ?: if (numbers >= 0.5 * nonEmpty) 'r' else 'l'
    }
    val rule = widths.joinToString(separator = "+", prefix = "|", postfix = "|") { "-".repeat(it + 2) }
    var data = 0
    return rows.map { row ->
        if (row.isRule) return@map rule
        val fields = cells[data++]
        (0 until columns).joinToString(separator = "|", prefix = "|", postfix = "|") { column ->
            val field = fields.getOrElse(column) { "" }
            val spaces = widths[column] - orgDisplayWidth(field)
            val before = when (alignments[column]) {
                'r' -> spaces
                'c' -> spaces / 2
                else -> 0
            }
            " " + " ".repeat(before) + field + " ".repeat(spaces - before) + " "
        }
    }
}

/** A column cookie: `<l>`, `<c>`, `<r>`, with an optional width (`<l10>`), or a width alone (`<10>`). */
private val alignmentCookie = Regex("""<(?:[lrc][0-9]*|[0-9]+)>""")

// `org-table-number-regexp`, as Emacs matches it: without regard to case.
private val orgTableNumber = Regex(
    """[<>]?[-+^.0-9]*[0-9][-+^.0-9eEdDx()%:]*|[<>]?[-+]?0[xX][0-9a-fA-F.]+|[<>]?[-+]?[0-9]+#[0-9a-zA-Z.]+|nan|[-+u]?inf""",
    RegexOption.IGNORE_CASE,
)

// Emacs right-aligns cells that look like numbers.
private val numericCellRegex = Regex("^[-+]?(\\d+([.,]\\d*)?|[.,]\\d+)([eE][-+]?\\d+)?%?$")
fun String.isNumeric(): Boolean = isNotEmpty() && numericCellRegex.matches(this)

/** The TODO keywords a file declares: those still to do, and those that mark an entry done. */
data class OrgTodoConfig(val todo: Set<String>, val done: Set<String>)

/** What Org assumes when a document names no workflow of its own. */
val DEFAULT_TODO_KEYWORDS = OrgTodoConfig(todo = setOf("TODO"), done = setOf("DONE"))

/**
 * Each `#+TODO:` line among [lines] (see [OrgSyntax.todoKeywordsLine]) as the sequence it declares,
 * in order. Other lines are skipped.
 *
 * Read as Org reads `org-todo-keywords`: the words before `|` are still to do and the words after
 * it are done, and a line without `|` makes only its last word done, so `#+TODO: TODO NEXT DONE`
 * is `TODO NEXT | DONE`. A word's `(t)` or `(w@/!)` settings are dropped. A word no heading could
 * start with (see [OrgSyntax.TODO_WORD]) is skipped, as it could never mark one.
 */
fun todoSequencesOf(lines: List<String>): List<OrgTodoConfig> = lines.mapNotNull { line ->
    val words = OrgSyntax.todoKeywordsLine.find(line)?.groupValues?.get(1)
        ?.split(' ', '\t')?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    val bar = words.indexOf("|")
    val todo = if (bar >= 0) words.subList(0, bar) else words.dropLast(1)
    val done = if (bar >= 0) words.subList(bar + 1, words.size) else words.takeLast(1)
    fun keywords(side: List<String>) =
        side.mapNotNullTo(linkedSetOf()) { OrgSyntax.todoKeywordDeclaration.matchEntire(it)?.groupValues?.get(1) }
    OrgTodoConfig(keywords(todo), keywords(done))
}

/** Every keyword the `#+TODO:` lines among [metadata] declare, all sequences together. */
fun parseTodoConfigFrom(metadata: List<String>): OrgTodoConfig {
    val todo = linkedSetOf<String>()
    val done = linkedSetOf<String>()
    for (sequence in todoSequencesOf(metadata)) {
        todo += sequence.todo
        done += sequence.done
    }
    return OrgTodoConfig(todo, done)
}

/** The `#+TODO:` lines of raw Org text, wherever they are. */
private fun todoLinesOf(text: String): List<String> =
    text.lineSequence().filter { OrgSyntax.todoKeywordsLine.containsMatchIn(it) }.toList()

/**
 * The keywords [text] declares with `#+TODO:`, or [fallback] when it declares none.
 *
 * A declaration replaces the fallback rather than adding to it, as in Org: a file that says
 * `#+TODO: EVENT | FINISHED` has no TODO keyword.
 */
fun todoKeywordsOf(text: String, fallback: OrgTodoConfig): OrgTodoConfig =
    parseTodoConfigFrom(todoLinesOf(text)).takeUnless { it.todo.isEmpty() && it.done.isEmpty() } ?: fallback

/**
 * What [text] calls itself: its first `#+TITLE:` with a value, or null. A line inside a block is the
 * block's text and names nothing.
 *
 * The one way a note's title is read, so that the link picker, the links completion writes and
 * anything else that names a note all name it alike.
 */
fun orgNoteTitle(text: String): String? {
    if (!text.contains("#+TITLE:", ignoreCase = true)) return null
    var block: String? = null
    for (line in text.lineSequence()) {
        val open = block
        if (open != null) {
            if (OrgSyntax.closesBlock(line, open)) block = null
            continue
        }
        block = OrgSyntax.blockBegin.find(line)?.groupValues?.get(1)
        OrgSyntax.titleKeyword.matchEntire(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return null
}

/**
 * This document as it reads when its text declares no keywords and the fallback is [fallback]:
 * itself when that changes nothing, because it declares its own or was read with the same.
 */
fun OrgDocument.readWith(fallback: OrgTodoConfig): OrgDocument =
    if (todoFallback == fallback || todoKeywords != todoFallback) this else text.toOrgDocument(fallback)

// --- Serialization back to org-mode text ---
fun OrgNode.toOrgString(): String = when (this) {
    is OrgDocument -> buildString {
        this@toOrgString.children.forEachIndexed { idx, node ->
            if (idx > 0) append('\n')
            append(node.toOrgString())
        }
    }
    is OrgKeyword -> this.content
    is OrgRaw -> this.content
    is OrgComment -> text
    is OrgHorizontalRule -> "-----"
    is OrgFixedWidth -> lines.joinToString("\n")
    is OrgLatexEnvironment -> lines.joinToString("\n")
    is OrgDescriptionItem -> "- $term :: " + description.indentedBy(2)
    is OrgFootnoteDefinition -> "[fn:$label] $text"
    is OrgBlock -> buildString {
        append("#+BEGIN_").append(type)
        if (parameters.isNotBlank()) append(parameters)
        if (body.isNotEmpty()) { appendLine(); append(body.trimEnd('\n')) }
        if (!unterminated) { appendLine(); append("#+END_").append(type) }
    }
    is OrgDrawer -> buildString {
        append(':').append(name).append(':')
        lines.forEach { appendLine(); append(it) }
        if (!unterminated) { appendLine(); append(":END:") }
    }
    is OrgCheckboxItem -> buildString {
        append("- [").append(if (checked) "X" else if (partial) "-" else " ").append("] ").append(text.indentedBy(2))
        children.forEach { appendLine(); append(it.toOrgString()) }
    }
    is OrgListItem -> buildString {
        append("- ").append(text.indentedBy(2))
        children.forEach { appendLine(); append(it.toOrgString()) }
    }
    is OrgList -> children.zip(orderedNumbers()).joinToString("\n") { (child, number) ->
        val pad = " ".repeat(indent)
        val marker = if (ordered) "$number$bulletType" else "$bulletType"
        val counter = child.listCounter?.let { "[@$it] " }.orEmpty()
        val body = counter + when (child) {
            is OrgCheckboxItem -> "[" + (if (child.checked) "X" else if (child.partial) "-" else " ") + "] " + child.text
            is OrgListItem -> child.text
            is OrgDescriptionItem -> "${child.term} :: ${child.description}"
            is OrgRaw -> child.content
            else -> child.toOrgString()
        }
        val nested = child.children.joinToString("") {
            "\n" + if (it is OrgList) it.toOrgString() else it.toOrgString().prependIndent(" ".repeat(pad.length + marker.length + 1))
        }
        // Continuation lines go back under the text, past the bullet, or they would end the list.
        "$pad$marker ${body.indentedBy(pad.length + marker.length + 1)}$nested"
    }
    is OrgSection -> buildString {
        append("*".repeat(level.coerceAtLeast(1))).append(' ')
        if (todo != null) append(todo).append(' ')
        if (priority != null) append("[#").append(priority.text).append("] ")
        append(title)
        if (tags.isNotEmpty()) append(" :").append(tags.joinToString(":")).append(':')
        // The body and the sub-headings are children now, so printing a section prints its subtree.
        for (child in children) {
            appendLine()
            append(child.toOrgString())
        }
    }.trimEnd()
    is OrgTable -> toAlignedLines().joinToString("\n")
    is OrgTableRow -> if (isRule) "|-|" else "| " + cells.joinToString(" | ") { it.cellText() } + " |"
    is OrgPlanning -> if (planType.isBlank()) timestamp else "$planType: $timestamp"
}

/** Every line of a multi-line item's text after the first, indented by [columns]. */
private fun String.indentedBy(columns: Int): String = replace("\n", "\n" + " ".repeat(columns))

/**
 * [text] — a paragraph, or an item's or footnote's lines — as Org displays it: one run of text, its
 * line breaks read as spaces, so it wraps to whatever width shows it rather than to the
 * `fill-column` it was filled to.
 *
 * The exception is a line ending in `\\`, Org's way of asking for a line break: that one stays a
 * break, and the `\\` is not shown.
 */
fun joinOrgLines(text: String): String {
    val lines = text.split('\n')
    return buildString {
        lines.forEachIndexed { index, raw ->
            val line = raw.trim()
            val forced = forcedLineBreak.find(line)
            append(if (forced != null) line.substring(0, forced.range.first).trimEnd() else line)
            if (index < lines.lastIndex) append(if (forced != null) '\n' else ' ')
        }
    }
}

private val forcedLineBreak = Regex("\\\\\\\\\\s*$")
