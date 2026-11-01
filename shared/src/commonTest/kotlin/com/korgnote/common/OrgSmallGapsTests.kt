package com.korgnote.common

import com.korgnote.common.org.OrgLatexEnvironment
import com.korgnote.common.org.OrgRaw
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgSpanKind
import com.korgnote.common.org.link.OrgLinkIndex
import com.korgnote.common.org.link.OrgLinkResolution
import com.korgnote.common.org.orgSpans
import com.korgnote.common.org.parseOrgTable
import com.korgnote.common.org.search.SearchHit
import com.korgnote.common.org.search.SearchQuery
import com.korgnote.common.org.search.searchOrg
import com.korgnote.common.org.toAlignedLines
import com.korgnote.common.org.toOrgDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The smaller Org gaps closed after the syntax review: links, cookies, tables, LaTeX, search. */
class OrgSmallGapsTests {

    // --- Links ---

    @Test
    fun aHeadingLinkFindsACommentedHeadingByItsTitle() {
        val text = "* Intro\n* COMMENT Draft ideas\nBody.\n"
        val index = OrgLinkIndex(mapOf("a.org" to text))
        assertEquals(OrgLinkResolution.Note("a.org", text.indexOf("* COMMENT")), index.resolve("*Draft ideas", "a.org"))
    }

    @Test
    fun aLinkAbbreviationExpandsAsOrgDoes() {
        val text = """
            #+LINK: gh https://github.com/%s
            #+LINK: wiki https://en.wikipedia.org/wiki/
            #+LINK: q https://example.com/search?q=%h
            #+LINK: notes file:notes/%s.org
            * Links
        """.trimIndent()
        val index = OrgLinkIndex(mapOf("a.org" to text, "notes/b.org" to "* B\n"))
        assertEquals(OrgLinkResolution.External("https://github.com/me/repo"), index.resolve("gh:me/repo", "a.org"))
        assertEquals(OrgLinkResolution.External("https://en.wikipedia.org/wiki/Org-mode"), index.resolve("wiki:Org-mode", "a.org"))
        assertEquals(OrgLinkResolution.External("https://example.com/search?q=a%20b%26c"), index.resolve("q:a b&c", "a.org"))
        assertEquals(OrgLinkResolution.Note("notes/b.org", null), index.resolve("notes:b", "a.org"))
        // Another note does not see this one's abbreviations.
        assertIs<OrgLinkResolution.NotANote>(OrgLinkIndex(mapOf("a.org" to text, "c.org" to "* C\n")).resolve("gh:x", "c.org"))
    }

    @Test
    fun aDoiLinkOpensAtTheResolver() {
        assertEquals(
            OrgLinkResolution.External("https://doi.org/10.1000/xyz123"),
            OrgLinkIndex(emptyMap()).resolve("doi:10.1000/xyz123", "a.org"),
        )
    }

    // --- Statistics cookies ---

    @Test
    fun anEmptyCookieIsHighlightedAsACookie() {
        for (cookie in listOf("[/]", "[%]", "[1/3]", "[33%]")) {
            val text = "- Things $cookie"
            val span = orgSpans(text).singleOrNull { it.kind == OrgSpanKind.Statistics }
            assertEquals(cookie, span?.let { text.substring(it.range) }, cookie)
        }
    }

    // --- Tables ---

    @Test
    fun aWidthCookieIsNotTextWhenAligning() {
        // A number, a word and a `<6>`: half the column's text is numbers, so it is right-aligned.
        // Counted as text, the cookie would have tipped it left.
        val aligned = parseOrgTable(listOf("| <6> |", "| 1 |", "| a |")).toAlignedLines()
        assertEquals(listOf("| <6> |", "|   1 |", "|   a |"), aligned)
    }

    // --- LaTeX environments ---

    @Test
    fun aLatexEnvironmentIsKeptVerbatim() {
        val text = "Before.\n\\begin{equation}\n  a^2 + b^2 = c^2\n\\end{equation}\nAfter.\n"
        val doc = text.toOrgDocument()
        assertIs<OrgRaw>(doc.children[0])
        val env = assertIs<OrgLatexEnvironment>(doc.children[1])
        assertEquals("equation", env.name)
        assertEquals("\\begin{equation}\n  a^2 + b^2 = c^2\n\\end{equation}\n", text.substring(env.range))
        assertIs<OrgRaw>(doc.children[2])
    }

    @Test
    fun anUnclosedLatexEnvironmentIsText() {
        val doc = "\\begin{equation}\nno end\n* Heading\n".toOrgDocument()
        assertIs<OrgRaw>(doc.children[0])
        assertIs<OrgSection>(doc.children[1])
    }

    // --- Search ---

    private val notes = mapOf(
        "work.org" to """
            #+TODO: TODO NEXT WAIT | DONE
            #+TAGS: [ work : @office @remote ] [ @remote : @home ]
            * NEXT [#A] Write the report :@office:
            * WAIT Call the plumber :@home:
            * TODO [#B] Plan :errand:
        """.trimIndent(),
    )

    private fun titles(query: String) = searchOrg(notes, query).filter { it.kind == SearchHit.Kind.HEADING }
        .map { it.breadcrumb.last() }

    @Test
    fun aGroupTagFindsItsMembers() {
        assertEquals(listOf("Write the report", "Call the plumber"), titles("tag:work"))
        assertEquals(listOf("Call the plumber"), titles(":@remote:"))
        assertEquals(listOf("Plan"), titles("tag:errand"))
    }

    @Test
    fun todoAndPriorityTermsMatchTheHeadingItself() {
        assertEquals(listOf("Write the report"), titles("todo:next"))
        assertEquals(listOf("Plan"), titles("priority:B"))
        assertEquals(listOf("Write the report"), titles("todo:NEXT priority:a"))
        assertEquals(emptyList(), titles("todo:DONE"))
    }

    @Test
    fun todoAndPriorityTermsParse() {
        assertEquals(SearchQuery(todo = listOf("next")), SearchQuery.parse("todo:NEXT"))
        assertEquals(SearchQuery(priority = listOf('A')), SearchQuery.parse("priority:a"))
        // Not a keyword or a priority: words to find.
        assertEquals(SearchQuery(text = listOf("todo:")), SearchQuery.parse("todo:"))
        assertEquals(SearchQuery(text = listOf("priority:high")), SearchQuery.parse("priority:high"))
    }

    @Test
    fun aKeywordTermWithWordsFindsLinesUnderThatHeading() {
        val hits = searchOrg(mapOf("a.org" to "* TODO Shop\nmilk\n* Notes\nmilk\n"), "todo:TODO milk")
        assertEquals(listOf("Shop"), hits.map { it.breadcrumb.last() })
        assertTrue(hits.all { it.kind == SearchHit.Kind.LINE })
    }
}
