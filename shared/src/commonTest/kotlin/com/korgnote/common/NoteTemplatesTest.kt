package com.korgnote.common

import com.korgnote.common.org.link.OrgLinkIndex
import com.korgnote.common.org.template.DEFAULT_EXTRA_HEADER
import com.korgnote.common.org.template.dailyFileName
import com.korgnote.common.org.template.newNoteText
import com.korgnote.common.org.template.parseTypedTags
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class NoteTemplatesTest {

    private val date = LocalDate(2026, 10, 26)

    @Test
    fun aNoteStartsTheWayOrgRoamWritesOne() {
        val text = newNoteText(
            id = "6d4a3005-6ac4-48b2-8751-0cdb35e4fd93",
            title = " Agenda ",
            date = date,
            author = "Jean-Guillaume Louis",
            tags = listOf("work", "ecam"),
            extraHeader = DEFAULT_EXTRA_HEADER,
        )
        assertEquals(
            """
            :PROPERTIES:
            :ID:       6d4a3005-6ac4-48b2-8751-0cdb35e4fd93
            :END:
            #+TITLE: Agenda
            #+AUTHOR: Jean-Guillaume Louis
            #+DATE: [2026-10-26 Mon]
            #+filetags: :work:ecam:
            #+TODO: TODO(t) DOING(p!) WAIT(w@/!) | DONE(d!) CANCELED(c@/!)
            #+TODO: EVENT(e) | FINISHED(f)


            """.trimIndent(),
            text,
        )
    }

    @Test
    fun aBlankAuthorAndNoTagsLeaveTheirLinesOut() {
        val text = newNoteText(id = "x", title = "T", date = date)
        assertEquals(":PROPERTIES:\n:ID:       x\n:END:\n#+TITLE: T\n#+DATE: [2026-10-26 Mon]\n\n", text)
    }

    @Test
    fun theNotesOwnIdIsWhatLinksFindItBy() {
        val text = newNoteText(id = "abc-123", title = "T", date = date)
        assertEquals("abc-123", OrgLinkIndex(mapOf("t.org" to text)).fileIdOf("t.org"))
    }

    @Test
    fun typedTagsAreSplitAndCleaned() {
        assertEquals(listOf("work", "ecam", "a_b"), parseTypedTags(" work, :ecam: a_b work  "))
        assertEquals(listOf("éte"), parseTypedTags("é/te"))
    }

    @Test
    fun aDailyIsNamedByItsDate() {
        assertEquals("2026-10-26.org", dailyFileName(date))
    }
}
