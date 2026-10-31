package com.korgnote.common

import com.korgnote.common.org.capture.CalendarEvent
import com.korgnote.common.org.capture.CaptureLink
import com.korgnote.common.org.capture.EventTime
import com.korgnote.common.org.capture.icsOf
import com.korgnote.common.org.capture.Captured
import com.korgnote.common.org.capture.HeadingAdditions
import com.korgnote.common.org.capture.HeadingRef
import com.korgnote.common.org.capture.captureInto
import com.korgnote.common.org.capture.fileUnder
import com.korgnote.common.org.capture.SharedContent
import com.korgnote.common.org.capture.calendarEventFrom
import com.korgnote.common.org.capture.captureLinkOf
import com.korgnote.common.org.capture.capturedFrom
import com.korgnote.common.org.capture.fileCapture
import com.korgnote.common.org.capture.EntryParts
import com.korgnote.common.org.capture.captureEntry
import com.korgnote.common.org.capture.entryKeyword
import com.korgnote.common.org.capture.renderCapture
import com.korgnote.common.org.capture.withEntryKeyword
import com.korgnote.common.sync.MergeResult
import com.korgnote.common.sync.merge
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptureTest {

    /** Paris in winter, without needing the zone database every target may not have. */
    private val zone: TimeZone = UtcOffset(hours = 1).asTimeZone()
    private val now = LocalDateTime(2026, 10, 31, 14, 5)

    private fun read(text: String? = null, subject: String? = null, url: String? = null, calendar: String? = null) =
        capturedFrom(SharedContent(subject = subject, text = text, url = url, calendar = calendar), zone)

    private fun note(captured: Captured) = captureEntry(captured, now).text

    // --- Reading what was shared ---

    @Test
    fun aPageSharedFromABrowserIsItsTitleAndLink() {
        val captured = read(text = "https://orgmode.org/manual/Capture.html", subject = "Capture (The Org Manual)")
        assertEquals("Capture (The Org Manual)", captured.title)
        assertEquals("https://orgmode.org/manual/Capture.html", captured.link)
        assertEquals("", captured.text)
    }

    @Test
    fun aLinkSharedWithAWordAboutItTakesTheWordForItsTitle() {
        val captured = read(text = "Org mode in 100 seconds https://youtu.be/abc123")
        assertEquals("Org mode in 100 seconds", captured.title)
        assertEquals("https://youtu.be/abc123", captured.link)
    }

    @Test
    fun aBareLinkIsNamedByItself() {
        assertEquals("github.com/jglouis/korgnote", read(text = "https://www.github.com/jglouis/korgnote/?tab=readme").title)
    }

    @Test
    fun aLinkEndingASentenceLeavesItsFullStopBehind() {
        assertEquals("https://example.com/a", read(text = "See https://example.com/a.").link)
    }

    @Test
    fun aGoogleMapsPlaceIsItsNameAddressAndLink() {
        val captured = read(text = "Tour Eiffel\nAv. Gustave Eiffel, 75007 Paris, France\nhttps://maps.app.goo.gl/Xy12AbCd")
        assertEquals("Tour Eiffel", captured.title)
        assertEquals("Av. Gustave Eiffel, 75007 Paris, France", captured.location)
        assertEquals("https://maps.app.goo.gl/Xy12AbCd", captured.link)
        assertNull(captured.geo)
    }

    @Test
    fun aGoogleMapsShareAsTheAppSendsItIsItsNameAndShortLink() {
        // Google Maps on Android, seen 2026-10-31: the link as the text, the place's name as the title.
        val captured = read(text = "https://maps.app.goo.gl/ne1k1Zy6Prd6t9QC8", subject = "Eiffel Tower")
        assertEquals("Eiffel Tower", captured.title)
        assertEquals("https://maps.app.goo.gl/ne1k1Zy6Prd6t9QC8", captured.link)
        assertNull(captured.location)
    }

    @Test
    fun aGoogleMapsLinkAloneSaysWhatItCan() {
        val captured = read(text = "https://www.google.com/maps/place/Tour+Eiffel/@48.8583701,2.2944813,17z/data=!3m1")
        assertEquals("Tour Eiffel", captured.title)
        assertEquals("geo:48.8583701,2.2944813", captured.geo)
    }

    @Test
    fun anAppleMapsPlaceIsReadFromItsLink() {
        val captured = read(
            url = "https://maps.apple.com/place?address=5%20Av.%20Anatole%20France,%2075007%20Paris&coordinate=48.858,2.2945&name=Tour%20Eiffel",
        )
        assertEquals("Tour Eiffel", captured.title)
        assertEquals("5 Av. Anatole France, 75007 Paris", captured.location)
        assertEquals("geo:48.858,2.2945", captured.geo)
    }

    @Test
    fun aGeoLinkGivesItsCoordinatesAndLabel() {
        val captured = read(text = "geo:50.8466,4.3528?q=50.8466,4.3528(Grand-Place)")
        assertEquals("Grand-Place", captured.title)
        assertEquals("geo:50.8466,4.3528", captured.geo)
    }

    @Test
    fun aShortLineOfTextIsTheHeading() {
        val captured = read(text = "  Call the plumber  ")
        assertEquals("Call the plumber", captured.title)
        assertEquals("", captured.text)
    }

    @Test
    fun severalLinesOfTextAreAHeadingAndWhatFollows() {
        val captured = read(text = "Shopping\neggs\nflour")
        assertEquals("Shopping", captured.title)
        assertEquals("eggs\nflour", captured.text)
    }

    @Test
    fun aParagraphIsNamedByItsStartAndKeptWhole() {
        val paragraph = "The quick brown fox jumps over the lazy dog, and then it jumps over the lazy dog again and again."
        val captured = read(text = paragraph)
        assertEquals("The quick brown fox jumps over the lazy dog, and then it jumps over the lazy dog…", captured.title)
        assertEquals(paragraph, captured.text)
    }

    // --- Calendar files ---

    private fun ics(vararg event: String) =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "METHOD:REQUEST", "BEGIN:VEVENT") + event + listOf("END:VEVENT", "END:VCALENDAR"))
            .joinToString("\r\n")

    @Test
    fun anInvitationIsItsSummaryTimeAndPlace() {
        val captured = read(
            calendar = ics(
                "DTSTART:20261103T130000Z",
                "DTEND:20261103T140000Z",
                "SUMMARY:Sprint review",
                "LOCATION:Room 4\\, second floor",
                "DESCRIPTION:Demo of capture.\\nBring the phone.\\n\\n-::~:~::~:~:~:~::~:~::-\\nJoin with Google Meet: https://meet.google.com/x",
            ),
        )
        assertEquals("Sprint review", captured.title)
        assertEquals("<2026-11-03 Tue 14:00-15:00>", captured.time)
        assertEquals("Room 4, second floor", captured.location)
        assertEquals("Demo of capture.\nBring the phone.", captured.text)
    }

    @Test
    fun foldedLinesAreReadAsOne() {
        val event = calendarEventFrom(ics("DTSTART;VALUE=DATE:20261224", "SUMMARY:Christmas", " Eve dinner"), zone)
        assertEquals("ChristmasEve dinner", event?.summary)
    }

    @Test
    fun anAllDayEventEndsTheDayBeforeItsEnd() {
        assertEquals("<2026-12-24 Thu>", read(calendar = ics("DTSTART;VALUE=DATE:20261224", "DTEND;VALUE=DATE:20261225", "SUMMARY:X")).time)
        assertEquals(
            "<2026-12-24 Thu>--<2026-12-26 Sat>",
            read(calendar = ics("DTSTART;VALUE=DATE:20261224", "DTEND;VALUE=DATE:20261227", "SUMMARY:X")).time,
        )
    }

    @Test
    fun anEventAcrossMidnightIsARange() {
        assertEquals(
            "<2026-11-03 Tue 22:00>--<2026-11-04 Wed 02:00>",
            read(calendar = ics("DTSTART:20261103T220000", "DTEND:20261104T020000", "SUMMARY:X")).time,
        )
    }

    @Test
    fun aWeeklyMeetingRepeatsAsOrgDoes() {
        assertEquals(
            "<2026-11-03 Tue 14:00-15:00 +1w>",
            read(calendar = ics("DTSTART:20261103T140000", "DTEND:20261103T150000", "RRULE:FREQ=WEEKLY;BYDAY=TU", "SUMMARY:X")).time,
        )
        assertEquals(
            "<2026-11-03 Tue +2m>",
            read(calendar = ics("DTSTART;VALUE=DATE:20261103", "RRULE:FREQ=MONTHLY;INTERVAL=2", "SUMMARY:X")).time,
        )
    }

    @Test
    fun aRuleOrgCannotSayLeavesTheEventOnce() {
        assertEquals("<2026-11-03 Tue 14:00>", read(calendar = ics("DTSTART:20261103T140000", "RRULE:FREQ=WEEKLY;BYDAY=TU,TH", "SUMMARY:X")).time)
        assertEquals("<2026-11-03 Tue 14:00>", read(calendar = ics("DTSTART:20261103T140000", "RRULE:FREQ=WEEKLY;COUNT=4", "SUMMARY:X")).time)
    }

    @Test
    fun anAlarmInsideTheEventDoesNotLendItsDescription() {
        val event = calendarEventFrom(
            ics("DTSTART:20261103T140000", "SUMMARY:X", "BEGIN:VALARM", "DESCRIPTION:Reminder", "END:VALARM"),
            zone,
        )
        assertNull(event?.description)
    }

    @Test
    fun anEventFromThePhonesCalendarTravelsAsACalendarFileAndComesBackTheSame() {
        val event = CalendarEvent(
            summary = "Sprint review; demo, then drinks",
            start = EventTime.At(LocalDateTime(2026, 11, 3, 14, 0)),
            end = EventTime.At(LocalDateTime(2026, 11, 3, 15, 30)),
            location = "Room 4, second floor",
            description = "Bring the phone.\nAnd the charger \\o/",
            repeater = "+1w",
            rule = "FREQ=WEEKLY;BYDAY=TU",
        )
        assertEquals(event, calendarEventFrom(icsOf(event), zone))
        val allDay = CalendarEvent("Holiday", EventTime.AllDay(kotlinx.datetime.LocalDate(2026, 12, 24)), EventTime.AllDay(kotlinx.datetime.LocalDate(2026, 12, 27)))
        assertEquals(allDay, calendarEventFrom(icsOf(allDay), zone))
    }

    @Test
    fun aFileWithNoEventIsReadAsTextInstead() {
        assertNull(calendarEventFrom("BEGIN:VCALENDAR\nEND:VCALENDAR", zone))
    }

    // --- Templates ---

    @Test
    fun aPageBecomesANoteWithItsLink() {
        assertEquals(
            """
            * Capture (The Org Manual)
            https://orgmode.org/manual/Capture.html

            """.trimIndent(),
            note(read(text = "https://orgmode.org/manual/Capture.html", subject = "Capture (The Org Manual)")),
        )
    }

    @Test
    fun anEventBecomesANoteTheAgendaShows() {
        assertEquals(
            """
            * Sprint review
            SCHEDULED: <2026-11-03 Tue 14:00-15:00>
            :PROPERTIES:
            :LOCATION: Room 4
            :END:

            """.trimIndent(),
            note(read(calendar = ics("DTSTART:20261103T140000", "DTEND:20261103T150000", "SUMMARY:Sprint review", "LOCATION:Room 4"))),
        )
    }

    @Test
    fun aDrawerWithNothingLeftInItGoes() {
        val entry = "* %:description\n:PROPERTIES:\n:LOCATION: %:location\n:END:\n%i"
        assertEquals("* Hello\n", renderCapture(entry, Captured(title = "Hello"), now).text)
    }

    @Test
    fun aHeadingWithNoTitleIsStillAHeading() {
        assertEquals("* TODO\n[2026-10-31 Sat 14:05]\n", renderCapture("* TODO %:description\n%U", Captured(), now).text)
        assertEquals("* \n", renderCapture("* %:description", Captured(), now).text)
    }

    @Test
    fun theCaretGoesWhereTheTemplateSaysOrAtTheEndOfTheHeading() {
        assertEquals(7, renderCapture("* TODO %?\n%U", Captured(), now).cursor)
        assertEquals(7, renderCapture("* Hello\n%U", Captured(), now).cursor)
        val afterARemovedLine = renderCapture("* A\n%:link\nNote: %?", Captured(), now)
        assertEquals("* A\nNote: \n", afterARemovedLine.text)
        assertEquals(10, afterARemovedLine.cursor)
    }

    @Test
    fun sharedTextCannotStartAHeadingOfItsOwn() {
        val rendered = renderCapture("* %:description\n  %i", Captured(title = "List", text = "first\n* second\nthird"), now)
        assertEquals("* List\n  first\n   * second\n  third\n", rendered.text)
    }

    @Test
    fun anEscapeKorgNoteDoesNotKnowIsLeftForEmacs() {
        assertEquals("* %F 100%\n", renderCapture("* %F 100%%", Captured(), now).text)
    }

    @Test
    fun aLinkWithItsTitleIsWrittenAsOrgWritesOneWithoutBracketsThatWouldEndIt() {
        assertEquals("* [[https://x.org][A (b) c]]\n", renderCapture("* %a", Captured(title = "A [b] c", link = "https://x.org"), now).text)
    }

    // --- Into an existing heading ---

    private val eiffel = Captured(title = "Eiffel Tower", link = "https://maps.app.goo.gl/x")

    @Test
    fun aPlaceSetsTheHeadingsLocationAndItsLinkWhenAskedFor() {
        val file = "#+TITLE: Trips\n* Trip\n** Paris\nBook the hotel.\n** Rome\n"
        val into = HeadingRef("trips.org", outline = listOf("Trip"), title = "Paris")
        assertEquals(
            "#+TITLE: Trips\n* Trip\n** Paris\n:PROPERTIES:\n:LOCATION: Eiffel Tower\n:END:\nBook the hotel.\n** Rome\n",
            captureInto(file, into, eiffel, HeadingAdditions.defaultsFor(eiffel)),
        )
        assertEquals(
            "#+TITLE: Trips\n* Trip\n** Paris\n:PROPERTIES:\n:LOCATION: Eiffel Tower\n:END:\nBook the hotel.\n" +
                "- [[https://maps.app.goo.gl/x][Eiffel Tower]]\n** Rome\n",
            captureInto(file, into, eiffel, HeadingAdditions(location = true, link = true)),
        )
    }

    @Test
    fun aPlaceFromGoogleMapsIsItsLocationAndItsLinkOnlyWhenAskedFor() {
        // Google Maps shares the name as the title and a short link: no address.
        val place = read(text = "https://maps.app.goo.gl/x", subject = "Eiffel Tower")
        assertEquals(EntryParts(location = true, link = false), EntryParts.defaultsFor(place))
        assertEquals(EntryParts(location = true, link = true), EntryParts.available(place))
        assertEquals("* Eiffel Tower\n:PROPERTIES:\n:LOCATION: Eiffel Tower\n:END:\n", captureEntry(place, now).text)
        assertEquals("* Eiffel Tower\nhttps://maps.app.goo.gl/x\n", captureEntry(place, now, location = false, link = true).text)
        assertEquals(
            "* Eiffel Tower\n:PROPERTIES:\n:LOCATION: Eiffel Tower\n:END:\nhttps://maps.app.goo.gl/x\n",
            captureEntry(place, now, location = true, link = true).text,
        )
        // With an address, that is the location.
        assertEquals(
            "* Tour Eiffel\n:PROPERTIES:\n:LOCATION: Av. Gustave Eiffel, 75007 Paris\n:END:\n",
            captureEntry(read(text = "Tour Eiffel\nAv. Gustave Eiffel, 75007 Paris\nhttps://maps.app.goo.gl/y"), now).text,
        )
        // A page is no place: its link, and no location to offer.
        assertEquals(EntryParts(location = false, link = true), EntryParts.available(read(text = "https://orgmode.org", subject = "Org")))
    }

    @Test
    fun aLinkGoesAfterTheHeadingsOwnTextAndBeforeItsSubHeadings() {
        val file = "* Reading\n:PROPERTIES:\n:ID: r1\n:END:\n- [[https://a.org][A]]\n\n** Done\n"
        val page = Captured(title = "Org manual", link = "https://orgmode.org")
        assertEquals(
            "* Reading\n:PROPERTIES:\n:ID: r1\n:END:\n- [[https://a.org][A]]\n- [[https://orgmode.org][Org manual]]\n\n** Done\n",
            captureInto(file, HeadingRef("r.org", title = "Reading"), page, HeadingAdditions.defaultsFor(page)),
        )
    }

    @Test
    fun aHeadingIsFoundByItsIdWhereverItMoved() {
        val file = "* Elsewhere\n** Renamed :tag:\n:PROPERTIES:\n:ID: abc\n:END:\n"
        val into = HeadingRef("n.org", outline = listOf("Old parent"), title = "Old title", id = "abc")
        assertEquals(
            "* Elsewhere\n** Renamed :tag:\n:PROPERTIES:\n:ID: abc\n:END:\n- [[https://x.org][X]]\n",
            captureInto(file, into, Captured(title = "X", link = "https://x.org"), HeadingAdditions(link = true)),
        )
    }

    @Test
    fun anEventAcrossDaysCannotBeScheduledAndIsWrittenAsItIs() {
        val trip = Captured(title = "Trip", time = "<2026-12-24 Thu>--<2026-12-26 Sat>")
        assertEquals("* Trip\n<2026-12-24 Thu>--<2026-12-26 Sat>\n", renderCapture("* %:description\n%:scheduled\n%:range", trip, now).text)
        assertEquals(
            "* Holidays\n<2026-12-24 Thu>--<2026-12-26 Sat>\n",
            captureInto("* Holidays\n", HeadingRef("n.org", title = "Holidays"), trip, HeadingAdditions(time = true)),
        )
    }

    @Test
    fun aHeadingIsFoundWhateverKeywordsItsNoteDeclares() {
        // The picker read EVENT as a keyword, from another note's #+TODO; this note declares none.
        val file = "* EVENT test\n** WAIT Call back\n"
        assertEquals(
            "* EVENT test\n** WAIT Call back\n- [[https://x.org][X]]\n",
            captureInto(file, HeadingRef("c.org", outline = listOf("test"), title = "Call back"), Captured(title = "X", link = "https://x.org"), HeadingAdditions(link = true)),
        )
    }

    @Test
    fun aNewEntryCanBeFiledUnderAnyHeading() {
        val file = "* Trips\n** Paris\nnotes\n*** Louvre\n\n** Rome\n"
        assertEquals(
            "* Trips\n** Paris\nnotes\n*** Louvre\n*** Eiffel Tower\nhttps://maps\n\n** Rome\n",
            fileUnder(file, HeadingRef("t.org", outline = listOf("Trips"), title = "Paris"), "* Eiffel Tower\nhttps://maps\n"),
        )
        assertNull(fileUnder(file, HeadingRef("t.org", title = "Berlin"), "* X\n"))
    }

    @Test
    fun aHeadingThatIsGoneIsNotGuessed() {
        assertNull(captureInto("* Other\n", HeadingRef("n.org", title = "Paris"), eiffel, HeadingAdditions(link = true)))
    }

    @Test
    fun anEventGivesTheHeadingItsTimeForTheAgenda() {
        val meeting = Captured(title = "Sprint review", time = "<2026-11-03 Tue 14:00-15:00>", location = "Room 4")
        val additions = HeadingAdditions.defaultsFor(meeting)
        assertEquals(HeadingAdditions(time = true, location = true), additions)
        assertEquals(
            "* TODO Prepare the demo\nSCHEDULED: <2026-11-03 Tue 14:00-15:00>\n:PROPERTIES:\n:LOCATION: Room 4\n:END:\n",
            captureInto("* TODO Prepare the demo\n", HeadingRef("w.org", title = "Prepare the demo"), meeting, additions),
        )
    }

    @Test
    fun whatGoesToAHeadingByDefaultFollowsWhatWasShared() {
        assertEquals(HeadingAdditions(location = true), HeadingAdditions.defaultsFor(read(text = "https://maps.app.goo.gl/x", subject = "Eiffel Tower")))
        assertEquals(HeadingAdditions(link = true), HeadingAdditions.defaultsFor(read(text = "https://orgmode.org", subject = "Org")))
        // Brave shares a selection as the quote and a link to it.
        assertEquals(
            HeadingAdditions(link = true, text = true),
            HeadingAdditions.defaultsFor(read(text = "\"collection\"\nhttps://commons.wikimedia.org/#:~:text=collection", subject = "Commons")),
        )
        assertEquals(HeadingAdditions(text = true), HeadingAdditions.defaultsFor(read(text = "Call back after five")))
    }

    // --- The keyword ---

    private val keywords = listOf("TODO", "WAIT", "EVENT", "DONE")

    @Test
    fun anEntryIsMarkedWithTheKeywordChosenOrNone() {
        assertEquals("* TODO Call back\n", captureEntry(Captured(title = "Call back"), now, keyword = "TODO").text.lines().first() + "\n")
        assertEquals("* EVENT Call back\nbody\n", withEntryKeyword("* TODO Call back\nbody\n", "EVENT", keywords))
        assertEquals("* Call back\nbody\n", withEntryKeyword("* WAIT Call back\nbody\n", null, keywords))
        assertEquals("** TODO Call back", withEntryKeyword("** Call back", "TODO", keywords))
    }

    @Test
    fun aWordInCapitalsIsAKeywordOnlyWhenTheNoteDeclaresIt() {
        assertEquals("TODO", entryKeyword("* TODO NASA launch\n", keywords))
        assertNull(entryKeyword("* NASA launch\n", keywords))
        assertEquals("* TODO NASA launch", withEntryKeyword("* NASA launch", "TODO", keywords))
    }

    // --- Capture links ---

    @Test
    fun aCaptureLinkCarriesOrgProtocolsParameters() {
        val link = captureLinkOf("korgnote://capture?template=Task&url=https%3A%2F%2Forgmode.org%2F&title=Org%20mode&body=a+b")
        assertEquals(CaptureLink(SharedContent(subject = "Org mode", text = "a b", url = "https://orgmode.org/")), link)
        assertEquals(CaptureLink(SharedContent()), captureLinkOf("korgnote://capture"))
    }

    @Test
    fun otherLinksAreNotCaptureLinks() {
        assertNull(captureLinkOf("korgnote://pair?code=x"))
        assertNull(captureLinkOf("korgnote://captures?x=1"))
    }

    // --- Filing ---

    @Test
    fun anEntryWithNoHeadingGoesAtTheEnd() {
        assertEquals("* A\n* B\n", fileCapture("* A", "* B\n"))
        assertEquals("* A\n* B\n", fileCapture("* A\n", "* B"))
        assertEquals("* B\n", fileCapture("", "* B\n"))
    }

    @Test
    fun anEntryGoesAtTheEndOfItsHeadingsSubtree() {
        val file = "#+TITLE: Inbox\n* Tasks\n** TODO one\nbody\n\n* Done\n** DONE old\n"
        assertEquals(
            "#+TITLE: Inbox\n* Tasks\n** TODO one\nbody\n** TODO two\n\n* Done\n** DONE old\n",
            fileCapture(file, "* TODO two\n", heading = "Tasks"),
        )
    }

    @Test
    fun aDeeperHeadingPushesTheEntryDeeper() {
        val file = "* Work\n** Inbox :work:\n*** a\n* Home\n"
        assertEquals("* Work\n** Inbox :work:\n*** a\n*** b\n**** detail\n* Home\n", fileCapture(file, "* b\n** detail\n", heading = "Inbox"))
    }

    @Test
    fun aMissingHeadingIsAddedAtTheEnd() {
        assertEquals("* Other\n* Tasks\n** TODO x\n", fileCapture("* Other\n", "* TODO x\n", heading = "Tasks"))
    }

    @Test
    fun twoDevicesCapturingIntoOneInboxKeepBoth() {
        val base = "#+TITLE: Inbox\n\n* Earlier\n"
        val phone = fileCapture(base, "* From the phone\n")
        val laptop = fileCapture(base, "* From the laptop\n")
        val merged = merge(base, phone, laptop)
        assertEquals(MergeResult.Merged("#+TITLE: Inbox\n\n* Earlier\n* From the phone\n* From the laptop\n"), merged)
    }

    @Test
    fun twoDevicesCapturingUnderOneHeadingKeepBoth() {
        val base = "* Tasks\n** a\n* Later\n"
        val merged = merge(base, fileCapture(base, "* b", "Tasks"), fileCapture(base, "* c", "Tasks"))
        assertEquals(MergeResult.Merged("* Tasks\n** a\n** b\n** c\n* Later\n"), merged)
    }
}
