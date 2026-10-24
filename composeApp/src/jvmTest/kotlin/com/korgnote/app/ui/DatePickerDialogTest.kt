package com.korgnote.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** The picker behind "Pick date & time…": opened on a stamp already written, and what each way out hands back. */
@OptIn(ExperimentalTestApi::class)
class DatePickerDialogTest {
    private val date = LocalDate(2026, 9, 23)
    private val picked = mutableListOf<Triple<LocalDate, LocalTime?, LocalTime?>>()

    private fun ComposeUiTest.show(time: LocalTime?, endTime: LocalTime? = null) = setContent {
        MaterialTheme {
            OrgDatePickerDialog(
                initialDate = date,
                initialTime = time,
                initialEndTime = endTime,
                onPick = { d, t, e -> picked += Triple(d, t, e) },
                onDismiss = {},
            )
        }
    }

    private fun assertPicked(time: LocalTime?, endTime: LocalTime? = null) =
        assertEquals(listOf(Triple(date, time, endTime)), picked.toList())

    @Test
    fun okOnTheDateKeepsTheTimeAlreadyWritten() = runComposeUiTest {
        show(LocalTime(10, 0))

        onNodeWithText("OK").performClick()

        // The date it opened on, not the day before: the picker's millis are read in UTC.
        assertPicked(LocalTime(10, 0))
    }

    @Test
    fun theTimeStepCanTakeTheTimeAway() = runComposeUiTest {
        show(LocalTime(10, 0), LocalTime(11, 30))

        onNodeWithText("At 10:00–11:30").performClick()
        onNodeWithText("No time").performClick()

        // The end goes with the start: there is no range without one.
        assertPicked(null)
    }

    @Test
    fun theTimeStepOpensOnTheTimeWritten() = runComposeUiTest {
        show(LocalTime(10, 30))

        onNodeWithText("At 10:30").performClick()
        onNodeWithText("OK").performClick()

        assertPicked(LocalTime(10, 30))
    }

    @Test
    fun aStampWithNoTimeOffersToAddOne() = runComposeUiTest {
        show(null)

        onNodeWithText("Add time").performClick()
        // Only a time already written can be taken away.
        onNodeWithText("No time").assertDoesNotExist()
        onNodeWithText("Back").performClick()
        onNodeWithText("OK").performClick()

        assertPicked(null)
    }

    // --- Ranges ---------------------------------------------------------------------------------

    @Test
    fun okOnTheDateKeepsTheRangeAlreadyWritten() = runComposeUiTest {
        show(LocalTime(10, 0), LocalTime(11, 30))

        onNodeWithText("OK").performClick()

        assertPicked(LocalTime(10, 0), LocalTime(11, 30))
    }

    @Test
    fun aTimeCanBeGivenAnEnd() = runComposeUiTest {
        show(LocalTime(10, 0))

        onNodeWithText("At 10:00").performClick()
        onNodeWithText("Add end").performClick()
        // Nothing to take away yet.
        onNodeWithText("No end").assertDoesNotExist()
        onNodeWithText("OK").performClick()

        // A new range starts an hour long.
        assertPicked(LocalTime(10, 0), LocalTime(11, 0))
    }

    @Test
    fun theEndStepOpensOnTheEndWritten() = runComposeUiTest {
        show(LocalTime(10, 0), LocalTime(11, 30))

        onNodeWithText("At 10:00–11:30").performClick()
        onNodeWithText("Until 11:30").performClick()
        onNodeWithText("OK").performClick()

        assertPicked(LocalTime(10, 0), LocalTime(11, 30))
    }

    @Test
    fun theEndStepCanTakeTheEndAway() = runComposeUiTest {
        show(LocalTime(10, 0), LocalTime(11, 30))

        onNodeWithText("At 10:00–11:30").performClick()
        onNodeWithText("Until 11:30").performClick()
        onNodeWithText("No end").performClick()

        assertPicked(LocalTime(10, 0))
    }
}
