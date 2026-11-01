package com.korgnote.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaOptions
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** Clocked time in the calendar: on the hour grid, in the agenda's report, and clocking in from an entry. */
@OptIn(ExperimentalTestApi::class)
class CalendarClockingTest {
    private val today = LocalDate(2026, 11, 2)
    private val now = LocalDateTime(2026, 11, 2, 15, 15)
    private val work = """
        * Design review <2026-11-02 Mon 10:00-11:00>
        * TODO Report
        :LOGBOOK:
        CLOCK: [2026-11-02 Mon 09:00]--[2026-11-02 Mon 10:30] =>  1:30
        :END:
        ** Figures
        :LOGBOOK:
        CLOCK: [2026-11-02 Mon 14:00]
        :END:
    """.trimIndent() + "\n"
    private val agenda = AgendaIndex.of(mapOf("work.org" to work), AgendaOptions(today = today))

    private val clockedIn = mutableListOf<Pair<String, Int>>()
    private val opened = mutableListOf<Pair<String, Int>>()

    private fun ComposeUiTest.show(mode: CalendarMode) = setContent {
        MaterialTheme {
            CalendarScreen(
                Modifier.size(390.dp, 800.dp),
                agenda = agenda,
                initialMode = mode,
                now = now,
                clocking = CalendarClocking(
                    isRunning = { file, offset -> file == "work.org" && offset == work.indexOf("** Figures") },
                    clockIn = { file, offset -> clockedIn += file to offset },
                    clockOut = { _, _ -> },
                    open = { file, offset -> opened += file to offset },
                ),
            )
        }
    }

    @Test
    fun theDayShowsWhatWasClockedAndATapOpensIt() = runComposeUiTest {
        show(CalendarMode.Day)
        onNodeWithContentDescription("Clocked on Report from 09:00, 1 h 30").assertExists()
        // The running clock, up to now.
        onNodeWithContentDescription("Clocked on Figures from 14:00, 1 h 15").performClick()
        assertEquals(listOf("work.org" to work.indexOf("** Figures")), opened)
    }

    @Test
    fun theDaysTotalOpensItsReport() = runComposeUiTest {
        show(CalendarMode.Day)
        onNodeWithText("2 h 45 clocked").performClick()
        onNodeWithText("Time clocked").assertExists()
        onNodeWithText("Mon 2 Nov").assertExists()
        onNode(hasText("Figures") and hasAnyAncestor(hasTestTag("clockReport"))).performClick()
        assertEquals(listOf("work.org" to work.indexOf("** Figures")), opened)
    }

    @Test
    fun theAgendaSumsTheDaysShown() = runComposeUiTest {
        show(CalendarMode.Agenda)
        onNodeWithTag("agendaList").performScrollToNode(hasTestTag("clockReport"))
        onNodeWithText("Time clocked").assertExists()
        onNodeWithText("Mon 2 Nov to Sun 8 Nov").assertExists()
        onNodeWithText("TODO Report").assertExists()
        onNodeWithText("1 h 30").assertExists()
        onNodeWithText("1 h 15").assertExists()
        onNodeWithText("2 h 45").assertExists()
    }

    @Test
    fun anEntryIsClockedInFromItsLongPress() = runComposeUiTest {
        show(CalendarMode.Agenda)
        onAllNodesWithText("Design review", substring = true)[0].performTouchInput { longClick() }
        onNodeWithText("Clock in").performClick()
        assertEquals(listOf("work.org" to 0), clockedIn)
    }
}
