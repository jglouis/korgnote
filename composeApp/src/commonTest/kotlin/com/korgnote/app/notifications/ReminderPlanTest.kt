package com.korgnote.app.notifications

import com.korgnote.common.org.agenda.agendaEntries
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What reminders the device is asked to hold (T18), worked out from the agenda with the clock at
 * Wednesday 23 September 2026, 08:00.
 */
class ReminderPlanTest {
    private val now = LocalDateTime(2026, 9, 23, 8, 0)

    private fun plan(text: String, settings: ReminderSettings = ReminderSettings(), at: LocalDateTime = now) =
        planReminders(agendaEntries(mapOf("notes.org" to text)), at, settings)

    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 9) = LocalDateTime(2026, month, day, hour, minute)

    @Test
    fun aTimedEntryIsRemindedTheLeadTimeBefore() {
        val reminders = plan("* Design review <2026-09-23 Wed 10:00-11:30>\n")
        assertEquals(listOf(at(23, 9, 50)), reminders.map { it.at })
        with(reminders.single()) {
            assertEquals("Design review", title)
            assertEquals("10:00–11:30 · notes.org", body)
            assertEquals("notes.org", file)
            assertEquals(0, offset)
        }
    }

    @Test
    fun theLeadTimeIsASetting() {
        val reminders = plan("* Call <2026-09-24 Thu 00:15>\n", ReminderSettings(leadMinutes = 30))
        // Across midnight: the day before, at 23:45.
        assertEquals(listOf(at(23, 23, 45)), reminders.map { it.at })
    }

    @Test
    fun onlyWhatIsStillAheadIsKept() {
        // 08:05 less ten minutes is already past; 08:15 less ten is not.
        val reminders = plan("* Early <2026-09-23 Wed 08:05>\n* Later <2026-09-23 Wed 08:15>\n")
        assertEquals(listOf("Later"), reminders.map { it.title })
    }

    @Test
    fun untimedAndDoneEntriesAreNotReminded() {
        val reminders = plan(
            """
            * All day <2026-09-24 Thu>
            * DONE Already done <2026-09-24 Thu 10:00>
            * CANCELED Called off <2026-09-24 Thu 11:00>
            * TODO Still to do <2026-09-24 Thu 12:00>
            #+TODO: TODO | DONE CANCELED
            """.trimIndent(),
        )
        assertEquals(listOf("TODO Still to do"), reminders.map { it.title })
    }

    @Test
    fun scheduledItemsAndDeadlinesWithATimeAreReminded() {
        val reminders = plan(
            """
            * TODO Submit the report
            DEADLINE: <2026-09-25 Fri 17:00>
            * TODO Call the bank
            SCHEDULED: <2026-09-24 Thu 09:30>
            """.trimIndent(),
        )
        assertEquals(listOf(at(24, 9, 20), at(25, 16, 50)), reminders.map { it.at })
        assertEquals(listOf("09:30 · Scheduled · notes.org", "17:00 · Deadline · notes.org"), reminders.map { it.body })
    }

    @Test
    fun overdueItemsAndDeadlineWarningsAreNotReminders() {
        val reminders = plan(
            """
            * TODO Missed
            SCHEDULED: <2026-09-20 Sun 10:00>
            * TODO Far off
            DEADLINE: <2026-10-01 Thu>
            """.trimIndent(),
        )
        assertEquals(emptyList(), reminders)
    }

    @Test
    fun aMultiDayRangeRemindsOnceWhenItStarts() {
        val reminders = plan("* Conference\n<2026-09-24 Thu 09:00>--<2026-09-26 Sat 17:00>\n")
        assertEquals(listOf(at(24, 8, 50)), reminders.map { it.at })
    }

    @Test
    fun repeatsAreRemindedWithinTheHorizon() {
        val reminders = plan("* Stand-up <2026-09-01 Tue 09:15 +1d>\n")
        assertEquals(REMINDER_HORIZON_DAYS + 1, reminders.size)
        assertEquals(at(23, 9, 5), reminders.first().at)
        assertEquals(at(7, 9, 5, month = 10), reminders.last().at)
    }

    @Test
    fun theSoonestAreKeptWhenThereAreTooMany() {
        val reminders = plan("* Medication <2026-09-23 Wed 09:00 +2h>\n")
        assertEquals(MAX_REMINDERS, reminders.size)
        assertEquals(at(23, 8, 50), reminders.first().at)
        assertTrue(reminders.zipWithNext().all { (a, b) -> a.at <= b.at })
    }

    @Test
    fun theDigestListsEachDayThatHasSomething() {
        val reminders = plan(
            """
            * Design review <2026-09-24 Thu 10:00-11:30>
            * TODO Submit the report
            DEADLINE: <2026-09-24 Thu>
            * Dentist <2026-09-26 Sat 14:30>
            """.trimIndent(),
            ReminderSettings(enabled = false, digestEnabled = true, digestTime = LocalTime(7, 30)),
        )
        assertEquals(listOf(at(24, 7, 30), at(26, 7, 30)), reminders.map { it.at })
        with(reminders.first()) {
            assertEquals("Today: 2 entries", title)
            assertEquals("Deadline  TODO Submit the report\n10:00–11:30  Design review", body)
            assertEquals(null, file)
        }
    }

    @Test
    fun todaysDigestIsSkippedOnceItsTimeHasPassed() {
        val reminders = plan(
            "* Lunch <2026-09-23 Wed 12:30>\n",
            ReminderSettings(enabled = false, digestEnabled = true, digestTime = LocalTime(7, 0)),
        )
        assertEquals(emptyList(), reminders)
    }

    @Test
    fun titlesReadAsTheyDoInTheNote() {
        val reminders = plan("* Call with the CEO of [[https://www.heliovision.be/][HelioVision]] about *pricing* <2026-09-23 Wed 16:00>\n")
        assertEquals("Call with the CEO of HelioVision about pricing", reminders.single().title)
    }

    @Test
    fun theSameEntryKeepsItsIdAcrossPlans() {
        val text = "* Design review <2026-09-24 Thu 10:00>\n"
        assertEquals(plan(text).map { it.id }, plan(text, at = now.date.let { LocalDateTime(it, LocalTime(9, 0)) }).map { it.id })
    }

    @Test
    fun settingsSurviveBeingStored() {
        val settings = ReminderSettings(enabled = false, leadMinutes = 45, digestEnabled = true, digestTime = LocalTime(6, 5))
        assertEquals(settings, ReminderSettings.decode(settings.encode()))
        assertEquals(ReminderSettings(), ReminderSettings.decode(null))
        assertEquals(ReminderSettings(), ReminderSettings.decode("garbage"))
        assertEquals(LocalTime(8, 0), ReminderSettings.parseTime("8:00"))
        assertEquals(null, ReminderSettings.parseTime("25:00"))
        assertEquals(LocalDate(2026, 9, 23), now.date)
    }
}
