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
    val priority: Char? = null,
) : OrgNode()

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

// Checkbox list item like: - [ ] Task or - [X] Done
// Only supports simple on/off state; nested states are out of scope for now.
data class OrgCheckboxItem(
    val checked: Boolean,
    val text: String,
    override val children: List<OrgNode> = emptyList(),
) : OrgNode()

/** A plain (non-checkbox) list item, which may own a nested list. */
data class OrgListItem(
    val text: String,
    override val children: List<OrgNode> = emptyList(),
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
data class OrgDescriptionItem(val term: String, val description: String) : OrgNode()

/** A `[fn:name] text` definition at the start of a line. */
data class OrgFootnoteDefinition(val label: String, val text: String) : OrgNode()

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
    /** The TODO-like keyword, or `""` when the heading has none. */
    val todo: String?,
    val title: String,
    val tags: List<String>,
    /** The `[#A]` cookie's letter, or null when the heading carries none. */
    val priority: Char? = null,
)

/** Splits a heading line — with or without its leading stars — into [OrgHeading]. */
fun orgHeadingOf(line: String): OrgHeading {
    val trimmed = line.trim()
    val m = headingLineRegex.find(trimmed)
    val level = m?.groupValues?.getOrNull(1)?.length?.coerceAtLeast(1) ?: 1
    val todo = m?.groupValues?.getOrNull(2)
    var title = m?.groupValues?.getOrNull(3)?.ifBlank { null } ?: trimmed
    // Trailing :tag1:tag2: are stripped from the title and kept separately
    val tags = headingTagsRegex.find(title)?.let { tm ->
        title = title.substring(0, tm.range.first).trimEnd()
        tm.groupValues[1].split(':')
    } ?: emptyList()
    // A leading [#A] cookie is a priority, not part of the title.
    val priority = OrgSyntax.priority.find(title)?.let { pm ->
        title = title.substring(pm.value.length).trimStart()
        pm.value[2]
    }
    return OrgHeading(level = level, todo = todo, title = title, tags = tags, priority = priority)
}

private val headingLineRegex = Regex("^(\\*+)\\s+(?:([A-Z][A-Z0-9_-]*)\\s+)?(.*)")
private val headingTagsRegex = Regex("\\s+:([\\p{L}\\p{N}_@#%]+(?::[\\p{L}\\p{N}_@#%]+)*):\\s*$")

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

// Parse a String into a very small subset of Org-mode constructs supported by the data model above.
fun String.toOrgDocument(): OrgDocument {
    val source = this
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

    fun parseTable(start: Int): Pair<OrgNode, Int> {
        var idx = start
        while (idx < lines.size && lines[idx].isOrgTableLine()) idx++
        val table = parseOrgTable(lines.subList(start, idx))
        // Cells keep an empty range: locating them needs intra-line offsets, which waits for the
        // real table grammar in T5.
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
            val end = OrgSyntax.blockEnd.find(lines[idx])
            // An empty `#+END_` closes any block; a named one has to match.
            if (end != null && (end.groupValues[1].isEmpty() || end.groupValues[1].equals(type, ignoreCase = true))) {
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

        val items = mutableListOf<OrgNode>()
        var idx = start
        while (idx < lines.size) {
            val m = OrgSyntax.listItem.find(lines[idx]) ?: break
            val itemIndent = m.groupValues[1].length
            val marker = m.groupValues[2]
            // A shallower item belongs to an enclosing list; a deeper one is handled below, as a
            // child of the item it follows.
            if (itemIndent != indent) break
            // A different marker starts a different list, as it does in Org. This is also what keeps
            // a list from eating the headings after it: the grammar matches `*` as a bullet, so
            // without this rule a `- ` list swallowed every following `* heading` as an item.
            if (ordered != marker.first().isDigit()) break
            if (!ordered && marker.first() != bullet) break

            val itemStart = idx
            val checkbox = m.groupValues[4]

            // The checkbox group ends before its trailing space (the regex only looks ahead), so
            // trim it rather than carrying it into the item's text.
            val text = lines[idx].substring(m.value.length).trimStart()
            idx++

            // Continuation: a deeper list directly under this item is nested inside it.
            val nested = mutableListOf<OrgNode>()
            val deeper = if (idx < lines.size) OrgSyntax.listItem.find(lines[idx]) else null
            if (deeper != null && deeper.groupValues[1].length > indent) {
                val (sub, next) = parseList(idx)
                nested.add(sub)
                idx = next
            }

            val description = OrgSyntax.descriptionTerm.find(text)
            val item: OrgNode = when {
                checkbox.isNotEmpty() ->
                    OrgCheckboxItem(checkbox.contains('x', ignoreCase = true), text, nested.toList())
                description != null -> OrgDescriptionItem(
                    term = description.groupValues[2].trim(),
                    description = text.substring(description.value.length).trim(),
                ).also { if (nested.isNotEmpty()) Unit }
                else -> OrgListItem(text, nested.toList())
            }
            items.add(item.spanning(itemStart, idx))
        }
        return OrgList(bullet, items.toList(), ordered, indent).spanning(start, idx) to idx
    }

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
        if (line.startsWith("#+")) {
            flushParagraph()
            metadata.add(line)
            sink().add(OrgKeyword(line).spanning(i, i + 1))
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
        if (OrgSyntax.horizontalRule.matches(line)) {
            flushParagraph()
            sink().add(OrgHorizontalRule().spanning(i, i + 1))
            i++
            continue
        }
        OrgSyntax.footnoteDefinition.find(line)?.let { fn ->
            flushParagraph()
            val node = OrgFootnoteDefinition(fn.groupValues[1], fn.groupValues[2].trim())
            sink().add(node.spanning(i, i + 1))
            i++
            continue@mainLoop
        }
        if (line.trimStart().startsWith("|")) {
            flushParagraph()
            val (table, next) = parseTable(i)
            sink().add(table)
            i = next
            continue
        }
        // Prefer parsing headings (lines starting with one or more '*') as sections before '*' lists
        if (line.startsWith("*")) {
            flushParagraph()
            val heading = orgHeadingOf(line)
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
        // Lists come from the shared grammar now, which also covers `1.`/`1)` and nesting. A `*`
        // marker at column 0 never reaches here: the heading rule above claims it first.
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
    document.range = if (source.isEmpty()) IntRange.EMPTY else 0 until source.length
    return document
}

private fun OrgNode.cellText(): String = (this as? OrgRaw)?.content ?: this.toOrgString()

// Renders the table as Org text with every column padded to the widest cell (like Emacs does),
// so the result is aligned when displayed in a monospace font.
fun OrgTable.toAlignedLines(): List<String> {
    val cols = columnCount
    if (cols == 0) return rows.map { "|-|" }
    val widths = IntArray(cols)
    for (row in rows) {
        if (row.isRule) continue
        row.cells.forEachIndexed { c, cell -> widths[c] = maxOf(widths[c], cell.cellText().length) }
    }
    return rows.map { row ->
        if (row.isRule) {
            widths.joinToString(separator = "+", prefix = "|", postfix = "|") { "-".repeat(it + 2) }
        } else {
            (0 until cols).joinToString(separator = "|", prefix = "|", postfix = "|") { c ->
                val text = row.cells.getOrNull(c)?.cellText().orEmpty()
                if (text.isNumeric()) " " + text.padStart(widths[c]) + " " else " " + text.padEnd(widths[c]) + " "
            }
        }
    }
}

// Emacs right-aligns cells that look like numbers.
private val numericCellRegex = Regex("^[-+]?(\\d+([.,]\\d*)?|[.,]\\d+)([eE][-+]?\\d+)?%?$")
fun String.isNumeric(): Boolean = isNotEmpty() && numericCellRegex.matches(this)

// Represents parsed TODO workflow configuration from #+TODO metadata lines.
data class OrgTodoConfig(val todo: Set<String>, val done: Set<String>)

// Parse #+TODO metadata lines to extract TODO/DONE state sets.
fun parseTodoConfigFrom(metadata: List<String>): OrgTodoConfig {
    val todo = linkedSetOf<String>()
    val done = linkedSetOf<String>()
    val lineRegex = Regex("^#\\+TODO:\\s*(.*)", RegexOption.IGNORE_CASE)
    for (line in metadata) {
        val m = lineRegex.find(line.trim()) ?: continue
        val spec = m.groupValues[1].trim()
        // Split around first '|' if present
        val parts = spec.split('|', limit = 2)
        val left = parts.getOrNull(0)?.trim().orEmpty()
        val right = parts.getOrNull(1)?.trim().orEmpty()
        fun parseSide(side: String): List<String> {
            if (side.isBlank()) return emptyList()
            // Tokens like WORD(k) possibly repeated; also allow bare WORD
            val tokenRegex = Regex("([A-Z][A-Z0-9_-]*)(?:\\([^)]*\\))?")
            return tokenRegex.findAll(side).map { it.groupValues[1] }.toList()
        }
        parseSide(left).forEach { todo.add(it) }
        parseSide(right).forEach { done.add(it) }
    }
    return OrgTodoConfig(todo, done)
}


// Flips the checked state of the nth (0-based, document order) checkbox item directly in the raw
// text, changing only that line's "[ ]"/"[x]" marker and leaving every other character - including
// blank lines, trailing whitespace, and indentation elsewhere in the file - untouched.
// Returns null if there is no such checkbox (ordinal out of range).
// Convenience helpers to classify a section's state according to document metadata.
fun OrgDocument.todoConfig(): OrgTodoConfig = parseTodoConfigFrom(this.metadata)
fun OrgSection.isTodo(doc: OrgDocument): Boolean = this.todo != null && this.todo in doc.todoConfig().todo
fun OrgSection.isDone(doc: OrgDocument): Boolean = this.todo != null && this.todo in doc.todoConfig().done

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
    is OrgDescriptionItem -> "- $term :: $description"
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
        append("- [").append(if (checked) "x" else " ").append("] ").append(text)
        children.forEach { appendLine(); append(it.toOrgString()) }
    }
    is OrgListItem -> buildString {
        append("- ").append(text)
        children.forEach { appendLine(); append(it.toOrgString()) }
    }
    is OrgList -> children.mapIndexed { index, child ->
        val pad = " ".repeat(indent)
        val marker = if (ordered) "${index + 1}$bulletType" else "$bulletType"
        val body = when (child) {
            is OrgCheckboxItem -> "[" + (if (child.checked) "x" else " ") + "] " + child.text
            is OrgListItem -> child.text
            is OrgDescriptionItem -> "${child.term} :: ${child.description}"
            is OrgRaw -> child.content
            else -> child.toOrgString()
        }
        val nested = child.children.joinToString("") { "\n" + it.toOrgString() }
        "$pad$marker $body$nested"
    }.joinToString("\n")
    is OrgSection -> buildString {
        append("*".repeat(level.coerceAtLeast(1))).append(' ')
        if (todo != null) append(todo).append(' ')
        if (priority != null) append("[#").append(priority).append("] ")
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
