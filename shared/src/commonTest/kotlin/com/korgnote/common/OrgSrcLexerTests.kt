package com.korgnote.common

import com.korgnote.common.org.OrgLineState
import com.korgnote.common.org.OrgSpanKind
import com.korgnote.common.org.isHighlightedSrcLanguage
import com.korgnote.common.org.orgSpans
import com.korgnote.common.org.orgSpansForLine
import com.korgnote.common.org.srcBlockSpans
import com.korgnote.common.org.srcLanguageOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Src block bodies are classified by the language the block names. */
class OrgSrcLexerTests {

    /** The code kinds covering the [occurrence]th [token] in [text]. */
    private fun kindsAt(text: String, token: String, language: String, occurrence: Int = 0): List<OrgSpanKind> {
        var at = -1
        repeat(occurrence + 1) { at = text.indexOf(token, at + 1) }
        require(at >= 0) { "token <$token> not in text" }
        return srcBlockSpans(text, language).filter { at in it.range }.map { it.kind }
    }

    private fun assertKind(kind: OrgSpanKind, text: String, token: String, language: String, occurrence: Int = 0) {
        val kinds = kindsAt(text, token, language, occurrence)
        assertTrue(kind in kinds, "<$token> should be $kind in $language, was $kinds")
    }

    private fun assertPlain(text: String, token: String, language: String) {
        val kinds = kindsAt(text, token, language)
        assertTrue(kinds.isEmpty(), "<$token> should be plain in $language, was $kinds")
    }

    @Test
    fun theLanguageIsTheFirstParameter() {
        assertEquals("bash", srcLanguageOf(" bash :results output"))
        assertEquals("emacs-lisp", srcLanguageOf("Emacs-Lisp"))
        assertNull(srcLanguageOf(""))
        assertNull(srcLanguageOf(" :results output"))
        assertTrue(isHighlightedSrcLanguage("elisp"))
        assertTrue(!isHighlightedSrcLanguage("brainfuck"))
    }

    @Test
    fun shellCommandsVariablesStringsAndComments() {
        val text = "if [ -f \"\$HOME/x\" ]; then\n  git log --oneline | grep fix # recent\nfi\nNAME=world echo \"hi \$NAME\" a#b"
        val sh = "bash"
        assertKind(OrgSpanKind.CodeKeyword, text, "if", sh)
        assertKind(OrgSpanKind.CodeBuiltin, text, "[", sh)
        assertKind(OrgSpanKind.CodeString, text, "\"\$HOME", sh)
        assertKind(OrgSpanKind.CodeVariable, text, "\$HOME", sh)
        assertKind(OrgSpanKind.CodeKeyword, text, "then", sh)
        assertKind(OrgSpanKind.CodeFunction, text, "git", sh)
        assertPlain(text, "log", sh)
        assertPlain(text, "--oneline", sh)
        assertKind(OrgSpanKind.CodeFunction, text, "grep", sh)
        assertKind(OrgSpanKind.CodeComment, text, "# recent", sh)
        assertKind(OrgSpanKind.CodeKeyword, text, "fi\n", sh)
        assertKind(OrgSpanKind.CodeVariable, text, "NAME", sh)
        assertKind(OrgSpanKind.CodeBuiltin, text, "echo", sh)
        assertKind(OrgSpanKind.CodeVariable, text, "\$NAME", sh)
        // A # inside a word does not start a comment.
        assertPlain(text, "a#b", sh)
    }

    @Test
    fun shellRedirectionsDoNotStartACommand() {
        val text = "make 2>&1 | tee log && echo done"
        assertKind(OrgSpanKind.CodeFunction, text, "make", "sh")
        assertKind(OrgSpanKind.CodeNumber, text, "1", "sh")
        assertKind(OrgSpanKind.CodeFunction, text, "tee", "sh")
        assertKind(OrgSpanKind.CodeBuiltin, text, "echo", "sh")
    }

    @Test
    fun shellStringsAndHeredocsRunOverLines() {
        val text = "echo 'one\ntwo' done\ncat <<EOF\nif this\nEOF\nls"
        assertKind(OrgSpanKind.CodeString, text, "two'", "sh")
        assertKind(OrgSpanKind.CodeString, text, "<<EOF", "sh")
        assertKind(OrgSpanKind.CodeString, text, "if this", "sh")
        assertKind(OrgSpanKind.CodeFunction, text, "ls", "sh")
    }

    @Test
    fun emacsLisp() {
        val text = "(defun my-fn (x &optional y)\n  \"Doc.\"\n  (when (> x 1) ; big\n    (message \"%s\" 'sym :key t #'car ?a)))"
        val el = "emacs-lisp"
        assertKind(OrgSpanKind.CodeKeyword, text, "defun", el)
        assertKind(OrgSpanKind.CodeFunction, text, "my-fn", el)
        assertPlain(text, "x ", el)
        assertKind(OrgSpanKind.CodeKeyword, text, "&optional", el)
        assertKind(OrgSpanKind.CodeString, text, "\"Doc.\"", el)
        assertKind(OrgSpanKind.CodeKeyword, text, "when", el)
        // Calls stay plain, as in Emacs: only special forms, macros and defined names are coloured.
        assertPlain(text, ">", el)
        assertKind(OrgSpanKind.CodeNumber, text, "1", el)
        assertKind(OrgSpanKind.CodeComment, text, "; big", el)
        assertPlain(text, "message", el)
        assertKind(OrgSpanKind.CodeConstant, text, "'sym", el)
        assertKind(OrgSpanKind.CodeConstant, text, ":key", el)
        assertKind(OrgSpanKind.CodeConstant, text, "t ", el)
        assertKind(OrgSpanKind.CodeFunction, text, "#'car", el)
        assertKind(OrgSpanKind.CodeConstant, text, "?a", el)
    }

    @Test
    fun doomEmacsMacros() {
        val text = "(after! org\n  (use-package! ox-extra :config (ox-extras-activate)))"
        assertKind(OrgSpanKind.CodeKeyword, text, "after!", "emacs-lisp")
        assertKind(OrgSpanKind.CodeKeyword, text, "use-package!", "emacs-lisp")
        assertKind(OrgSpanKind.CodeFunction, text, "ox-extra", "emacs-lisp")
    }

    @Test
    fun aLispStringRunsOverLines() {
        val text = "(setq doc \"first\n(not code)\")\n(foo)"
        assertKind(OrgSpanKind.CodeString, text, "(not code)", "elisp")
        assertPlain(text, "foo", "elisp")
        assertKind(OrgSpanKind.CodeKeyword, text, "setq", "elisp")
    }

    @Test
    fun java() {
        val text = "@Override\npublic static int count(List<String> xs) {\n  /* many\n  lines */ return xs.size() + 0x1F; // done\n}"
        assertKind(OrgSpanKind.CodeMeta, text, "@Override", "java")
        assertKind(OrgSpanKind.CodeKeyword, text, "public", "java")
        assertKind(OrgSpanKind.CodeType, text, "int", "java")
        assertKind(OrgSpanKind.CodeFunction, text, "count", "java")
        assertKind(OrgSpanKind.CodeType, text, "List", "java")
        assertKind(OrgSpanKind.CodeComment, text, "lines */", "java")
        assertKind(OrgSpanKind.CodeKeyword, text, "return", "java")
        assertKind(OrgSpanKind.CodeFunction, text, "size", "java")
        assertKind(OrgSpanKind.CodeNumber, text, "0x1F", "java")
        assertKind(OrgSpanKind.CodeComment, text, "// done", "java")
    }

    @Test
    fun otherCodeLanguages() {
        assertKind(OrgSpanKind.CodeKeyword, "local t = {} -- note", "local", "lua")
        assertKind(OrgSpanKind.CodeComment, "local t = {} -- note", "-- note", "lua")
        assertKind(OrgSpanKind.CodeComment, "--[[ a\nb ]] x", "b ]]", "lua")
        assertKind(OrgSpanKind.CodeKeyword, "const std = @import(\"std\");", "const", "zig")
        assertKind(OrgSpanKind.CodeMeta, "const std = @import(\"std\");", "@import", "zig")
        assertKind(OrgSpanKind.CodeString, "const s =\n    \\\\ raw text\n;", "raw text", "zig")
        assertKind(OrgSpanKind.CodeKeyword, "SELECT name FROM t WHERE id = 'x'", "FROM", "sql")
        assertKind(OrgSpanKind.CodeString, "SELECT name FROM t WHERE id = 'x'", "'x'", "sql")
        assertKind(OrgSpanKind.CodeKeyword, "public sealed class Foo { }", "sealed", "csharp")
        assertKind(OrgSpanKind.CodeBuiltin, "println!(\"{}\", x);", "println!", "rust")
        // A lifetime is not a character literal.
        assertPlain("fn f<'a>(x: &'a str)", "'a>", "rust")
        assertKind(OrgSpanKind.CodeKey, "{\"name\": \"x\", \"n\": 1}", "\"name\"", "json")
        assertKind(OrgSpanKind.CodeString, "{\"name\": \"x\", \"n\": 1}", "\"x\"", "json")
        assertKind(OrgSpanKind.CodeString, "s = \"\"\"doc\nmore\"\"\"\nx", "more", "python")
    }

    @Test
    fun configurationFiles() {
        val ini = "[core]\n  editor = vim ; inline\nport=8080\n# comment\nHost example.com"
        assertKind(OrgSpanKind.CodeTag, ini, "[core]", "conf")
        assertKind(OrgSpanKind.CodeKey, ini, "editor", "conf")
        assertPlain(ini, "vim", "conf")
        assertKind(OrgSpanKind.CodeComment, ini, "; inline", "conf")
        assertKind(OrgSpanKind.CodeNumber, ini, "8080", "conf")
        assertKind(OrgSpanKind.CodeComment, ini, "# comment", "conf")
        assertKind(OrgSpanKind.CodeKey, ini, "Host", "conf")

        val yaml = "---\nservices:\n  - name: web # main\n    debug: true\n    url: http://x:80"
        assertKind(OrgSpanKind.CodeMeta, yaml, "---", "yaml")
        assertKind(OrgSpanKind.CodeKey, yaml, "services", "yaml")
        assertKind(OrgSpanKind.CodeKey, yaml, "name", "yaml")
        assertKind(OrgSpanKind.CodeComment, yaml, "# main", "yaml")
        assertKind(OrgSpanKind.CodeConstant, yaml, "true", "yaml")
        assertKind(OrgSpanKind.CodeKey, yaml, "url", "yaml")
        assertPlain(yaml, "http", "yaml")
    }

    @Test
    fun markup() {
        val text = "<?xml version=\"1.0\"?>\n<a href=\"x\"\n   class='y'>A &amp; B</a>\n<!-- c\nd -->"
        assertKind(OrgSpanKind.CodeMeta, text, "<?xml", "xml")
        assertKind(OrgSpanKind.CodeTag, text, "<a", "xml")
        assertKind(OrgSpanKind.CodeKey, text, "href", "xml")
        assertKind(OrgSpanKind.CodeString, text, "\"x\"", "xml")
        assertKind(OrgSpanKind.CodeKey, text, "class", "xml")
        assertPlain(text, "A ", "xml")
        assertKind(OrgSpanKind.CodeConstant, text, "&amp;", "xml")
        assertKind(OrgSpanKind.CodeTag, text, "</a", "xml")
        assertKind(OrgSpanKind.CodeComment, text, "d -->", "xml")
    }

    @Test
    fun anUnknownLanguageHasNoTokens() {
        assertTrue(srcBlockSpans("if x then y", "brainfuck").isEmpty())
    }

    @Test
    fun theDocumentScanUsesTheBlockLanguage() {
        val text = "#+BEGIN_SRC bash :results output\necho \$HOME\n#+END_SRC\necho plain\n"
        val spans = orgSpans(text)
        fun kinds(token: String) = text.indexOf(token).let { at -> spans.filter { at in it.range }.map { it.kind } }
        assertTrue(OrgSpanKind.BlockBodyMonospace in kinds("echo \$HOME"))
        assertTrue(OrgSpanKind.CodeBuiltin in kinds("echo \$HOME"))
        assertTrue(OrgSpanKind.CodeVariable in kinds("\$HOME"))
        assertTrue(kinds("echo plain").none { it.name.startsWith("Code") }, "the block's language ends with it")
    }

    @Test
    fun onlySrcBlocksAreLexed() {
        val text = "#+BEGIN_EXAMPLE bash\necho x\n#+END_EXAMPLE\n"
        assertTrue(orgSpans(text).none { it.kind.name.startsWith("Code") })
    }

    /** The editor's cache reuses a line only when its incoming state matches, so the lexer's must be in it. */
    @Test
    fun anOpenCommentIsPartOfTheLineState() {
        val state = OrgLineState()
        orgSpansForLine("#+BEGIN_SRC java", state)
        val before = state.copy()
        orgSpansForLine("/* open", state)
        assertTrue(state != before, "an open comment must change the state")
        val inComment = orgSpansForLine("still comment", state)
        assertTrue(inComment.any { it.kind == OrgSpanKind.CodeComment })
        orgSpansForLine("#+END_SRC", state)
        assertEquals(OrgLineState(), state)
    }

    /** Leaving spans out of a line must not change the state it hands on. */
    @Test
    fun advancingWithoutClassifyingKeepsTheState() {
        val lines = listOf("#+BEGIN_SRC sh", "cat <<EOF", "x", "EOF", "echo 'a", "b'", "#+END_SRC")
        val classified = OrgLineState()
        val skipped = OrgLineState()
        for (line in lines) {
            orgSpansForLine(line, classified)
            orgSpansForLine(line, skipped, classify = false)
            assertEquals(classified, skipped, "after <$line>")
        }
    }

    /**
     * No stretch of a code line may be covered by a distinct set of spans while holding only
     * whitespace: Skia measures such a run as zero width. See `OrgWhitespaceRunTest`.
     */
    @Test
    fun tokensLeaveNoWhitespaceOnlyRuns() {
        val samples = mapOf(
            "bash" to "  if [ -n \"\$A \$B\" ];  then  echo   \$X  # c  \nNAME=1  make  ",
            "elisp" to "  (defun  f  (x)  \"s\"  'q  :k)  ; c",
            "java" to "    public  static  int  f(int  x)  {  return  1 ;  }  // c ",
            "yaml" to "  key:   value  # c\n  - a:  1",
            "conf" to "  [s]  \n  k  =  v  ; c",
            "xml" to "  <a  href=\"x\"  >  t  </a>  ",
        )
        for ((language, body) in samples) {
            for (line in body.split('\n')) {
                val spans = srcBlockSpans(line, language)
                val boundaries = (spans.flatMap { listOf(it.range.first, it.range.last + 1) } + 0 + line.length)
                    .distinct().sorted()
                val bad = boundaries.zipWithNext()
                    .map { (start, end) -> line.substring(start, end) }
                    .filter { it.isNotEmpty() && it.isBlank() }
                assertTrue(bad.isEmpty(), "whitespace-only run(s) $bad in $language: <$line>")
            }
        }
    }

    @Test
    fun everySpanIsInBounds() {
        val samples = mapOf(
            "bash" to "echo \"\${x\"\ncat <<'E'\n\$(", "elisp" to "(a \"b\n?", "java" to "x /* \n'\\u0041' 1e-",
            "lua" to "[[ x", "yaml" to "\"k\": ", "xml" to "<a b='", "zig" to "\\\\", "json" to "\"",
        )
        for ((language, body) in samples) {
            for (span in srcBlockSpans(body, language)) {
                assertTrue(span.range.first >= 0 && span.range.last < body.length, "$span out of bounds in $language")
                assertTrue(!span.range.isEmpty(), "empty $span in $language")
            }
        }
    }
}
