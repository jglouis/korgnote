package com.korgnote.common.org

/**
 * Line-level Org syntax patterns.
 *
 * This lives in `shared/` so the parser and the highlighter recognise Org the same way. It used to
 * sit beside the highlighter, which left the parser with its own private notion of what a heading or
 * a list item was — the kind of split that lets the two disagree about a file. Moving it here is the
 * first half of the "one grammar" idea; T6 finishes the job by having the highlighter consume the
 * parse tree instead of re-scanning the text.
 *
 * The `\]` escapes are flagged as redundant by the IDE (JVM semantics) but they are required:
 * Kotlin/JS and Kotlin/Wasm compile regexes with the JavaScript `u` flag, where a lone `]` is a SyntaxError.
 */
@Suppress("RegExpRedundantEscape")
object OrgSyntax {
    val heading = Regex("^(\\*+)\\s")
    val blockBegin = Regex("^\\s*#\\+BEGIN_(\\S+)(.*)$", RegexOption.IGNORE_CASE)
    val blockEnd = Regex("^\\s*#\\+END_(\\S*)\\s*$", RegexOption.IGNORE_CASE)
    val keyword = Regex("^\\s*#\\+")
    val comment = Regex("^\\s*#(\\s|$)")
    val drawerBegin = Regex("^\\s*:[A-Za-z0-9_-]+:\\s*$")
    val drawerEnd = Regex("^\\s*:END:\\s*$", RegexOption.IGNORE_CASE)
    val property = Regex("^(\\s*:[A-Za-z0-9_+-]+:)(.*)$")
    val planning = Regex("^\\s*(SCHEDULED|DEADLINE|CLOSED):")
    val planningKeyword = Regex("(SCHEDULED|DEADLINE|CLOSED):")
    val horizontalRule = Regex("^\\s*-{5,}\\s*$")
    val listItem = Regex("^(\\s*)([-+*]|\\d+[.)])(\\s+)(?:(\\[[ xX-]\\])(?=\\s|$))?")
    val descriptionTerm = Regex("^(\\s*)(.+?)(\\s+::)(\\s|$)")
    val priority = Regex("^\\[#[A-Z0-9]\\]")
    val tags = Regex("\\s+(:[\\p{L}\\p{N}_@#%]+(?::[\\p{L}\\p{N}_@#%]+)*:)\\s*$")
    val footnoteDefinition = Regex("^\\[fn:([^\\]]+)\\]\\s*(.*)$")

    /** Block types whose content is rendered verbatim in a monospace font. */
    val monospaceBlocks = setOf("src", "example", "export")

    fun isTableLine(line: String) = line.isOrgTableLine()
    fun isTableRuleLine(line: String) = line.isOrgTableRuleLine()
}
