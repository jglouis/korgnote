package com.korgnote.common

import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaKind
import com.korgnote.common.org.agenda.AgendaOccurrence
import com.korgnote.common.org.agenda.AgendaOptions
import com.korgnote.common.org.agenda.DaySpan
import com.korgnote.common.org.agenda.agenda
import com.korgnote.common.org.agenda.agendaEntries
import com.korgnote.common.org.corpusTodoKeywords
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The agenda engine (T16): each expansion rule in `docs/07-calendar-agenda.md`, over small
 * corpora with "today" pinned to Wednesday 23 September 2026.
 */
class AgendaTests {
    private val today = LocalDate(2026, 9, 23)
    private val options = AgendaOptions(today = today)

    private fun day(d: Int, month: Int = 9) = LocalDate(2026, month, d)

    private fun occurrences(
        text: String,
        from: LocalDate = day(1),
        to: LocalDate = day(30),
        options: AgendaOptions = this.options,
    ): List<AgendaOccurrence> = agenda(mapOf("notes.org" to text), from, to, options)

    private fun List<AgendaOccurrence>.dates() = map { it.date }

    // --- Scheduled ---

    @Test
    fun aScheduledDateIsOneOccurrence() {
        val found = occurrences("* TODO Write report\nSCHEDULED: <2026-09-25 Fri>\n")
        assertEquals(listOf(day(25)), found.dates())
        val entry = found.single().entry
        assertEquals(AgendaKind.Scheduled, entry.kind)
        assertEquals("Write report", entry.title)
        assertEquals("TODO", entry.todo)
    }

    @Test
    fun anUnfinishedPastScheduledItemIsCarriedToToday() {
        val text = "* TODO Call the bank\nSCHEDULED: <2026-09-18 Fri>\n"
        val found = occurrences(text)
        assertEquals(listOf(day(18), today), found.dates())
        assertEquals(listOf(false, true), found.map { it.isCarriedOver })
        assertEquals(null, found.last().time)
    }

    @Test
    fun carryingOverIsAnOption() {
        val found = occurrences("* TODO Call the bank\nSCHEDULED: <2026-09-18 Fri>\n", options = options.copy(carryOver = false))
        assertEquals(listOf(day(18)), found.dates())
    }

    @Test
    fun aFinishedScheduledItemStaysOnItsDate() {
        assertEquals(listOf(day(18)), occurrences("* DONE Call the bank\nSCHEDULED: <2026-09-18 Fri>\n").dates())
    }

    @Test
    fun aHeadingWithoutAKeywordIsStillCarried() {
        // Nothing marks it done, so, as in Org, it is still in front of you.
        val found = occurrences("* Dentist\nSCHEDULED: <2026-09-18 Fri>\n")
        assertEquals(listOf(day(18), today), found.dates())
    }

    @Test
    fun aScheduledDelayHoldsTheEntryBack() {
        val found = occurrences("* TODO Follow up\nSCHEDULED: <2026-09-24 Thu -2d>\n")
        assertEquals(listOf(day(26)), found.dates())
    }

    // --- Deadlines ---

    @Test
    fun aDeadlineWarnsForFourteenDaysByDefault() {
        val found = occurrences("* TODO Tax return\nDEADLINE: <2026-09-30 Wed>\n")
        // Warnings run from today — not from fourteen days before — up to the day itself.
        assertEquals((23..30).map { day(it) }, found.dates())
        assertEquals(listOf(7, 6, 5, 4, 3, 2, 1, 0), found.map { it.daysUntilDeadline })
        assertEquals(listOf(true), found.filter { it.isOnOwnDate }.map { it.date == day(30) })
    }

    @Test
    fun aDeadlineCanSayHowEarlyToWarn() {
        val found = occurrences("* TODO Tax return\nDEADLINE: <2026-09-30 Wed -2d>\n")
        assertEquals(listOf(day(28), day(29), day(30)), found.dates())
    }

    @Test
    fun theDefaultWarningPeriodIsAnOption() {
        val found = occurrences("* TODO Tax return\nDEADLINE: <2026-09-30 Wed>\n", options = options.copy(deadlineWarningDays = 1))
        assertEquals(listOf(day(29), day(30)), found.dates())
    }

    @Test
    fun aMissedDeadlineIsCarriedToTodayCountingHowLate() {
        val found = occurrences("* TODO Renew passport\nDEADLINE: <2026-09-20 Sun>\n")
        assertEquals(listOf(day(20), today), found.dates())
        assertEquals(listOf(0, -3), found.map { it.daysUntilDeadline })
        assertTrue(found.last().isCarriedOver)
    }

    @Test
    fun aFinishedDeadlineNeitherWarnsNorCarries() {
        val found = occurrences("* DONE Renew passport\nDEADLINE: <2026-09-20 Sun>\n* DONE Tax\nDEADLINE: <2026-09-30 Wed>\n")
        assertEquals(listOf(day(20), day(30)), found.dates())
    }

    @Test
    fun aRepeatingDeadlineWarnsOnlyForTheNextOne() {
        // Daily, with the default fortnight's warning: without this rule every day would hold
        // fourteen warnings about its successors.
        val found = occurrences("* TODO Stand-up notes\nDEADLINE: <2026-09-24 Thu +1d>\n", from = day(24), to = day(27))
        assertEquals(listOf(day(24), day(25), day(26), day(27)), found.dates())
        assertTrue(found.all { it.daysUntilDeadline == 0 })
    }

    // --- Repeaters ---

    @Test
    fun aRepeaterExpandsAcrossTheRange() {
        val found = occurrences("* Team meeting <2026-09-01 Tue 10:00 +1w>\n", from = day(20), to = day(10, 10))
        assertEquals(listOf(day(22), day(29), day(6, 10)), found.dates())
        assertTrue(found.all { it.time == LocalTime(10, 0) })
    }

    @Test
    fun aMonthlyRepeatOnThe31stKeepsComingBack() {
        val found = occurrences(
            "* Pay rent <2026-01-31 Sat +1m>\n",
            from = LocalDate(2026, 1, 1),
            to = LocalDate(2026, 4, 30),
        )
        assertEquals(
            listOf(LocalDate(2026, 1, 31), LocalDate(2026, 2, 28), LocalDate(2026, 3, 31), LocalDate(2026, 4, 30)),
            found.dates(),
        )
    }

    @Test
    fun aYearlyRepeatFindsItsDayYearsLater() {
        val found = occurrences("* Birthday <1990-09-25 Tue +1y>\n")
        assertEquals(listOf(day(25)), found.dates())
    }

    @Test
    fun anHourlyRepeatMovesTheTime() {
        val found = occurrences("* Medication <2026-09-10 Thu 20:00 +8h>\n", from = day(10), to = day(11))
        assertEquals(
            listOf(day(10) to LocalTime(20, 0), day(11) to LocalTime(4, 0), day(11) to LocalTime(12, 0), day(11) to LocalTime(20, 0)),
            found.map { it.date to it.time },
        )
    }

    @Test
    fun aFinishedRepeatingEntryShowsOnlyItsNextTime() {
        val found = occurrences("* DONE Water plants\nSCHEDULED: <2026-09-01 Tue +1w>\n")
        assertEquals(listOf(day(29)), found.dates())
    }

    @Test
    fun anUnfinishedRepeatingScheduledItemIsOverdueOnce() {
        // Its dates come round weekly, and the missed one it started from is carried to today.
        val found = occurrences("* TODO Weekly review\nSCHEDULED: <2026-09-07 Mon +1w>\n", from = day(20), to = day(30))
        assertEquals(listOf(day(21), today, day(28)), found.dates())
        assertEquals(listOf(false, true, false), found.map { it.isCarriedOver })
    }

    // --- Ranges and plain stamps ---

    @Test
    fun aMultiDayRangeIsOneOccurrencePerDay() {
        val found = occurrences("* Conference\n<2026-09-21 Mon 09:00>--<2026-09-23 Wed 17:00>\n")
        assertEquals(listOf(day(21), day(22), day(23)), found.dates())
        assertEquals(listOf(DaySpan.Start, DaySpan.Middle, DaySpan.End), found.map { it.span })
        assertEquals(listOf(LocalTime(9, 0), null, null), found.map { it.time })
        assertEquals(listOf(null, null, LocalTime(17, 0)), found.map { it.endTime })
    }

    @Test
    fun aRangeStartedBeforeTheWindowStillShowsItsDaysInside() {
        val found = occurrences("* Holiday\n<2026-08-30 Sun>--<2026-09-02 Wed>\n")
        assertEquals(listOf(day(1), day(2)), found.dates())
        assertEquals(listOf(DaySpan.Middle, DaySpan.End), found.map { it.span })
    }

    @Test
    fun bodyAndHeadingStampsAreAppointments() {
        val text = """
            * Dentist <2026-09-24 Thu 14:30>
            * Planning
            Workshop on <2026-09-25 Fri 10:00-12:00>, bring laptop.
        """.trimIndent()
        val found = occurrences(text)
        assertEquals(listOf("Dentist", "Planning"), found.map { it.entry.title })
        assertTrue(found.all { it.entry.kind == AgendaKind.Timestamp })
        assertEquals(LocalTime(12, 0), found[1].endTime)
    }

    @Test
    fun inactiveStampsAreLeftOutUnlessAskedFor() {
        val text = "* Note\nWritten [2026-09-20 Sun].\n"
        assertEquals(emptyList(), occurrences(text))
        assertEquals(listOf(day(20)), occurrences(text, options = options.copy(includeInactive = true)).dates())
    }

    @Test
    fun closedIsLeftOutUnlessAskedFor() {
        val text = "* DONE Ship it\nCLOSED: [2026-09-21 Mon 18:00] SCHEDULED: <2026-09-20 Sun>\n"
        assertEquals(listOf(AgendaKind.Scheduled), occurrences(text).map { it.entry.kind })
        assertEquals(
            listOf(AgendaKind.Scheduled, AgendaKind.Closed),
            occurrences(text, options = options.copy(includeClosed = true)).map { it.entry.kind },
        )
    }

    @Test
    fun onePlanningLineCanScheduleAndSetADeadline() {
        val found = occurrences("* TODO Draft\nSCHEDULED: <2026-09-24 Thu> DEADLINE: <2026-09-26 Sat -1d>\n")
        assertEquals(
            listOf(day(24) to AgendaKind.Scheduled, day(25) to AgendaKind.Deadline, day(26) to AgendaKind.Deadline),
            found.map { it.date to it.entry.kind },
        )
    }

    @Test
    fun stampsInBlocksAndDrawersAreNotAppointments() {
        val text = """
            * Snippets
            :PROPERTIES:
            :CREATED: <2026-09-24 Thu>
            :END:
            #+BEGIN_SRC org
            SCHEDULED: <2026-09-25 Fri>
            #+END_SRC
            #+begin_example
            <2026-09-26 Sat>
            #+end_example
            But this one counts: <2026-09-27 Sun>
        """.trimIndent()
        assertEquals(listOf(day(27)), occurrences(text).dates())
    }

    // --- What an entry says about its heading ---

    @Test
    fun keywordsAreTheOnesTheFileDeclares() {
        val text = """
            #+TODO: NEXT WAITING | FINISHED
            * NEXT Book flights <2026-09-24 Thu>
            * FINISHED Pack <2026-09-24 Thu>
            * API review <2026-09-24 Thu>
            * TODO Not a keyword here <2026-09-24 Thu>
        """.trimIndent()
        val entries = agendaEntries("trip.org", text)
        assertEquals(listOf("NEXT", "FINISHED", null, null), entries.map { it.todo })
        assertEquals(listOf(false, true, false, false), entries.map { it.done })
        assertEquals("API review", entries[2].title)
        assertEquals("TODO Not a keyword here", entries[3].title)
    }

    @Test
    fun aFileThatDeclaresNothingUsesTheKeywordsOfTheOthers() {
        // agenda.org declares the workflows; the meeting notes rely on them without saying so, as
        // they do in Emacs through `org-todo-keywords`.
        val files = mapOf(
            "agenda.org" to "#+TODO: TODO DOING | DONE CANCELED\n#+TODO: EVENT | FINISHED\n* EVENT Dentist <2026-09-25 Fri>\n",
            "meetings.org" to "* FINISHED Meeting 1\nSCHEDULED: <2026-09-18 Fri>\n* CANCELED Meeting 2\nSCHEDULED: <2026-09-19 Sat>\n",
        )
        val found = agenda(files, day(1), day(30), options)
        val meetings = found.filter { it.entry.file == "meetings.org" }
        assertEquals(listOf("FINISHED", "CANCELED"), meetings.map { it.entry.todo })
        assertTrue(meetings.all { it.entry.done })
        // Done, so neither is carried to today as overdue.
        assertEquals(listOf(day(18), day(19)), meetings.map { it.date })
    }

    @Test
    fun aFileThatDeclaresItsOwnKeywordsKeepsThem() {
        val files = mapOf(
            "agenda.org" to "#+TODO: EVENT | FINISHED\n",
            "work.org" to "#+TODO: NEXT | SHIPPED\n* FINISHED Not a keyword here <2026-09-24 Thu>\n",
        )
        val entry = agendaEntries(files).single()
        assertEquals(null, entry.todo)
        assertEquals("FINISHED Not a keyword here", entry.title)
    }

    @Test
    fun theCorpusKeywordsAreEveryDeclarationPlusOrgsOwn() {
        val keywords = corpusTodoKeywords(
            listOf(
                "#+TODO: TODO(t) WAIT(w@/!) | DONE(d!) CANCELED(c@/!)\n",
                "#+TODO: EVENT(e) | FINISHED(f)\n",
                "#+TODO: REVIEW | PARKED\n",
                // Called open here and done above: done wins.
                "#+TODO: PARKED | ARCHIVED\n",
                "* No declaration\n",
            ),
        )
        assertEquals(setOf("TODO", "WAIT", "EVENT", "REVIEW"), keywords.todo)
        assertEquals(setOf("DONE", "CANCELED", "FINISHED", "PARKED", "ARCHIVED"), keywords.done)
        assertEquals(setOf("TODO"), corpusTodoKeywords(emptyList()).todo)
    }

    @Test
    fun tagsAreInheritedFromParentsAndTheFile() {
        val text = """
            #+FILETAGS: :home:
            * Garden :outdoor:
            ** TODO [#A] Mow the lawn :weekly:
            SCHEDULED: <2026-09-26 Sat>
        """.trimIndent()
        val entry = agendaEntries("house.org", text).single()
        assertEquals(listOf("weekly", "outdoor", "home"), entry.tags)
        assertEquals('A', entry.priority)
        assertEquals("Mow the lawn", entry.title)
    }

    @Test
    fun anEntryPointsAtItsHeadingAndItsStamp() {
        val text = "#+TITLE: Work\n\n* Intro\nNothing dated.\n* TODO Report\nDEADLINE: <2026-09-30 Wed>\n"
        val entry = agendaEntries("work.org", text).single()
        assertEquals(text.indexOf("* TODO Report"), entry.headingOffset)
        assertEquals("<2026-09-30 Wed>", text.substring(entry.stamp.range))
    }

    @Test
    fun entriesComeFromEveryFileInPathOrder() {
        val found = agenda(
            mapOf(
                "b.org" to "* B <2026-09-24 Thu>\n",
                "a.org" to "* A <2026-09-24 Thu>\n* A timed <2026-09-24 Thu 08:00>\n",
            ),
            day(1), day(30), options,
        )
        // All-day first, then by time; ties by file.
        assertEquals(listOf("A", "B", "A timed"), found.map { it.entry.title })
    }

    // --- The index the app keeps ---

    @Test
    fun theIndexAnswersPerDayWhatTheRangeQueryAnswers() {
        val files = mapOf(
            "notes.org" to """
                * TODO Weekly review
                SCHEDULED: <2026-08-31 Mon +1w>
                * TODO Tax return
                DEADLINE: <2026-10-02 Fri>
                * Trip <2026-09-29 Tue>--<2026-10-03 Sat>
            """.trimIndent(),
        )
        val index = AgendaIndex.of(files, options)
        val from = day(20)
        val to = day(10, 10)
        val byDay = generateSequence(from) { if (it < to) LocalDate.fromEpochDays(it.toEpochDays() + 1) else null }
            .flatMap { index.on(it) }.toList()
        assertEquals(index.between(from, to), byDay)
    }
}
