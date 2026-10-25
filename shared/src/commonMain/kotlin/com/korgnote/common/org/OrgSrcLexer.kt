package com.korgnote.common.org

/*
 * Highlighting for the body of `#+BEGIN_SRC` blocks.
 *
 * A handful of small hand-written lexers rather than a library: they have to run on every target
 * (wasm included), they have to work one line at a time so the editor's per-line cache keeps working,
 * and Lisp — the second most common language in the notes — is missing from the multiplatform
 * libraries. A lexer only has to tell keywords, strings, comments and the like apart, so a family of
 * languages shares one lexer configured with its own word lists.
 *
 * Each lexer reads one line and returns the state the next line starts in: null between tokens, or a
 * marker for a string, comment or heredoc still open at the end of the line. [OrgLineState] carries
 * that marker, so it takes part in the cache's "same line, same incoming state" check.
 */

/** The language a `#+BEGIN_SRC` line names, lowercased, or null when it names none. */
fun srcLanguageOf(parameters: String): String? {
    val first = parameters.trim().substringBefore(' ').substringBefore('\t')
    return first.lowercase().takeIf { it.isNotEmpty() && !it.startsWith(':') }
}

/** True when src blocks in [language] are highlighted; the rest are shown as plain monospace. */
fun isHighlightedSrcLanguage(language: String): Boolean = language.lowercase() in srcLexers

/**
 * Spans for the body of a src block in [language], for a renderer that draws the block on its own.
 * Offsets are into [body]; an unknown language has none.
 */
fun srcBlockSpans(body: String, language: String): List<OrgSpan> {
    val name = language.lowercase()
    if (name !in srcLexers) return emptyList()
    val spans = mutableListOf<OrgSpan>()
    var state: String? = null
    var lineStart = 0
    while (lineStart <= body.length) {
        val newline = body.indexOf('\n', lineStart)
        val lineEnd = if (newline == -1) body.length else newline
        state = lexSrcLine(body.substring(lineStart, lineEnd), lineStart, name, state) { range, kind ->
            spans.add(OrgSpan(range, kind))
        }
        if (newline == -1) break
        lineStart = newline + 1
    }
    return spans
}

/**
 * Classifies one line of a src block in [language], which starts in [state], passing the spans to
 * [add] at [offset]. Returns the state the next line starts in. A null [add] only advances the state.
 */
internal fun lexSrcLine(
    line: String,
    offset: Int,
    language: String,
    state: String?,
    add: ((IntRange, OrgSpanKind) -> Unit)?,
): String? {
    val lexer = srcLexers[language] ?: return null
    val tokens = SrcTokens(line)
    val next = lexer.lex(line, state, tokens)
    if (add != null) tokens.emit(offset, add)
    return next
}

private fun interface SrcLexer {
    /** Lexes [line], which starts in [state], into [out]; returns the state the next line starts in. */
    fun lex(line: String, state: String?, out: SrcTokens): String?
}

/**
 * The tokens of one line, collected left to right.
 *
 * Text between tokens stays unstyled. When that text is only whitespace it is folded into the token
 * after it (or, at the end of the line, the one before): a style run holding only whitespace is
 * measured as zero width by the Skia text backend, which collapses indentation and moves the caret.
 * See the note on [orgSpans] and `OrgWhitespaceRunTest`.
 */
private class SrcTokens(val line: String) {
    private class Token(var start: Int, var end: Int, val kind: OrgSpanKind)

    private val tokens = mutableListOf<Token>()
    private val nested = mutableListOf<Token>()

    /** Marks [start] until [end] as [kind]. Tokens arrive left to right and never overlap. */
    fun add(start: Int, end: Int, kind: OrgSpanKind) {
        if (end > start) tokens.add(Token(start, end, kind))
    }

    /**
     * Marks a token inside the last one, such as a variable inside a shell string. It takes the
     * whitespace after it, up to [limit], so the stretch between two nested tokens never holds only
     * whitespace.
     */
    fun addNested(start: Int, end: Int, kind: OrgSpanKind, limit: Int) {
        var stop = end
        while (stop < limit && line[stop].isWhitespace()) stop++
        if (stop > start) nested.add(Token(start, stop, kind))
    }

    /** Marks [start] through [close] as [kind]; the index after it, or -1 when the line ends first. */
    fun region(start: Int, contentStart: Int, close: String, kind: OrgSpanKind, escapes: Boolean): Int {
        val end = findClose(line, contentStart, close, escapes)
        add(start, if (end < 0) line.length else end, kind)
        return end
    }

    fun emit(offset: Int, add: (IntRange, OrgSpanKind) -> Unit) {
        var previousEnd = 0
        for (token in tokens) {
            if (isBlank(previousEnd, token.start)) token.start = previousEnd
            previousEnd = token.end
        }
        tokens.lastOrNull()?.let { if (isBlank(it.end, line.length)) it.end = line.length }
        for (token in tokens) add(offset + token.start until offset + token.end, token.kind)
        for (token in nested) add(offset + token.start until offset + token.end, token.kind)
    }

    private fun isBlank(from: Int, to: Int): Boolean {
        for (i in from until to) if (!line[i].isWhitespace()) return false
        return true
    }
}

/** The index just past [close] at or after [from], or -1 when the line ends first. */
private fun findClose(line: String, from: Int, close: String, escapes: Boolean): Int {
    var j = from
    while (j < line.length) {
        if (escapes && line[j] == '\\') {
            j += 2
            continue
        }
        if (line.startsWith(close, j)) return j + close.length
        j++
    }
    return -1
}

private fun words(list: String): Set<String> = list.split(' ').filter { it.isNotEmpty() }.toSet()

private val numberPattern = Regex("^[+-]?(0[xX][0-9a-fA-F_]+|\\d[\\d_]*(\\.\\d+)?([eE][+-]?\\d+)?)$")

private fun isNumber(word: String) = numberPattern.matches(word)

// --- C-like languages, and the others that only differ in their word lists and delimiters ---

/**
 * Keywords, strings, comments, numbers and calls, for languages built from those: the C family,
 * Java, JavaScript, Python, Lua, SQL and the like.
 *
 * States: `c` + the closing delimiter inside a block comment, `s` + the closing delimiter inside a
 * string that may span lines.
 */
private class CodeLexer(
    keywords: String,
    types: String = "",
    constants: String = "true false null",
    builtins: String = "",
    private val lineComments: List<String> = listOf("//"),
    private val blockComment: Pair<String, String>? = "/*" to "*/",
    private val quotes: String = "\"'",
    /** Quotes whose strings may run over several lines, like JavaScript's template literals. */
    private val multilineQuotes: String = "",
    /** `"""` and `'''` open strings that may run over several lines. */
    private val tripleQuotes: Boolean = false,
    /** `'x'` is a character rather than a string, so a lone `'` (a Rust lifetime) is left alone. */
    private val charLiterals: Boolean = false,
    /** Lua's `[[ … ]]` strings. */
    private val longStrings: Boolean = false,
    /** Zig's `\\` strings, which run to the end of the line. */
    private val lineStringPrefix: String? = null,
    /** `@name` annotations and decorators, or `#name` preprocessor directives. */
    private val metaPrefix: Char? = null,
    /** Rust's `name!` macros. */
    private val macros: Boolean = false,
    private val ignoreCase: Boolean = false,
    /** A string followed by `:` is an object key, as in JSON. */
    private val keyStrings: Boolean = false,
) : SrcLexer {
    private val keywords = words(keywords)
    private val types = words(types)
    private val constants = words(constants)
    private val builtins = words(builtins)

    override fun lex(line: String, state: String?, out: SrcTokens): String? {
        val n = line.length
        var i = 0
        if (state != null) {
            val comment = state[0] == 'c'
            val close = state.substring(1)
            val kind = if (comment) OrgSpanKind.CodeComment else OrgSpanKind.CodeString
            val end = out.region(0, 0, close, kind, escapes = !comment && close != "]]")
            if (end < 0) return state
            i = end
        }
        while (i < n) {
            val c = line[i]
            val block = blockComment
            when {
                c.isWhitespace() -> i++

                block != null && line.startsWith(block.first, i) -> {
                    val end = out.region(i, i + block.first.length, block.second, OrgSpanKind.CodeComment, false)
                    if (end < 0) return "c" + block.second
                    i = end
                }

                lineComments.any { line.startsWith(it, i) } -> {
                    out.add(i, n, OrgSpanKind.CodeComment)
                    i = n
                }

                lineStringPrefix != null && line.startsWith(lineStringPrefix, i) -> {
                    out.add(i, n, OrgSpanKind.CodeString)
                    i = n
                }

                longStrings && line.startsWith("[[", i) -> {
                    val end = out.region(i, i + 2, "]]", OrgSpanKind.CodeString, false)
                    if (end < 0) return "s]]"
                    i = end
                }

                tripleQuotes && (line.startsWith("\"\"\"", i) || line.startsWith("'''", i)) -> {
                    val close = line.substring(i, i + 3)
                    val end = out.region(i, i + 3, close, OrgSpanKind.CodeString, true)
                    if (end < 0) return "s$close"
                    i = end
                }

                c == '\'' && charLiterals -> {
                    val end = findClose(line, i + 1, "'", true)
                    val length = end - i
                    if (end > 0 && (length <= 3 || (line[i + 1] == '\\' && length <= 12))) {
                        out.add(i, end, OrgSpanKind.CodeString)
                        i = end
                    } else {
                        i++
                    }
                }

                c in quotes -> {
                    val end = findClose(line, i + 1, c.toString(), true)
                    if (end < 0) {
                        out.add(i, n, OrgSpanKind.CodeString)
                        if (c in multilineQuotes) return "s$c"
                        i = n
                    } else {
                        var k = end
                        while (k < n && line[k] == ' ') k++
                        val isKey = keyStrings && k < n && line[k] == ':'
                        out.add(i, end, if (isKey) OrgSpanKind.CodeKey else OrgSpanKind.CodeString)
                        i = end
                    }
                }

                c.isDigit() && (i == 0 || !isIdentifierPart(line[i - 1])) -> {
                    var j = i + 1
                    while (j < n) {
                        val d = line[j]
                        val exponentSign = d in "+-" && line[j - 1] in "eE" && !line.startsWith("0x", i)
                        val fraction = d == '.' && j + 1 < n && line[j + 1].isDigit()
                        if (d.isLetterOrDigit() || d == '_' || fraction || exponentSign) j++ else break
                    }
                    out.add(i, j, OrgSpanKind.CodeNumber)
                    i = j
                }

                c == metaPrefix && i + 1 < n && isIdentifierStart(line[i + 1]) -> {
                    var j = i + 1
                    while (j < n && (isIdentifierPart(line[j]) || line[j] == '.')) j++
                    out.add(i, j, OrgSpanKind.CodeMeta)
                    i = j
                }

                isIdentifierStart(c) -> {
                    var j = i + 1
                    while (j < n && isIdentifierPart(line[j])) j++
                    val word = line.substring(i, j)
                    val key = if (ignoreCase) word.lowercase() else word
                    val isMacro = macros && j < n && line[j] == '!' && line.getOrNull(j + 1) != '='
                    val kind = when {
                        key in keywords -> OrgSpanKind.CodeKeyword
                        key in constants -> OrgSpanKind.CodeConstant
                        key in types -> OrgSpanKind.CodeType
                        isMacro -> OrgSpanKind.CodeBuiltin
                        key in builtins -> OrgSpanKind.CodeBuiltin
                        isCall(line, j) -> OrgSpanKind.CodeFunction
                        !ignoreCase && isAllCaps(word) -> OrgSpanKind.CodeConstant
                        !ignoreCase && word[0].isUpperCase() -> OrgSpanKind.CodeType
                        else -> null
                    }
                    if (isMacro) j++
                    if (kind != null) out.add(i, j, kind)
                    i = j
                }

                else -> i++
            }
        }
        return null
    }

    private fun isCall(line: String, from: Int): Boolean {
        var k = from
        while (k < line.length && line[k] == ' ') k++
        return k < line.length && line[k] == '('
    }

    private fun isAllCaps(word: String) =
        word.length > 1 && word.any { it.isLetter() } && word.all { it.isUpperCase() || it.isDigit() || it == '_' }

    private fun isIdentifierStart(c: Char) = c.isLetter() || c == '_' || c == '$'
    private fun isIdentifierPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
}

// --- Shell ---

private const val SHELL_KEYWORDS = "if then else elif fi case esac for select while until do done in function time coproc !"

private const val SHELL_BUILTINS = "alias bg bind break builtin cd command continue declare dirs disown echo enable " +
    "eval exec exit export fc fg getopts hash help history jobs kill let local logout popd printf pushd pwd " +
    "read readonly return set shift shopt source suspend test trap type typeset ulimit umask unalias unset " +
    "wait . [ [[ true false sudo env xargs nohup"

/** Keywords and commands whose next word is itself a command. */
private val leadsToCommand = words("if then else elif do while until time ! sudo exec env xargs nohup command builtin")

/** Characters that end a shell word. */
private const val SHELL_WORD_STOPS = ";|&()<>\"'`\${}"

/**
 * Shell, and Dockerfiles, which are shell behind an instruction.
 *
 * The command at the start of a line, or after `|`, `&&`, `;`, `$(` and the like, is what makes a
 * shell snippet readable, so it gets a colour of its own. States: `'` or `"` inside a string,
 * `heredoc:WORD` (or `heredoc-:WORD` for `<<-`) inside a heredoc, and `\` after a line continuation.
 */
private class ShellLexer(instructions: String = "") : SrcLexer {
    private val instructions = words(instructions)
    private val keywords = words(SHELL_KEYWORDS)
    private val builtins = words(SHELL_BUILTINS)

    override fun lex(line: String, state: String?, out: SrcTokens): String? {
        val n = line.length
        var i = 0
        if (state != null && state.startsWith("heredoc")) {
            val word = state.substringAfter(':')
            val text = if (state.startsWith("heredoc-")) line.trimStart('\t') else line
            out.add(0, n, OrgSpanKind.CodeString)
            return if (text == word) null else state
        }
        if (state == "'" || state == "\"") {
            val end = findClose(line, 0, state, escapes = state == "\"")
            val stop = if (end < 0) n else end
            out.add(0, stop, OrgSpanKind.CodeString)
            if (state == "\"") variablesIn(line, 0, stop, out)
            if (end < 0) return state
            i = end
        }
        var command = state == null
        var heredoc: String? = null

        if (state == null && instructions.isNotEmpty()) {
            while (i < n && line[i].isWhitespace()) i++
            var j = i
            while (j < n && line[j].isLetter()) j++
            val word = line.substring(i, j)
            if (word.uppercase() in instructions) {
                out.add(i, j, OrgSpanKind.CodeKeyword)
                command = word.uppercase() == "RUN"
                i = j
            }
        }

        while (i < n) {
            val c = line[i]
            when {
                c.isWhitespace() -> i++

                c == '#' && (i == 0 || line[i - 1].isWhitespace() || line[i - 1] in ";|&(") -> {
                    out.add(i, n, OrgSpanKind.CodeComment)
                    i = n
                }

                c == '\'' || c == '"' -> {
                    val end = findClose(line, i + 1, c.toString(), escapes = c == '"')
                    val stop = if (end < 0) n else end
                    out.add(i, stop, OrgSpanKind.CodeString)
                    if (c == '"') variablesIn(line, i + 1, stop, out)
                    if (end < 0) return c.toString()
                    // `NAME="value" command`: the assignment's value does not end the command position.
                    if (i == 0 || line[i - 1] != '=') command = false
                    i = end
                }

                line.startsWith("$((", i) -> {
                    command = false
                    i += 3
                }

                line.startsWith("$(", i) -> {
                    command = true
                    i += 2
                }

                c == '$' -> {
                    val end = variableEnd(line, i)
                    if (end > i + 1) {
                        out.add(i, end, OrgSpanKind.CodeVariable)
                        if (i == 0 || line[i - 1] != '=') command = false
                    }
                    i = end
                }

                line.startsWith("<<", i) && !line.startsWith("<<<", i) -> {
                    val dash = line.getOrNull(i + 2) == '-'
                    var j = if (dash) i + 3 else i + 2
                    while (j < n && line[j] == ' ') j++
                    val quote = line.getOrNull(j)?.takeIf { it == '\'' || it == '"' }
                    if (quote != null) j++
                    val wordStart = j
                    while (j < n && (line[j].isLetterOrDigit() || line[j] == '_' || line[j] == '-')) j++
                    val word = line.substring(wordStart, j)
                    if (quote != null && line.getOrNull(j) == quote) j++
                    if (word.isNotEmpty()) {
                        out.add(i, j, OrgSpanKind.CodeString)
                        heredoc = (if (dash) "heredoc-:" else "heredoc:") + word
                        i = j
                    } else {
                        i += 2
                    }
                }

                // A redirection: `2>&1` and `&>` name a file or a descriptor, not a new command.
                c == '<' || c == '>' -> i += if (line.getOrNull(i + 1) == '&') 2 else 1
                c == '&' && line.getOrNull(i + 1) == '>' -> i += 2

                c == ';' || c == '|' || c == '&' || c == '(' || c == '{' || c == '`' -> {
                    command = true
                    i++
                }

                c == ')' || c == '}' -> {
                    command = false
                    i++
                }

                c == '\\' -> i += 2

                else -> {
                    var j = i
                    while (j < n && !line[j].isWhitespace() && line[j] !in SHELL_WORD_STOPS) j++
                    val word = line.substring(i, j)
                    val equals = word.indexOf('=')
                    when {
                        command && equals > 0 && word.substring(0, equals).all { it.isLetterOrDigit() || it == '_' } ->
                            out.add(i, i + equals, OrgSpanKind.CodeVariable)

                        command && word.startsWith('-') -> {}

                        command -> {
                            val kind = when (word) {
                                in keywords -> OrgSpanKind.CodeKeyword
                                in builtins -> OrgSpanKind.CodeBuiltin
                                else -> OrgSpanKind.CodeFunction
                            }
                            out.add(i, j, kind)
                            command = word in leadsToCommand
                        }

                        word.all { it.isDigit() } -> out.add(i, j, OrgSpanKind.CodeNumber)
                    }
                    i = j
                }
            }
        }
        return when {
            heredoc != null -> heredoc
            line.trimEnd().endsWith('\\') -> "\\"
            else -> null
        }
    }

    private fun variableEnd(line: String, i: Int): Int {
        val next = line.getOrNull(i + 1) ?: return i + 1
        return when {
            next == '{' -> findClose(line, i + 2, "}", false).let { if (it < 0) line.length else it }
            next.isLetter() || next == '_' -> {
                var j = i + 2
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                j
            }
            next.isDigit() || next in "@*#?$!-" -> i + 2
            else -> i + 1
        }
    }

    /** `$name` inside a double-quoted string between [from] and [to]. */
    private fun variablesIn(line: String, from: Int, to: Int, out: SrcTokens) {
        var k = from
        while (k < to) {
            when (line[k]) {
                '\\' -> k += 2
                '$' -> {
                    val end = minOf(variableEnd(line, k), to)
                    if (end > k + 1) out.addNested(k, end, OrgSpanKind.CodeVariable, to)
                    k = maxOf(end, k + 1)
                }
                else -> k++
            }
        }
    }
}

// --- Lisp ---

private val lispKeywords = words(
    "defun defmacro defvar defvar-local defcustom defconst defgroup defface defsubst defalias " +
        "define-minor-mode define-derived-mode cl-defun cl-defmacro cl-defstruct defclass defmethod " +
        "defgeneric defstruct defpackage in-package lambda let let* letrec flet labels cl-flet cl-letf " +
        "if when unless cond case pcase pcase-let and or not progn prog1 prog2 setq setq-default setq-local " +
        "setf quote function while dolist dotimes loop cl-loop catch throw unwind-protect condition-case " +
        "ignore-errors save-excursion save-restriction save-match-data with-current-buffer " +
        "with-temp-buffer with-eval-after-load interactive require provide use-package declare " +
        "define defn defn- def fn ns begin set! do recur try " +
        // Doom Emacs
        "after! use-package! map! add-hook! remove-hook! setq-hook! defadvice! undefadvice! package! load! " +
        "custom-set-faces! custom-theme-set-faces! setq! set!",
)

/** Forms whose next symbol is the name being defined. */
private val lispDefiners = words(
    "defun defmacro defvar defvar-local defcustom defconst defgroup defface defsubst defalias " +
        "define-minor-mode define-derived-mode cl-defun cl-defmacro cl-defstruct defclass defmethod " +
        "defgeneric defstruct defpackage define defn defn- def use-package use-package! package! defadvice!",
)

private val lispConstants = words("t nil #t #f true false")

/** Characters that end a Lisp symbol. */
private const val LISP_SYMBOL_STOPS = "()[]\";'`,"

/**
 * Emacs Lisp, Common Lisp, Scheme and Clojure.
 *
 * As in Emacs, the head of a form is coloured only when it is a special form or a common macro, and
 * the name a definition introduces is coloured as a function; other calls stay plain. Telling a call
 * from an argument list or a `let` binding would take more than one line of context. States: `"`
 * inside a string, `|#` inside a block comment.
 */
private object LispLexer : SrcLexer {
    override fun lex(line: String, state: String?, out: SrcTokens): String? {
        val n = line.length
        var i = 0
        if (state != null) {
            val comment = state == "|#"
            val kind = if (comment) OrgSpanKind.CodeComment else OrgSpanKind.CodeString
            val end = out.region(0, 0, state, kind, escapes = !comment)
            if (end < 0) return state
            i = end
        }
        var head = false
        var defining = false
        while (i < n) {
            val c = line[i]
            when {
                c.isWhitespace() -> i++

                c == ';' -> {
                    out.add(i, n, OrgSpanKind.CodeComment)
                    i = n
                }

                c == '"' -> {
                    val end = out.region(i, i + 1, "\"", OrgSpanKind.CodeString, true)
                    if (end < 0) return "\""
                    head = false
                    defining = false
                    i = end
                }

                line.startsWith("#|", i) -> {
                    val end = out.region(i, i + 2, "|#", OrgSpanKind.CodeComment, false)
                    if (end < 0) return "|#"
                    i = end
                }

                c == '(' || c == '[' -> {
                    head = c == '('
                    i++
                }

                c == ')' || c == ']' -> {
                    head = false
                    i++
                }

                // An Emacs Lisp character: ?a, ?\n.
                c == '?' && i + 1 < n && (i == 0 || line[i - 1].isWhitespace() || line[i - 1] in "('`,") -> {
                    val end = minOf(n, if (line[i + 1] == '\\') i + 3 else i + 2)
                    out.add(i, end, OrgSpanKind.CodeConstant)
                    head = false
                    i = end
                }

                line.startsWith("#'", i) -> {
                    val end = symbolEnd(line, i + 2)
                    out.add(i, end, OrgSpanKind.CodeFunction)
                    head = false
                    i = end
                }

                // A quoted symbol is data; a quoted list is read on as usual.
                c == '\'' -> {
                    val end = symbolEnd(line, i + 1)
                    if (end > i + 1) {
                        out.add(i, end, OrgSpanKind.CodeConstant)
                        head = false
                    }
                    i = maxOf(end, i + 1)
                }

                c == '`' || c == ',' || c == '@' -> i++

                else -> {
                    val end = symbolEnd(line, i)
                    val symbol = line.substring(i, end)
                    val kind = when {
                        defining -> OrgSpanKind.CodeFunction
                        symbol.startsWith(':') && symbol.length > 1 -> OrgSpanKind.CodeConstant
                        symbol.startsWith('&') -> OrgSpanKind.CodeKeyword
                        symbol in lispConstants -> OrgSpanKind.CodeConstant
                        isNumber(symbol) -> OrgSpanKind.CodeNumber
                        head && symbol in lispKeywords -> OrgSpanKind.CodeKeyword
                        else -> null
                    }
                    if (kind != null) out.add(i, end, kind)
                    defining = head && symbol in lispDefiners
                    head = false
                    i = end
                }
            }
        }
        return null
    }

    private fun symbolEnd(line: String, from: Int): Int {
        var j = from
        while (j < line.length && !line[j].isWhitespace() && line[j] !in LISP_SYMBOL_STOPS) j++
        return j
    }
}

// --- Configuration files ---

private val configConstants = words("true false yes no on off null none ~")

/**
 * INI, TOML, `.conf` and properties files, and YAML: section headers, keys, and values.
 *
 * A key is what comes before `=` or `:` — or, for space-separated formats such as an SSH config, the
 * first word. Nothing spans lines, so there is no state.
 */
private class ConfigLexer(private val yaml: Boolean) : SrcLexer {
    override fun lex(line: String, state: String?, out: SrcTokens): String? {
        val n = line.length
        var i = 0
        while (i < n && line[i].isWhitespace()) i++
        if (i == n) return null
        val c = line[i]
        when {
            c == '#' || (!yaml && c == ';') -> {
                out.add(i, n, OrgSpanKind.CodeComment)
                return null
            }

            !yaml && c == '[' -> {
                val close = line.indexOf(']', i)
                val end = if (close < 0) n else close + 1
                out.add(i, end, OrgSpanKind.CodeTag)
                values(line, end, out)
                return null
            }

            yaml && (line.startsWith("---", i) || line.startsWith("...", i)) && line.substring(i + 3).isBlank() -> {
                out.add(i, i + 3, OrgSpanKind.CodeMeta)
                return null
            }
        }
        if (yaml) {
            while (i < n && line[i] == '-' && (i + 1 == n || line[i + 1] == ' ')) {
                i++
                while (i < n && line[i] == ' ') i++
            }
        }
        val keyEnd = if (yaml) yamlKeyEnd(line, i) else keyEnd(line, i)
        if (keyEnd > i) {
            out.add(i, keyEnd, OrgSpanKind.CodeKey)
            i = keyEnd
        }
        values(line, i, out)
        return null
    }

    private fun yamlKeyEnd(line: String, from: Int): Int {
        if (from >= line.length) return from
        val quote = line[from]
        if (quote == '"' || quote == '\'') {
            val end = findClose(line, from + 1, quote.toString(), quote == '"')
            return if (end > 0 && isColonAt(line, end)) end else from
        }
        var j = from
        while (j < line.length) {
            if (line[j] == '#' && j > from && line[j - 1].isWhitespace()) return from
            if (isColonAt(line, j)) return j
            j++
        }
        return from
    }

    private fun isColonAt(line: String, at: Int) =
        at < line.length && line[at] == ':' && (at + 1 == line.length || line[at + 1].isWhitespace())

    private fun keyEnd(line: String, from: Int): Int {
        var j = from
        while (j < line.length && line[j] != '=' && line[j] != ':') j++
        if (j < line.length) {
            val key = line.substring(from, j).trimEnd()
            if (key.isNotEmpty() && key.none { it.isWhitespace() }) return from + key.length
        }
        // Space-separated: `Host example.com`, `listen 80;`.
        var k = from
        while (k < line.length && (line[k].isLetterOrDigit() || line[k] in "_.-")) k++
        return if (k > from && k < line.length && line[k].isWhitespace()) k else from
    }

    private fun values(line: String, from: Int, out: SrcTokens) {
        val n = line.length
        var i = from
        while (i < n) {
            val c = line[i]
            when {
                (c == '#' || (!yaml && c == ';')) && (i == 0 || line[i - 1].isWhitespace()) -> {
                    out.add(i, n, OrgSpanKind.CodeComment)
                    return
                }

                c == '"' || c == '\'' -> {
                    val end = out.region(i, i + 1, c.toString(), OrgSpanKind.CodeString, c == '"')
                    i = if (end < 0) n else end
                }

                c == '$' && line.getOrNull(i + 1) == '{' -> {
                    val end = findClose(line, i + 2, "}", false).let { if (it < 0) n else it }
                    out.add(i, end, OrgSpanKind.CodeVariable)
                    i = end
                }

                c.isLetterOrDigit() || c in "+-." -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in "_.+-")) j++
                    val word = line.substring(i, j)
                    when {
                        isNumber(word) -> out.add(i, j, OrgSpanKind.CodeNumber)
                        word.lowercase() in configConstants -> out.add(i, j, OrgSpanKind.CodeConstant)
                    }
                    i = j
                }

                c == '~' && (i + 1 == n || line[i + 1].isWhitespace()) -> {
                    out.add(i, i + 1, OrgSpanKind.CodeConstant)
                    i++
                }

                else -> i++
            }
        }
    }
}

// --- XML and HTML ---

/**
 * Tags, attributes, attribute values, entities and comments. States: `<!--` inside a comment, `<`
 * inside a start tag whose attributes run onto the next line.
 */
private object MarkupLexer : SrcLexer {
    override fun lex(line: String, state: String?, out: SrcTokens): String? {
        val n = line.length
        var i = 0
        var inTag = state == "<"
        if (state == "<!--") {
            val end = out.region(0, 0, "-->", OrgSpanKind.CodeComment, false)
            if (end < 0) return state
            i = end
        }
        while (i < n) {
            val c = line[i]
            if (inTag) {
                when {
                    c.isWhitespace() -> i++

                    c == '>' -> {
                        out.add(i, i + 1, OrgSpanKind.CodeTag)
                        inTag = false
                        i++
                    }

                    line.startsWith("/>", i) || line.startsWith("?>", i) -> {
                        out.add(i, i + 2, OrgSpanKind.CodeTag)
                        inTag = false
                        i += 2
                    }

                    c == '"' || c == '\'' -> {
                        val end = out.region(i, i + 1, c.toString(), OrgSpanKind.CodeString, false)
                        i = if (end < 0) n else end
                    }

                    isNameChar(c) -> {
                        val end = nameEnd(line, i)
                        out.add(i, end, OrgSpanKind.CodeKey)
                        i = end
                    }

                    else -> i++
                }
                continue
            }
            when {
                line.startsWith("<!--", i) -> {
                    val end = out.region(i, i + 4, "-->", OrgSpanKind.CodeComment, false)
                    if (end < 0) return "<!--"
                    i = end
                }

                line.startsWith("<!", i) -> {
                    val end = out.region(i, i + 2, ">", OrgSpanKind.CodeMeta, false)
                    i = if (end < 0) n else end
                }

                c == '<' && i + 1 < n && (line[i + 1].isLetter() || line[i + 1] == '/' || line[i + 1] == '?') -> {
                    val end = nameEnd(line, if (line[i + 1].isLetter()) i + 1 else i + 2)
                    out.add(i, end, if (line[i + 1] == '?') OrgSpanKind.CodeMeta else OrgSpanKind.CodeTag)
                    inTag = true
                    i = end
                }

                c == '&' -> {
                    val semi = line.indexOf(';', i)
                    if (semi in (i + 2)..(i + 10) && line.substring(i + 1, semi).all { it.isLetterOrDigit() || it == '#' }) {
                        out.add(i, semi + 1, OrgSpanKind.CodeConstant)
                        i = semi + 1
                    } else {
                        i++
                    }
                }

                else -> i++
            }
        }
        return if (inTag) "<" else null
    }

    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c in "_:.-"

    private fun nameEnd(line: String, from: Int): Int {
        var j = from
        while (j < line.length && isNameChar(line[j])) j++
        return j
    }
}

// --- Languages ---

private const val C_KEYWORDS = "auto break case const continue default do else enum extern for goto if inline " +
    "register restrict return sizeof static struct switch typedef union volatile while"
private const val C_TYPES = "char double float int long short signed unsigned void bool size_t ssize_t " +
    "int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t"

private val cLanguage = CodeLexer(C_KEYWORDS, C_TYPES, constants = "true false NULL", metaPrefix = '#', charLiterals = true)

private val cpp = CodeLexer(
    "$C_KEYWORDS class namespace template typename public private protected virtual override new delete " +
        "try catch throw using operator friend this constexpr noexcept static_cast dynamic_cast " +
        "reinterpret_cast const_cast explicit mutable final",
    "$C_TYPES string vector map",
    constants = "true false nullptr NULL",
    metaPrefix = '#',
    charLiterals = true,
)

private val java = CodeLexer(
    "abstract assert break case catch class continue default do else enum extends final finally for if " +
        "implements import instanceof interface native new package private protected public return static " +
        "super switch synchronized this throw throws transient try volatile while var record yield sealed permits",
    "boolean byte char double float int long short void",
    metaPrefix = '@',
    tripleQuotes = true,
    charLiterals = true,
)

private val kotlin = CodeLexer(
    "as break class continue do else for fun if in interface is object package return super this throw try " +
        "typealias val var when while by catch constructor init companion data enum sealed open override " +
        "private protected public internal abstract final inline suspend lateinit import operator infix " +
        "vararg reified out finally",
    metaPrefix = '@',
    tripleQuotes = true,
    charLiterals = true,
)

private val csharp = CodeLexer(
    "abstract as base break case catch class const continue default delegate do else enum event explicit " +
        "extern finally fixed for foreach goto if implicit in interface internal is lock namespace new " +
        "operator out override params private protected public readonly ref return sealed sizeof stackalloc " +
        "static struct switch this throw try typeof unchecked unsafe using virtual volatile while async await " +
        "var get set init record yield where",
    "bool byte char decimal double float int long object sbyte short string uint ulong ushort void dynamic",
    metaPrefix = '#',
    charLiterals = true,
)

private val javascript = CodeLexer(
    "break case catch class const continue debugger default delete do else export extends finally for " +
        "function if import in instanceof let new return super switch this throw try typeof var void while " +
        "with yield async await of static get set from as interface type enum implements namespace declare " +
        "readonly private public protected abstract keyof",
    "string number boolean any unknown never object",
    constants = "true false null undefined NaN Infinity",
    quotes = "\"'`",
    multilineQuotes = "`",
    metaPrefix = '@',
)

private val go = CodeLexer(
    "break case chan const continue default defer else fallthrough for func go goto if import interface map " +
        "package range return select struct switch type var",
    "bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string uint uint8 " +
        "uint16 uint32 uint64 uintptr any",
    constants = "true false nil iota",
    builtins = "append cap close copy delete len make new panic print println recover",
    quotes = "\"'`",
    multilineQuotes = "`",
    charLiterals = true,
)

private val rust = CodeLexer(
    "as async await break const continue crate dyn else enum extern fn for if impl in let loop match mod move " +
        "mut pub ref return self Self static struct super trait type unsafe use where while",
    "i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str",
    constants = "true false",
    charLiterals = true,
    macros = true,
)

private val zig = CodeLexer(
    "addrspace align allowzero and anyframe anytype asm async await break callconv catch comptime const " +
        "continue defer else enum errdefer error export extern fn for if inline linksection noalias noinline " +
        "nosuspend opaque or orelse packed pub resume return struct suspend switch test threadlocal try union " +
        "unreachable usingnamespace var volatile while",
    "i8 u8 i16 u16 i32 u32 i64 u64 i128 u128 isize usize f16 f32 f64 f128 bool void noreturn type anyerror " +
        "comptime_int comptime_float c_int c_uint c_long",
    constants = "true false null undefined",
    blockComment = null,
    lineStringPrefix = "\\\\",
    metaPrefix = '@',
    charLiterals = true,
)

private val lua = CodeLexer(
    "and break do else elseif end for function goto if in local not or repeat return then until while",
    constants = "true false nil",
    builtins = "print pairs ipairs require type tostring tonumber setmetatable getmetatable error assert pcall " +
        "select table string math os io",
    lineComments = listOf("--"),
    blockComment = "--[[" to "]]",
    longStrings = true,
)

private val python = CodeLexer(
    "and as assert async await break class continue def del elif else except finally for from global if " +
        "import in is lambda nonlocal not or pass raise return try while with yield match case",
    constants = "True False None",
    builtins = "print len range str int float list dict set tuple open isinstance enumerate zip map filter " +
        "sorted super self",
    lineComments = listOf("#"),
    blockComment = null,
    tripleQuotes = true,
    metaPrefix = '@',
)

private val sql = CodeLexer(
    "select from where insert into values update set delete create table drop alter add column index view " +
        "join inner left right outer full cross on as and or not is in exists between like order by group " +
        "having limit offset union all distinct case when then else end primary key foreign references " +
        "default unique check constraint begin commit rollback transaction with returning if replace pragma",
    "int integer bigint smallint text varchar char boolean date timestamp real float double numeric " +
        "decimal blob serial",
    builtins = "count sum avg min max coalesce",
    lineComments = listOf("--"),
    ignoreCase = true,
)

private val json = CodeLexer("", keyStrings = true)

private val srcLexers: Map<String, SrcLexer> = buildMap {
    fun register(lexer: SrcLexer, vararg names: String) = names.forEach { put(it, lexer) }

    register(ShellLexer(), "sh", "bash", "shell", "zsh", "ksh", "fish", "console")
    register(
        ShellLexer(
            "FROM RUN CMD LABEL EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER WORKDIR ARG ONBUILD STOPSIGNAL " +
                "HEALTHCHECK SHELL MAINTAINER",
        ),
        "dockerfile", "docker",
    )
    register(LispLexer, "lisp", "elisp", "emacs-lisp", "common-lisp", "scheme", "clojure", "racket")
    register(cLanguage, "c", "h")
    register(cpp, "cpp", "c++", "cc", "hpp")
    register(java, "java")
    register(kotlin, "kotlin", "kt", "kts")
    register(csharp, "csharp", "cs", "c#")
    register(javascript, "javascript", "js", "typescript", "ts", "jsx", "tsx")
    register(go, "go", "golang")
    register(rust, "rust", "rs")
    register(zig, "zig")
    register(lua, "lua")
    register(python, "python", "py", "python3")
    register(sql, "sql", "sqlite", "postgresql", "mysql")
    register(json, "json")
    register(ConfigLexer(yaml = false), "conf", "config", "cfg", "ini", "toml", "properties", "desktop", "systemd")
    register(ConfigLexer(yaml = true), "yaml", "yml")
    register(MarkupLexer, "xml", "html", "svg")
}
