package com.korgnote.common.org

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// An immutable node in an org document.
@Serializable
sealed interface OrgNode

// Top-level org document with parsed children and collected file keywords (#+TITLE, #+AUTHOR, etc.).
@Serializable
@SerialName("document")
data class OrgDocument(val children: List<OrgNode>, val metadata: List<String>) : OrgNode

// A section is a heading with its inline content (paragraph text after heading line until next heading).
// level is the number of leading stars; tags are the trailing :tag1:tag2: of the heading line (without colons).
@Serializable
@SerialName("section")
data class OrgSection(
    val title: String,
    val content: String,
    val todo: String? = null,
    val level: Int = 1,
    val tags: List<String> = emptyList(),
) : OrgNode

// A flat list of items where each child is typically OrgRaw or OrgSection; bulletType is '-', '+', or '*'.
@Serializable
@SerialName("list")
data class OrgList(val bulletType: Char, val children: List<OrgNode>) : OrgNode

// Lines that start with #+...
@Serializable
@SerialName("keyword")
data class OrgKeyword(val content: String) : OrgNode

// SCHEDULED or DEADLINE timestamp line under a heading
@Serializable
@SerialName("planning")
data class OrgPlanning(@SerialName("planType") val planType: String, val timestamp: String) : OrgNode

// Any raw text paragraph or line content that doesn't fit another construct.
@Serializable
@SerialName("raw")
data class OrgRaw(val content: String) : OrgNode

// TODO state for headings is a free-form string (e.g., TODO, DONE, WAITING) defined by document metadata.
// We intentionally avoid an enum to allow arbitrary states.

// Checkbox list item like: - [ ] Task or - [X] Done
// Only supports simple on/off state; nested states are out of scope for now.
@Serializable
@SerialName("checkboxItem")
data class OrgCheckboxItem(val checked: Boolean, val text: String) : OrgNode

// Table model: consecutive lines starting with '|' become a single table. Each line is a row; a line
// starting with "|-" is a horizontal rule row (isRule = true, no cells).
@Serializable
@SerialName("table")
data class OrgTable(val rows: List<OrgTableRow>) : OrgNode {
    val columnCount: Int get() = rows.maxOfOrNull { it.cells.size } ?: 0
}

@Serializable
@SerialName("tableRow")
data class OrgTableRow(val cells: List<OrgNode>, val isRule: Boolean = false) : OrgNode

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
    val lines = this.split('\n')
    val children = mutableListOf<OrgNode>()
    val metadata = mutableListOf<String>()

    var i = 0
    var pendingParagraph = StringBuilder()

    fun flushParagraph() {
        if (pendingParagraph.isNotEmpty()) {
            children.add(OrgRaw(pendingParagraph.toString().trimEnd()))
            pendingParagraph = StringBuilder()
        }
    }

    fun parseTable(start: Int): Pair<OrgNode, Int> {
        var idx = start
        while (idx < lines.size && lines[idx].isOrgTableLine()) idx++
        return parseOrgTable(lines.subList(start, idx)) to idx
    }

    fun parseList(start: Int, bullet: Char): Pair<OrgNode, Int> {
        val items = mutableListOf<OrgNode>()
        var idx = start
        // The bullet goes in a character class: escaping it with a backslash is not portable to JS regexes
        val itemRegex = Regex("^\\s*[$bullet]\\s+(.*)")
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
                items.add(OrgCheckboxItem(checked, text))
            } else {
                val itemText = m.groupValues[1]
                items.add(OrgRaw(itemText))
            }
            idx++
        }
        return OrgList(bullet, items) to idx
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
        return OrgSection(title, contentSb.toString().trimEnd(), todo, level, tags) to idx
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
            children.add(OrgKeyword(line))
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
                children.add(OrgPlanning(planType = type, timestamp = ts))
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
        if (pendingParagraph.isNotEmpty()) pendingParagraph.append('\n')
        pendingParagraph.append(line)
        i++
    }
    flushParagraph()

    return OrgDocument(children, metadata)
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