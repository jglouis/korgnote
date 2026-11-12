package com.korgnote.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaOptions
import com.korgnote.common.org.agenda.TodoEntry
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** The agenda's filter by keyword and tag, and the TODO list opened from the top of the agenda. */
@OptIn(ExperimentalTestApi::class)
class AgendaFilterAndTodosTest {
    private val now = LocalDateTime(2030, 3, 4, 9, 0)

    private val agenda = AgendaIndex.of(
        mapOf(
            "garden.org" to """
                #+TODO: TODO NEXT | DONE
                * NEXT Dig the beds :outside:
                SCHEDULED: <2030-03-04 Mon>
                * TODO Buy seeds :shop:
                * TODO Order a hose :shop:
                DEADLINE: <2030-03-01 Fri>
                * Seed swap <2030-03-05 Tue 14:00> :outside:
            """.trimIndent(),
        ),
        AgendaOptions(today = now.date),
    )

    private val opened = mutableListOf<TodoEntry>()

    private fun ComposeUiTest.show() = setContent {
        MaterialTheme {
            CalendarScreen(
                Modifier.size(390.dp, 800.dp),
                agenda = agenda,
                initialMode = CalendarMode.Agenda,
                now = now,
                onOpenTodo = { opened += it },
            )
        }
    }

    private fun ComposeUiTest.shown(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aTappedTagNarrowsTheAgendaAndItsChipUndoesIt() = runComposeUiTest {
        show()
        assertEquals(false, shown("Stop filtering"), "nothing is added to the screen until a filter is on")
        onAllNodesWithText("outside", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(true, it.isNotEmpty()) }
        onAllNodesWithText("outside", useUnmergedTree = true)[0].performClick()
        waitForIdle()
        assertEquals(true, shown("Dig the beds"))
        assertEquals(true, shown("Seed swap"))
        assertEquals(false, shown("Order a hose"), "an entry without the tag is hidden")
        onNodeWithContentDescription("Stop filtering by outside").performClick()
        waitForIdle()
        assertEquals(true, shown("Order a hose"))
    }

    @Test
    fun theMenusFilterPicksAKeyword() = runComposeUiTest {
        show()
        onNodeWithContentDescription("Calendar options").performClick()
        onNodeWithText("Filter…").performClick()
        onNodeWithTag("agendaFilterDialog").assertExists()
        onNodeWithText("NEXT").performClick()
        onNodeWithText("OK").performClick()
        waitForIdle()
        assertEquals(true, shown("Dig the beds"))
        assertEquals(false, shown("Order a hose"))
        assertEquals(false, shown("Seed swap"))
    }

    @Test
    fun theTodosRowOpensTheListAndBackReturns() = runComposeUiTest {
        show()
        onNodeWithText("TODOs · 3").assertExists()
        onNodeWithTag("todosRow").performClick()
        onNodeWithTag("todoList").assertExists()
        // Grouped as declared: TODO before NEXT; the undated task is there too.
        assertEquals(true, shown("TODO · 2"))
        assertEquals(true, shown("NEXT · 1"))
        assertEquals(true, shown("Buy seeds"))
        assertEquals(true, shown("3 d. overdue"))
        onNodeWithText("Buy seeds").performClick()
        assertEquals(listOf("Buy seeds"), opened.map { it.title })

        // A keyword's heading narrows the list to it, as a keyword does in the agenda.
        onNodeWithText("NEXT · 1").performClick()
        waitForIdle()
        assertEquals(false, shown("Buy seeds"))
        assertEquals(true, shown("TODOs · 1"))

        onNodeWithContentDescription("Back").performClick()
        onNodeWithTag("agendaList").assertExists()
        assertEquals(true, shown("TODOs · 1"), "the filter carries over to the agenda's count")
    }

    @Test
    fun noTodosNoRow() = runComposeUiTest {
        setContent {
            MaterialTheme {
                CalendarScreen(
                    Modifier.size(390.dp, 800.dp),
                    agenda = AgendaIndex.of(mapOf("a.org" to "* Meeting <2030-03-04 Mon 10:00>\n"), AgendaOptions(today = now.date)),
                    initialMode = CalendarMode.Agenda,
                    now = now,
                )
            }
        }
        onNodeWithTag("agendaList").assertExists()
        assertEquals(false, shown("TODOs"))
    }
}
