package com.korgnote.common

import com.korgnote.common.org.search.SearchHit
import com.korgnote.common.org.search.SearchQuery
import com.korgnote.common.org.search.searchOrg
import com.korgnote.common.org.search.searchOrgFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Search over the replica (T14).
 *
 * Two properties are worth stating up front, because most of what follows checks one of them: a hit
 * locates itself by *character offset*, which is how the rest of the app identifies a place in a
 * document, and tags are inherited — a tag on a parent heading or in `#+FILETAGS:` belongs to
 * everything beneath it, exactly as in Org.
 */
class OrgSearchTests {

    private val work = """
        #+TITLE: Work

        * Projects                                       :work:
        ** TODO Send the invoice
           The invoice is due on Friday.
        ** TODO Book a room
        * Personal
        ** Groceries
           Buy milk and bread.
    """.trimIndent() + "\n"

    private val home = """
        #+TITLE: Home
        #+FILETAGS: :house:

        * Repairs
        ** TODO Fix the tap
           The tap in the kitchen drips.
    """.trimIndent() + "\n"

    private val corpus = mapOf("work.org" to work, "home.org" to home)

    @Test
    fun findsAWordInABodyLine() {
        val hits = searchOrg(corpus, "drips")
        assertEquals(1, hits.size)
        val hit = hits.single()
        assertEquals("home.org", hit.path)
        // The breadcrumb names headings as they read, without the TODO keyword.
        assertEquals(listOf("Repairs", "Fix the tap"), hit.breadcrumb)
        assertEquals(6, hit.line)
        assertEquals("   The tap in the kitchen drips.", hit.lineText)
    }

    @Test
    fun theOffsetPointsAtTheMatchInTheFile() {
        val hit = searchOrgFile("home.org", home, SearchQuery.parse("kitchen")).single()
        assertEquals("kitchen", home.substring(hit.offset, hit.offset + "kitchen".length))
        assertEquals("kitchen", hit.lineText.substring(hit.match))
    }

    @Test
    fun headingTitlesAreSearchedToo() {
        val hits = searchOrg(corpus, "invoice")
        // The heading and the body line beneath it, in document order.
        assertContentEquals(listOf(4, 5), hits.map { it.line })
        assertTrue(hits.all { it.path == "work.org" })
    }

    @Test
    fun everyWordHasToMatchAndOrderDoesNotMatter() {
        assertEquals(1, searchOrg(corpus, "invoice friday").size)
        assertEquals(1, searchOrg(corpus, "friday invoice").size)
        // "invoice" alone matches two lines; adding a word that is on neither leaves nothing.
        assertEquals(0, searchOrg(corpus, "invoice monday").size)
    }

    @Test
    fun aQuotedPhraseIsOneTerm() {
        assertEquals(listOf("the tap in the kitchen"), SearchQuery.parse("\"The tap in the kitchen\"").text)
        assertEquals(1, searchOrg(corpus, "\"tap in the kitchen\"").size)
        assertEquals(0, searchOrg(corpus, "\"kitchen tap\"").size)
    }

    @Test
    fun matchingIgnoresCase() {
        assertEquals(searchOrg(corpus, "invoice").size, searchOrg(corpus, "INVOICE").size)
        // Tags too: Org treats `:Work:` and `:work:` as the same tag, and so does this.
        assertEquals(searchOrg(corpus, "tag:house").size, searchOrg(corpus, "tag:HOUSE").size)
        assertTrue(searchOrg(corpus, "tag:HOUSE").isNotEmpty())
    }

    @Test
    fun aTagOnlyQueryReturnsTheHeadingsCarryingIt() {
        val hits = searchOrg(corpus, "tag:work")
        // The tagged heading and both of its children, which inherit the tag.
        assertContentEquals(
            listOf("Projects", "Send the invoice", "Book a room"),
            hits.map { it.breadcrumb.last() },
        )
        // An untagged sibling and its subtree are not results.
        assertTrue(hits.none { it.breadcrumb.contains("Personal") })
    }

    @Test
    fun tagsAreInheritedFromFiletags() {
        val hits = searchOrg(corpus, "tag:house")
        assertContentEquals(listOf("Repairs", "Fix the tap"), hits.map { it.breadcrumb.last() })
    }

    @Test
    fun aTagNarrowsAWordSearch() {
        // "the" appears in both files; the tag keeps only the one heading's subtree.
        assertTrue(searchOrg(corpus, "the").size > 1)
        val tagged = searchOrg(corpus, "tag:work the")
        assertTrue(tagged.isNotEmpty())
        assertTrue(tagged.all { it.path == "work.org" && it.breadcrumb.first() == "Projects" })
    }

    @Test
    fun orgShapedTagsAreAccepted() {
        assertEquals(SearchQuery(tags = listOf("work")), SearchQuery.parse(":work:"))
        assertEquals(SearchQuery(tags = listOf("work")), SearchQuery.parse("tag:work"))
        assertEquals(SearchQuery(tags = listOf("work", "home")), SearchQuery.parse(":work:home:"))
        // A lone colon, or one inside a word, is text to find — `SCHEDULED:` has to stay searchable.
        assertEquals(SearchQuery(text = listOf("scheduled:")), SearchQuery.parse("SCHEDULED:"))
        assertEquals(SearchQuery(text = listOf(":")), SearchQuery.parse(":"))
    }

    @Test
    fun aFileIsFoundByItsName() {
        val byName = searchOrg(corpus, "home").single { it.kind == SearchHit.Kind.PATH }
        assertEquals("home.org", byName.path)
        assertEquals(0, byName.offset)
        // Which is what makes the drawer's filename filter the degenerate case of this search.
        assertTrue(searchOrg(corpus, "work").any { it.path == "work.org" && it.kind == SearchHit.Kind.PATH })
    }

    @Test
    fun aHeadingInsideASourceBlockIsNotAHeading() {
        val text = """
            * Real heading                                :work:
              #+BEGIN_SRC org
              * Not a heading
              #+END_SRC
        """.trimIndent() + "\n"
        val hit = searchOrgFile("blocks.org", text, SearchQuery.parse("Not a heading")).single()
        assertContentEquals(listOf("Real heading"), hit.breadcrumb)
        // It is inside the tagged heading, so the tag reaches it.
        assertEquals(1, searchOrgFile("blocks.org", text, SearchQuery.parse("tag:work \"not a heading\"")).size)
    }

    @Test
    fun linesAreNumberedAcrossSkippedElements() {
        // "Buy milk" is in the last element of the file; the tagged elements before it are skipped
        // for a tag query but must still count towards the line number here.
        val hit = searchOrgFile("work.org", work, SearchQuery.parse("milk")).single()
        assertEquals(work.lineSequence().indexOfFirst { it.contains("milk") } + 1, hit.line)
    }

    @Test
    fun anEmptyQueryFindsNothing() {
        assertTrue(SearchQuery.parse("   ").isEmpty)
        assertEquals(0, searchOrg(corpus, "   ").size)
    }

    @Test
    fun theLimitCapsTheResults() {
        assertEquals(2, searchOrg(corpus, "the", limit = 2).size)
        // Counted over everything returned, including the file matched by its name: "work" matches
        // work.org's name and three of its lines, so a limit of 3 returns three hits, not four.
        assertEquals(3, searchOrgFile("work.org", work, SearchQuery.parse("work"), limit = 3).size)
    }

    @Test
    fun aCorpusSizedSearchIsWellUnderASecond() {
        // ~4 MB, larger than the real notes directory, built so the match is in the last file.
        val file = (1..400).joinToString("\n") { "* Heading $it :bulk:\n  Line $it of filler text.\n" }
        val many = (1..250).associate { "notes/file-$it.org" to file } + ("zzz.org" to "* Needle\n  findme\n")
        val elapsed = measureTime {
            val hits = searchOrg(many, "findme")
            assertEquals(1, hits.size)
        }
        assertTrue(elapsed.inWholeMilliseconds < 1000, "corpus search took $elapsed")
    }
}
