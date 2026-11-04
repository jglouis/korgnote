package com.korgnote.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaOptions
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertTrue

/** An hourly repeater lands on one day several times, and each landing is a row of its own. */
@OptIn(ExperimentalTestApi::class)
class AgendaRepeatsWithinADayTest {
    private val today = LocalDate(2026, 9, 23)

    @Test
    fun theAgendaListShowsEachTimeOfAnHourlyRepeat() = runComposeUiTest {
        val agenda = AgendaIndex.of(
            mapOf("med.org" to "* Medication <2026-09-23 Wed 08:00 +4h>\n"),
            AgendaOptions(today = today),
        )
        setContent {
            MaterialTheme {
                CalendarScreen(
                    Modifier.size(390.dp, 800.dp),
                    agenda = agenda,
                    onOpenEntry = {},
                    initialMode = CalendarMode.Agenda,
                    now = LocalDateTime(2026, 9, 23, 7, 0),
                )
            }
        }
        waitForIdle()
        assertTrue(onAllNodesWithText("Medication", substring = true).fetchSemanticsNodes().size > 1)
    }
}
