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
    /** A `CLOCK:` line of a logbook, its keyword (group 1) and what follows it (group 2). */
    val clock = Regex("^(\\s*CLOCK:)(.*)$")
    val planningKeyword = Regex("(SCHEDULED|DEADLINE|CLOSED):")
    val horizontalRule = Regex("^\\s*-{5,}\\s*$")
    /**
     * A list item's start: indent, bullet, the space after it, then an optional `[@3]` counter and an
     * optional checkbox. The counter is matched (so it is not item text) but not captured, which keeps
     * the checkbox group 4 and last for everything that measures it from the end of the match; read
     * the counter with [listCounter].
     */
    val listItem = Regex("^(\\s*)([-+*]|\\d+[.)])(\\s+)(?:\\[@\\d+\\](?:\\s+|$))?(?:(\\[[ xX-]\\])(?=\\s|$))?")

    /** The `[@3]` that sets an item's number, capturing the number. */
    val listCounter = Regex("\\[@(\\d+)\\]")

    /** The `\begin{equation}` that opens a LaTeX environment, capturing its name. */
    val latexBegin = Regex("^[ \\t]*\\\\begin\\{([A-Za-z0-9*]+)\\}")

    /** The `\end{name}` that closes the LaTeX environment called [name]. */
    fun latexEnd(name: String) = Regex("\\\\end\\{${Regex.escape(name)}\\}[ \\t]*$")

    /** A fixed-width line, `: text` or a lone `:`, shown verbatim in a monospace font. */
    val fixedWidth = Regex("^[ \\t]*:( |$)")
    val descriptionTerm = Regex("^(\\s*)(.+?)(\\s+::)(\\s|$)")
    /**
     * A priority cookie at the start of a heading's title, capturing its value: an uppercase letter,
     * or a number from 0 to 64, as Org allows (see [OrgPriority]).
     */
    val priority = Regex("^\\[#([A-Z]|6[0-4]|[1-5]?[0-9])\\]")
    val tags = Regex("\\s+(:[\\p{L}\\p{N}_@#%]+(?::[\\p{L}\\p{N}_@#%]+)*:)\\s*$")
    val footnoteDefinition = Regex("^\\[fn:([^\\]]+)\\]\\s*(.*)$")

    // --- Inline (intra-line) constructs ---

    val link = Regex("\\[\\[([^\\[\\]]+)\\](?:\\[([^\\[\\]]*)\\])?\\]")
    val url = Regex("(?:https?|ftp|mailto):[^\\s<>\\[\\]()\"']+")
    val timestamp = Regex("[<\\[]\\d{4}-\\d{2}-\\d{2}(?: [^\\]>\\n]*)?[>\\]]")
    val footnoteReference = Regex("\\[fn:[^\\]]+\\]")
    /**
     * A statistics cookie, `[2/5]` or `[40%]`, capturing its two numbers (or its percentage, then
     * nothing). Either number may be empty, as in the `[/]` and `[%]` typed for Org to fill in.
     */
    val statistics = Regex("\\[(\\d*)(?:/(\\d*)|%)\\]")

    /** Characters an emphasis run may open after, and close before. */
    const val EMPHASIS_PRE = " \t-('\"{"
    const val EMPHASIS_POST = " \t-.,:!?;'\")}["

    /** Emphasis markers and what each one means, with the regex that matches a run of it. */
    val emphasis: Map<Char, Regex> = listOf('*', '/', '_', '+', '=', '~').associateWith { m ->
        val lit = if (m == '*' || m == '+') "\\$m" else "$m"
        // marker, non-space, (anything not the marker, lazily, ending with non-space)?, marker
        Regex("$lit([^\\s$m](?:[^$m\\n]*?[^\\s$m])?)$lit")
    }

    /** True when an emphasis run may open at [index]. */
    fun emphasisMayOpenAt(text: String, index: Int) = index == 0 || text[index - 1] in EMPHASIS_PRE

    /** True when an emphasis run may close at [end]. */
    fun emphasisMayCloseAt(text: String, end: Int) = end >= text.length || text[end] in EMPHASIS_POST

    /**
     * True when a heading's [title] — what follows its TODO keyword and priority — starts with
     * `COMMENT`, which comments the whole subtree out: Org leaves it out of export and of the agenda.
     */
    fun isCommentedTitle(title: String) = title == "COMMENT" || title.startsWith("COMMENT ")

    /** The tag that marks a subtree archived, which the agenda passes over (`org-archive-tag`). */
    const val ARCHIVE_TAG = "ARCHIVE"

    /** The `#+CAPTION:` that titles the table or figure below it, capturing the caption. */
    val captionKeyword = Regex("^[ \\t]*#\\+CAPTION(?:\\[[^\\]]*\\])?:[ \\t]*(.*)$", RegexOption.IGNORE_CASE)

    /** The `#+TITLE:` keyword, capturing the title. */
    val titleKeyword = Regex("^#\\+TITLE:\\s*(.*)$", RegexOption.IGNORE_CASE)

    /** The `#+FILETAGS:` keyword, capturing the tags every heading in the file inherits. */
    val filetagsKeyword = Regex("^#\\+FILETAGS:\\s*(.*)$", RegexOption.IGNORE_CASE)

    /**
     * What a heading's first word must look like to be read as its TODO keyword, and what a
     * `#+TODO:` line may declare. Whether that word *is* a keyword is up to the file's declaration.
     */
    const val TODO_WORD = "[A-Z][A-Z0-9_-]*"

    /**
     * A `#+TODO:` line, or the `#+SEQ_TODO:` and `#+TYP_TODO:` spellings Org reads the same way,
     * capturing what it declares: `TODO(t) WAIT(w@/!) | DONE(d!)`.
     */
    val todoKeywordsLine = Regex("^[ \\t]*#\\+(?:SEQ_|TYP_)?TODO:[ \\t]*(.*)$", RegexOption.IGNORE_CASE)

    /** One word of a `#+TODO:` line: the keyword, then its optional `(key@/!)` settings. */
    val todoKeywordDeclaration = Regex("^($TODO_WORD)(?:\\([^)]*\\))?$")

    /** The date at the start of a timestamp, capturing year, month and day. */
    val timestampDate = Regex("<(\\d{4})-(\\d{2})-(\\d{2})")

    /** Block types whose content is rendered verbatim in a monospace font. */
    val monospaceBlocks = setOf("src", "example", "export")

    fun isTableLine(line: String) = line.isOrgTableLine()
    fun isTableRuleLine(line: String) = line.isOrgTableRuleLine()
}
