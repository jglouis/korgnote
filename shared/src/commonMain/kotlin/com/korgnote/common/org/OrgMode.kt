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
) : OrgNode()

// A flat list of items where each child is typically OrgRaw or OrgSection; bulletType is '-', '+', or '*'.
data class OrgList(val bulletType: Char, override val children: List<OrgNode>) : OrgNode()

// Lines that start with #+
data class OrgKeyword(val content: String) : OrgNode()

// SCHEDULED or DEADLINE timestamp line under a heading
data class OrgPlanning(val planType: String, val timestamp: String) : OrgNode()

// Any raw text paragraph or line content that doesn't fit another construct.
data class OrgRaw(val content: String) : OrgNode()

// TODO state for headings is a free-form string (e.g., TODO, DONE, WAITING) defined by document metadata.
// We intentionally avoid an enum to allow arbitrary states.

// Checkbox list item like: - [ ] Task or - [X] Done
// Only supports simple on/off state; nested states are out of scope for now.
data class OrgCheckboxItem(val checked: Boolean, val text: String) : OrgNode()

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
    return OrgHeading(level = level, todo = todo, title = title, tags = tags)
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

    fun parseList(start: Int, bullet: Char): Pair<OrgNode, Int> {
        val items = mutableListOf<OrgNode>()
        var idx = start
        // The bullet goes in a character class: escaping it with a backslash is not portable to JS regexes
        val itemRegex = Regex("^\\s*[$bullet]\\s+(.*)")
        // `\]` is not redundant: JS/Wasm regexes run in `u` mode where a lone `]` is a SyntaxError
        @Suppress("RegExpRedundantEscape")
        val checkboxRegex = Regex("^\\s*[$bullet]\\s+\\[([ xX])\\]\\s*(.*)")
        while (idx < lines.size) {
            val l = lines[idx]
            val m = itemRegex.find(l) ?: break
            // If it is a checkbox item, parse its state and text
            val cb = checkboxRegex.find(l)
            if (cb != null) {
                val mark = cb.groupValues[1]
                val text = cb.groupValues[2]
                val checked = mark.equals("x", ignoreCase = true)
                items.add(OrgCheckboxItem(checked, text).spanning(idx, idx + 1))
            } else {
                val itemText = m.groupValues[1]
                items.add(OrgRaw(itemText).spanning(idx, idx + 1))
            }
            idx++
        }
        return OrgList(bullet, items).spanning(start, idx) to idx
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
        val open = openSections.removeLast()
        val section = OrgSection(
            title = open.heading.title,
            todo = open.heading.todo,
            level = open.heading.level,
            tags = open.heading.tags,
            children = open.children.toList(),
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
        if (line.startsWith("#+")) {
            flushParagraph()
            metadata.add(line)
            sink().add(OrgKeyword(line).spanning(i, i + 1))
            i++
            continue
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
        // Planning lines: SCHEDULED: or DEADLINE:
        run {
            val pm = Regex("^(?:SCHEDULED|DEADLINE):\\s*(.*)").find(line)
            if (pm != null) {
                flushParagraph()
                val type = if (line.trimStart().startsWith("SCHEDULED:")) "SCHEDULED" else "DEADLINE"
                val ts = pm.groupValues[1].trim()
                sink().add(OrgPlanning(planType = type, timestamp = ts).spanning(i, i + 1))
                i++
                continue@mainLoop
            }
        }
        val listBullet = when {
            Regex("^\\s*- ").containsMatchIn(line) -> '-'
            Regex("^\\s*\\+ ").containsMatchIn(line) -> '+'
            // Do not treat single '*' as list bullet at top-level anymore; keep '*' out to avoid conflict with sections
            else -> null
        }
        if (listBullet != null) {
            flushParagraph()
            val (listNode, next) = parseList(i, listBullet)
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

// Returns true when a line (as it appears in the raw text) is recognized as a checkbox list
// item by the parser above: a top-level "- "/"+ " bullet whose text starts with "[ ]"/"[x]"/"[X]".
// Kept in sync with parseList()'s checkboxRegex so raw-text ordinals match the parsed document.
private val rawCheckboxLineRegex = Regex("^\\s*[-+]\\s+\\[([ xX])]\\s*.*")

// Flips the checked state of the nth (0-based, document order) checkbox item directly in the raw
// text, changing only that line's "[ ]"/"[x]" marker and leaving every other character - including
// blank lines, trailing whitespace, and indentation elsewhere in the file - untouched.
// Returns null if there is no such checkbox (ordinal out of range).
fun String.toggleNthCheckbox(ordinal: Int, checked: Boolean): String? {
    val lines = this.split('\n').toMutableList()
    var count = 0
    for (idx in lines.indices) {
        val line = lines[idx]
        // Section content bodies never contain checkbox lines (parseSection stops before them),
        // so a plain line scan visits checkbox lines in the same order toOrgDocument() would.
        if (!rawCheckboxLineRegex.matches(line)) continue
        if (count == ordinal) {
            val newMark = if (checked) "x" else " "
            lines[idx] = line.replaceFirst(Regex("\\[[ xX]]"), "[$newMark]")
            return lines.joinToString("\n")
        }
        count++
    }
    return null
}

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
    is OrgCheckboxItem -> "- [" + (if (checked) "x" else " ") + "] " + text
    is OrgList -> children.joinToString("\n") { child ->
        when (child) {
            is OrgCheckboxItem -> "- [" + (if (child.checked) "x" else " ") + "] " + child.text
            is OrgRaw -> "${this.bulletType} ${child.content}"
            else -> "${this.bulletType} ${child.toOrgString()}"
        }
    }
    is OrgSection -> buildString {
        append("*".repeat(level.coerceAtLeast(1))).append(' ')
        if (todo != null) append(todo).append(' ')
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
    is OrgPlanning -> when (planType) {
        "SCHEDULED" -> "SCHEDULED: $timestamp"
        "DEADLINE" -> "DEADLINE: $timestamp"
        else -> timestamp
    }
}