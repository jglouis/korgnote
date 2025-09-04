package com.korgnote.common.org

// An immutable node in an org document.
interface OrgNode

// Top-level org document with parsed children and collected file keywords (#+TITLE, #+AUTHOR, etc.).
data class OrgDocument(val children: List<OrgNode>, val metadata: List<String>) : OrgNode

// A section is a heading with its inline content (paragraph text after heading line until next heading).
data class OrgSection(val title: String, val content: String) : OrgNode

// A flat list of items where each child is typically OrgRaw or OrgSection; bulletType is '-', '+', or '*'.
data class OrgList(val bulletType: Char, val children: List<OrgNode>) : OrgNode

// Lines that start with #+...
data class OrgKeyword(val content: String) : OrgNode

// Any raw text paragraph or line content that doesn't fit another construct.
data class OrgRaw(val content: String) : OrgNode

// Checkbox list item like: - [ ] Task or - [X] Done
// Only supports simple on/off state; nested states are out of scope for now.
data class OrgCheckboxItem(val checked: Boolean, val text: String) : OrgNode

// Very simplified table model: consecutive lines starting with '|' become a single table with rows of cells.
data class OrgTable(val rows : OrgTableRow) : OrgNode

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
        val itemRegex = Regex("^\\s*\\" + bullet + "\\s+(.*)")
        val checkboxRegex = Regex("^\\s*\\" + bullet + "\\s+\\[([ xX])]\\s*(.*)")
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
        val m = Regex("^(\\*+)\\s+(.*)").find(heading.trimEnd())
        val title = m?.groupValues?.get(2) ?: heading.trim()
        val contentSb = StringBuilder()
        var idx = start + 1
        while (idx < lines.size) {
            val l = lines[idx]
            val trim = l.trimEnd()
            // stop at next heading of same or higher level (starts with *)
            if (trim.startsWith("*")) break
            if (trim.isBlank()) {
                contentSb.append('\n')
                idx++
                continue
            }
            contentSb.appendLine(trim)
            idx++
        }
        return OrgSection(title, contentSb.toString().trimEnd()) to idx
    }

    while (i < lines.size) {
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
        val listBullet = when {
            Regex("^\\s*- ").containsMatchIn(line) -> '-'
            Regex("^\\s*\\+ ").containsMatchIn(line) -> '+'
            Regex("^\\s*\\* ").containsMatchIn(line) -> '*'
            else -> null
        }
        if (listBullet != null) {
            flushParagraph()
            val (listNode, next) = parseList(i, listBullet)
            children.add(listNode)
            i = next
            continue
        }
        if (line.startsWith("*")) {
            flushParagraph()
            val (section, next) = parseSection(i)
            children.add(section)
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