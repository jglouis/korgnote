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
@Serializable
@SerialName("section")
data class OrgSection(val title: String, val content: String, val todo: String? = null) : OrgNode

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

// Very simplified table model: consecutive lines starting with '|' become a single table with rows of cells.
@Serializable
@SerialName("table")
 data class OrgTable(val rows : OrgTableRow) : OrgNode
 
@Serializable
@SerialName("tableRow")
 data class OrgTableRow(val cells : List<OrgNode>) :OrgNode

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
        val tableRows = mutableListOf<OrgTableRow>()
        var idx = start
        while (idx < lines.size) {
            val l = lines[idx]
            if (!l.trimStart().startsWith("|")) break
            // Split cells by '|' ignoring the first empty entry due to leading '|'
            val parts = l.trim().trimEnd().split('|')
            val cells = parts
                .filter { it.isNotEmpty() }
                .map { OrgRaw(it.trim()) as OrgNode }
            tableRows.add(OrgTableRow(cells))
            idx++
        }
        // The OrgTable type accepts a single OrgTableRow; to keep compatibility with current model,
        // we will wrap multiple rows into a single row where each cell is an OrgRaw joined by newlines.
        // If there is at least one row, we convert rows into a single row with string-joined lines.
        val singleRow = if (tableRows.isEmpty()) OrgTableRow(emptyList()) else OrgTableRow(
            tableRows.map { row -> OrgRaw(row.cells.joinToString(" | ") { (it as? OrgRaw)?.content ?: "" }) as OrgNode }
        )
        return OrgTable(singleRow) to idx
    }

    fun parseList(start: Int, bullet: Char): Pair<OrgNode, Int> {
        val items = mutableListOf<OrgNode>()
        var idx = start
        val itemRegex = Regex("^\\s*\\$bullet\\s+(.*)")
        val checkboxRegex = Regex("^\\s*\\$bullet\\s+\\[([ xX])]\\s*(.*)")
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
        val m = Regex("^(\\*+)\\s+(?:([A-Z][A-Z0-9_-]*)\\s+)?(.*)").find(heading.trim())
        val token = m?.groupValues?.getOrNull(2)
        val title = m?.groupValues?.getOrNull(3)?.ifBlank { null } ?: heading.trim()
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
        return OrgSection(title, contentSb.toString().trimEnd(), todo) to idx
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
        // We don't track heading level; emit single-star heading with optional TODO token
        if (todo != null) append("* ").append(todo).append(' ').appendLine(title) else append("* ").appendLine(title)
        if (content.isNotBlank()) appendLine(content.trimEnd())
    }.trimEnd()
    is OrgTable -> {
        val row = rows
        if (row.cells.isEmpty()) "" else row.cells.joinToString("\n") { cell -> "| " + (cell as? OrgRaw)?.content.orEmpty() + " |" }
    }
    is OrgTableRow -> cells.joinToString(" | ") { (it as? OrgRaw)?.content ?: "" }
    is OrgPlanning -> when (planType) {
        "SCHEDULED" -> "SCHEDULED: $timestamp"
        "DEADLINE" -> "DEADLINE: $timestamp"
        else -> timestamp
    }
}