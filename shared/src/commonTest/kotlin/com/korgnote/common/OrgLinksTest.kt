package com.korgnote.common

import com.korgnote.common.org.complete.CompletionContext
import com.korgnote.common.org.complete.complete
import com.korgnote.common.org.link.OrgLinkIndex
import com.korgnote.common.org.link.OrgLinkResolution.External
import com.korgnote.common.org.link.OrgLinkResolution.NotANote
import com.korgnote.common.org.link.OrgLinkResolution.NotFound
import com.korgnote.common.org.link.OrgLinkResolution.Note
import com.korgnote.common.org.link.fileLinkPath
import com.korgnote.common.org.link.isNoteLink
import com.korgnote.common.org.link.relativeNotePath
import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.toOrgDocument
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Link resolution (T21), one rule per test, written against the shapes these notes actually use. */
class OrgLinksTest {

    private val house = """
        :PROPERTIES:
        :ID:       house-id
        :END:
        #+TITLE: House renovation

        * TODO [#A] Permit [1/2] :admin:
        :PROPERTIES:
        :ID:       permit-id
        :CUSTOM_ID: permit
        :END:
        Pay the fees <<the fees>>.
        * NASA plans
        #+NAME: budget
        | a | b |
    """.trimIndent() + "\n"

    private val daily = """
        * Today
        Worked on [[id:permit-id][the permit]], see [[file:../house.org][house]].
        Also [[file:~/org-roam/house.org::*Permit]] and [[Today]] and [[nowhere]].
    """.trimIndent() + "\n"

    private val notes = mapOf(
        "house.org" to house,
        "daily/2026-09-24.org" to daily,
        "daily/house.org" to "* A different house\n",
        "other.org" to "See [[https://example.com]] and [[file:~/Sync/facture.pdf][invoice]].\n",
    )
    private val index = OrgLinkIndex(notes)

    private fun resolve(link: String, from: String = "daily/2026-09-24.org") = index.resolve(link, from)

    @Test
    fun anIdLinkFindsTheHeadingThatDeclaresIt() {
        val found = assertIs<Note>(resolve("id:permit-id"))
        assertEquals("house.org", found.path)
        assertEquals(house.indexOf(":ID:       permit-id"), found.offset)
    }

    @Test
    fun anIdLinkCanNameAWholeFile() {
        assertEquals(Note("house.org", house.indexOf(":ID:       house-id")), resolve("id:house-id"))
    }

    @Test
    fun aRelativeFileLinkIsReadFromTheLinkingNotesFolder() {
        assertEquals(Note("house.org", null), resolve("file:../house.org"))
        assertEquals(Note("daily/house.org", null), resolve("file:house.org"), "not the one at the top")
    }

    @Test
    fun anAbsoluteFileLinkFromEmacsFindsTheNoteByItsLongestTail() {
        assertEquals(Note("house.org", null), resolve("file:~/org-roam/house.org"))
        assertEquals(Note("daily/house.org", null), resolve("file:~/org-roam/daily/house.org"))
        assertEquals(Note("daily/house.org", null), resolve("file:/home/me/org-roam/daily/house.org"))
    }

    @Test
    fun aFileLinkCanSearchInsideTheNote() {
        val permit = house.indexOf("* TODO [#A] Permit")
        assertEquals(Note("house.org", permit), resolve("file:../house.org::*Permit"))
        assertEquals(Note("house.org", house.indexOf(":CUSTOM_ID:")), resolve("file:../house.org::#permit"))
        assertEquals(Note("house.org", house.indexOf("#+TITLE")), resolve("file:../house.org::4"))
    }

    @Test
    fun headingSearchesIgnoreKeywordPriorityCookiesTagsAndCase() {
        val permit = house.indexOf("* TODO [#A] Permit")
        assertEquals(Note("house.org", permit), index.resolve("*permit", from = "house.org"))
        assertEquals(Note("house.org", permit), index.resolve("*TODO Permit", from = "house.org"))
    }

    @Test
    fun aPlainLinkIsOrgsFuzzyLinkIntoTheSameNote() {
        assertEquals(Note("house.org", house.indexOf("<<the fees>>")), index.resolve("the fees", from = "house.org"))
        assertEquals(Note("house.org", house.indexOf("#+NAME")), index.resolve("budget", from = "house.org"))
        assertEquals(
            Note("house.org", house.indexOf("* NASA plans")),
            index.resolve("NASA plans", from = "house.org"),
            "a capitalised first word is not lost to the keyword rule",
        )
    }

    @Test
    fun whatIsNotANoteSaysSo() {
        assertIs<External>(resolve("https://example.com"))
        assertIs<NotANote>(resolve("file:~/Sync/facture.pdf"))
        assertIs<NotANote>(resolve("kbd:C-c C-c"))
        assertIs<NotFound>(resolve("id:no-such-id"))
        assertIs<NotFound>(resolve("file:~/elsewhere/missing.org"))
        assertIs<NotFound>(resolve("nowhere"))
        assertIs<NotANote>(resolve("/Users/me/.config/emacs/init.el"), "a bare path to a non-note")
        assertIs<NotANote>(resolve("./linus.jpeg"))
        assertIs<NotANote>(resolve("Mu4e:msgid:abc@example.com"), "link types come in any case")
        assertIs<NotFound>(resolve("Note: see below"), "but a colon in prose is not one")
    }

    /** A heading renamed since the link was written: the note still opens, as Org opens the file. */
    @Test
    fun aFileLinkWhoseSearchNoLongerMatchesStillOpensTheNote() {
        assertEquals(
            Note("house.org", null, missedSearch = "*Renamed since"),
            resolve("file:~/org-roam/house.org::*Renamed since"),
        )
    }

    @Test
    fun backlinksListEveryLinkThatLeadsToANote() {
        val back = index.backlinksTo("house.org")

        assertEquals(3, back.size, back.toString())
        assertEquals(setOf("daily/2026-09-24.org"), back.map { it.path }.toSet())
        assertEquals("Today", back.first().context)
        assertEquals(daily.indexOf("[[id:permit-id]"), back.first().offset)
    }

    @Test
    fun aNoteLinkingIntoItselfIsABacklinkButAStrayPlainLinkIsNot() {
        val back = index.backlinksTo("daily/2026-09-24.org")
        assertEquals(listOf(daily.indexOf("[[Today]]")), back.map { it.offset })
    }

    @Test
    fun aNotesOwnIdIsTheOneAboveItsFirstHeading() {
        assertEquals("house-id", index.fileIdOf("house.org"))
        assertNull(index.fileIdOf("daily/2026-09-24.org"))
    }

    @Test
    fun relativePathsClimbOutOfTheLinkingNotesFolder() {
        assertEquals("../notes/b.org", relativeNotePath("notes/b.org", from = "daily/a.org"))
        assertEquals("b.org", relativeNotePath("notes/b.org", from = "notes/a.org"))
        assertEquals("notes/b.org", relativeNotePath("notes/b.org", from = "a.org"))
        assertEquals("../b.org", relativeNotePath("b.org", from = "daily/a.org"))
    }

    /** The two halves must agree: whatever completion writes, following it leads to that note. */
    @Test
    fun everyLinkCompletionWritesResolvesToTheNoteItOffered() {
        val from = "daily/2026-09-24.org"
        val context = CompletionContext(
            filePaths = notes.keys.sorted(),
            currentPath = from,
            noteIds = mapOf("house.org" to "house-id"),
            today = LocalDate(2026, 9, 24),
        )
        val items = complete("[[".toOrgDocument(), 2, context).filter { !it.label.startsWith("*") }
        assertEquals(notes.keys.sorted(), items.map { it.label })
        for (item in items) {
            val target = OrgSyntax.link.find(item.insert)!!.groupValues[1]
            val resolved = assertIs<Note>(index.resolve(target, from), item.insert)
            assertEquals(item.label, resolved.path, item.insert)
        }
    }

    @Test
    fun whatReadingModeMakesFollowableIsDecidedByShape() {
        for (link in listOf("id:x", "file:a.org", "file:~/org-roam/a.org::*H", "*Heading", "#custom", "Some heading", "./a.org")) {
            assertEquals(true, isNoteLink(link), link)
        }
        for (link in listOf("https://example.com", "mailto:a@b", "file:~/Sync/x.pdf", "kbd:C-c", "./linus.jpeg", "Mu4e:msgid:x")) {
            assertEquals(false, isNoteLink(link), link)
        }
    }

    @Test
    fun aLinkToAFileOutsideTheNotesNamesItsPath() {
        assertEquals("~/Sync/x.pdf", fileLinkPath("file:~/Sync/x.pdf"))
        assertEquals("/home/me/Sync/x.pdf", fileLinkPath("file+sys:/home/me/Sync/x.pdf::12"))
        assertEquals("~/Sync/photo.jpeg", fileLinkPath(" ~/Sync/photo.jpeg "))
        // Notes, web links and files beside the notes are not files this way.
        for (link in listOf("file:~/org-roam/a.org", "~/a.org", "https://example.com", "./linus.jpeg", "file:x.pdf", "kbd:C-c", "Plain")) {
            assertNull(fileLinkPath(link), link)
        }
    }
}
