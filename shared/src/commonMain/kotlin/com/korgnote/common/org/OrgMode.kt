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
}

// Top-level org document with parsed children and collected file keywords (#+TITLE, #+AUTHOR, etc.).
data class OrgDocument(val children: List<OrgNode>, val metadata: List<String>) : OrgNode() {
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

// A section is a heading with its inline content (paragraph text after heading line until next heading).
// level is the number of leading stars; tags are the trailing :tag1:tag2: of the heading line (without colons).
data class OrgSection(
    val title: String,
    val content: String,
    val todo: String? = null,
    val level: Int = 1,
    val tags: List<String> = emptyList(),
) : OrgNode()

// A flat list of items where each child is typically OrgRaw or OrgSection; bulletType is '-', '+', or '*'.
data class OrgList(val bulletType: Char, val children: List<OrgNode>) : OrgNode()

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
}

data class OrgTableRow(val cells: List<OrgNode>, val isRule: Boolean = false) : OrgNode()

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

    fun flushParagraph() {
        if (pendingParagraph.isNotEmpty()) {
            children.add(OrgRaw(pendingParagraph.toString().trimEnd()).spanning(paragraphStart, i))
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

    fun parseSection(start: Int): Pair<OrgNode, Int> {
        val heading = lines[start]
        val m = headingLineRegex.find(heading.trim())
        val level = m?.groupValues?.getOrNull(1)?.length?.coerceAtLeast(1) ?: 1
        val token = m?.groupValues?.getOrNull(2)
        var title = m?.groupValues?.getOrNull(3)?.ifBlank { null } ?: heading.trim()
        // Trailing :tag1:tag2: are stripped from the title and kept separately
        val tags = headingTagsRegex.find(title)?.let { tm ->
            title = title.substring(0, tm.range.first).trimEnd()
            tm.groupValues[1].split(':')
        } ?: emptyList()
        // Capture TODO-like token as-is (arbitrary, defined by metadata)
        val todo: String? = token
        val contentSb = StringBuilder()
        var idx = start + 1
        while (idx < lines.size) {
            val l = lines[idx]
            val trim = l.trimEnd()
            // stop at next heading of same or higher level (starts with *)
            if (trim.startsWith("*")) break
            // stop before a top-level simple list so that lists after headings are parsed as lists, not raw content
            if (Regex("^\\s*[-+] ").containsMatchIn(trim)) break
            // stop before planning lines so they are separate nodes
            if (Regex("^(?:SCHEDULED|DEADLINE):").containsMatchIn(trim)) break
            if (trim.isBlank()) {
                contentSb.append('\n')
                idx++
                continue
            }
            contentSb.appendLine(trim)
            idx++
        }
        val section = OrgSection(title, contentSb.toString().trimEnd(), todo, level, tags)
        return section.spanning(start, idx) to idx
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
            children.add(OrgKeyword(line).spanning(i, i + 1))
            i++
            continue
        }
        if (line.trimStart().startsWith("|")) {
            flushParagraph()
            val (table, next) = parseTable(i)
            children.add(table)
            i = next
            continue
        }
        // Prefer parsing headings (lines starting with one or more '*') as sections before '*' lists
        if (line.startsWith("*")) {
            flushParagraph()
            val (section, next) = parseSection(i)
            children.add(section)
            i = next
            continue
        }
        // Planning lines: SCHEDULED: or DEADLINE:
        run {
            val pm = Regex("^(?:SCHEDULED|DEADLINE):\\s*(.*)").find(line)
            if (pm != null) {
                flushParagraph()
                val type = if (line.trimStart().startsWith("SCHEDULED:")) "SCHEDULED" else "DEADLINE"
                val ts = pm.groupValues[1].trim()
                children.add(OrgPlanning(planType = type, timestamp = ts).spanning(i, i + 1))
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
            children.add(listNode)
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
private val rawCheckboxLineRegex = Regex("^\\s*[-+]\\s+\\[([ xX])\\]\\s*.*")

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
            lines[idx] = line.replaceFirst(Regex("\\[[ xX]\\]"), "[$newMark]")
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
        appendLine()
        if (content.isNotBlank()) appendLine(content.trimEnd())
    }.trimEnd()
    is OrgTable -> toAlignedLines().joinToString("\n")
    is OrgTableRow -> if (isRule) "|-|" else "| " + cells.joinToString(" | ") { it.cellText() } + " |"
    is OrgPlanning -> when (planType) {
        "SCHEDULED" -> "SCHEDULED: $timestamp"
        "DEADLINE" -> "DEADLINE: $timestamp"
        else -> timestamp
    }
}