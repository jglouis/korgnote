package com.korgnote.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.DEFAULT_TODO_KEYWORDS
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaOptions
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The reschedule picker belongs to the entry it was opened on. A sync can add an entry ahead of it
 * while it is open, and OK must still move the entry that was long-pressed.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarEntryStateTest {
    private val today = LocalDate(2026, 9, 23)
    private val now = LocalDateTime(2026, 9, 23, 7, 0)
    private val moved = mutableListOf<String>()

    private fun agendaOf(vararg notes: Pair<String, String>) = AgendaIndex.of(mapOf(*notes), AgendaOptions(today = today))

    private fun ComposeUiTest.show(mode: CalendarMode, agenda: () -> AgendaIndex) = setContent {
        MaterialTheme {
            CalendarScreen(
                Modifier.size(900.dp, 800.dp),
                agenda = agenda(),
                initialMode = mode,
                now = now,
                actions = CalendarActions(
                    isRunning = { _, _ -> false },
                    clockIn = { _, _ -> },
                    clockOut = { _, _ -> },
                    open = { _, _ -> },
                    keywords = { DEFAULT_TODO_KEYWORDS },
                    setTodo = { _, _, _ -> },
                    reschedule = { entry, _, _, _ -> moved += entry.title },
                ),
            )
        }
    }

    /** Opens the picker on [title], lets [sync] add to the notes under it, and takes its OK. */
    private fun ComposeUiTest.rescheduleAcrossASync(title: String, sync: () -> Unit) {
        onAllNodesWithText(title, substring = true)[0].performTouchInput { longClick() }
        onNodeWithText("Change date…").performClick()
        sync()
        waitForIdle()
        onNodeWithText("OK").performClick()
        waitForIdle()
    }

    @Test
    fun aBlockOnTheGridKeepsItsPickerWhenAnEarlierOneArrives() = runComposeUiTest {
        var agenda by mutableStateOf(agendaOf("work.org" to "* Dentist <2026-09-23 Wed 15:00>\n"))
        show(CalendarMode.Day, { agenda })

        rescheduleAcrossASync("Dentist") {
            agenda = agendaOf(
                "work.org" to "* Dentist <2026-09-23 Wed 15:00>\n",
                "synced.org" to "* Stand-up <2026-09-23 Wed 09:00>\n",
            )
        }

        assertEquals(listOf("Dentist"), moved)
    }

    /** Its place in its note is what names an entry, so moved within the note it is another one: nothing stays open on it. */
    @Test
    fun aHeadingAddedAboveInTheSameNoteClosesThePicker() = runComposeUiTest {
        var agenda by mutableStateOf(agendaOf("work.org" to "* Dentist <2026-09-23 Wed 15:00>\n"))
        show(CalendarMode.Day, { agenda })

        onNodeWithText("Dentist", substring = true).performTouchInput { longClick() }
        onNodeWithText("Change date…").performClick()
        agenda = agendaOf("work.org" to "* Stand-up <2026-09-23 Wed 09:00>\n* Dentist <2026-09-23 Wed 15:00>\n")
        waitForIdle()

        onNodeWithText("OK").assertDoesNotExist()
    }

    @Test
    fun aChipInTheDaysStripKeepsItsPickerWhenAnEarlierOneArrives() = runComposeUiTest {
        var agenda by mutableStateOf(agendaOf("work.org" to "* Dentist <2026-09-23 Wed>\n"))
        show(CalendarMode.Day, { agenda })

        rescheduleAcrossASync("Dentist") {
            agenda = agendaOf(
                "work.org" to "* Dentist <2026-09-23 Wed>\n",
                "a.org" to "* Passport renewal\nDEADLINE: <2026-09-23 Wed>\n",
            )
        }

        assertEquals(listOf("Dentist"), moved)
    }

    @Test
    fun aChipInTheWeeksStripKeepsItsPickerWhenAnEarlierOneArrives() = runComposeUiTest {
        var agenda by mutableStateOf(agendaOf("work.org" to "* Dentist <2026-09-23 Wed>\n"))
        show(CalendarMode.Week, { agenda })

        rescheduleAcrossASync("Dentist") {
            agenda = agendaOf(
                "work.org" to "* Dentist <2026-09-23 Wed>\n",
                "a.org" to "* Passport renewal\nDEADLINE: <2026-09-23 Wed>\n",
            )
        }

        assertEquals(listOf("Dentist"), moved)
    }

    @Test
    fun aChipInTheMonthKeepsItsPickerWhenAnEarlierOneArrives() = runComposeUiTest {
        var agenda by mutableStateOf(agendaOf("work.org" to "* Dentist <2026-09-23 Wed>\n"))
        show(CalendarMode.Month, { agenda })

        rescheduleAcrossASync("Dentist") {
            agenda = agendaOf(
                "work.org" to "* Dentist <2026-09-23 Wed>\n",
                "a.org" to "* Passport renewal\nDEADLINE: <2026-09-23 Wed>\n",
            )
        }

        assertEquals(listOf("Dentist"), moved)
    }
}
