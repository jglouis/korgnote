package com.korgnote.app.ui

import com.korgnote.common.org.orgElements
import com.korgnote.common.org.toOrgDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What reading mode works out before drawing: the byline, how deep each heading is, a sync's age. */
class ReadingDetailsTest {

    @Test
    fun theAuthorAndDateBecomeOneByline() {
        val document = "#+TITLE: Agenda\n#+AUTHOR: Jean\n#+DATE: [2026-10-27 Tue]\n#+filetags: :work:\n".toOrgDocument()
        val byline = noteBylineOf(document)!!
        assertEquals("Jean", byline.author)
        assertEquals("Tue 27 Oct 2026", byline.date)
        assertEquals(setOf(1, 2), byline.indices)
        assertNull(noteBylineOf("#+TITLE: Only a title\n".toOrgDocument()))
    }

    @Test
    fun aDateIsWrittenOutAndOneOrgCannotReadIsShownAsWritten() {
        assertEquals("Sat 3 Jan 2026 09:30", readableDate("<2026-01-03 Sat 09:30>"))
        assertEquals("last spring", readableDate("last spring"))
    }

    @Test
    fun aSubHeadingIsAsDeepAsTheHeadingsItSitsUnder() {
        val text = "Preamble\n* A\n** B\n**** D, a skipped level\n*** C\n* E\n"
        assertEquals(listOf(0, 0, 1, 2, 2, 0), text.orgElements().nestingDepths().toList())
    }

    @Test
    fun aSyncsAgeIsShortUnderTheCloud() {
        val at = Instant.fromEpochSeconds(1_800_000_000)
        assertEquals(SyncAge(SyncAge.Span.NOW, 0), syncAgeOf(at, at + 40.seconds))
        assertEquals(SyncAge(SyncAge.Span.MINUTES, 5), syncAgeOf(at, at + 5.minutes))
        assertEquals(SyncAge(SyncAge.Span.HOURS, 3), syncAgeOf(at, at + 3.hours))
        assertEquals(SyncAge(SyncAge.Span.DAYS, 2), syncAgeOf(at, at + 2.days))
        assertEquals(SyncAge(SyncAge.Span.NOW, 0), syncAgeOf(at, at - 1.minutes), "a clock set back is not a negative age")
    }

    /** The label on a drawer says what the rest of the app reads as this heading's id, or nothing. */
    @Test
    fun aDrawersIdIsItsWholeValueAndABlankOneIsNone() {
        assertEquals("a b", drawerIdOf("PROPERTIES", listOf(":ID: a b")))
        assertEquals("abc", drawerIdOf(":properties:", listOf("  :id:  abc  ")))
        assertEquals(null, drawerIdOf("PROPERTIES", listOf(":ID:")))
        assertEquals(null, drawerIdOf("LOGBOOK", listOf(":ID: abc")))
    }
}
