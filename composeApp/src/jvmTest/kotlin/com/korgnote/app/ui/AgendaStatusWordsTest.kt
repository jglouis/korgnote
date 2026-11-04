package com.korgnote.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

/** A status in the words the calendar shows it in. */
@OptIn(ExperimentalTestApi::class)
class AgendaStatusWordsTest {

    @Test
    fun eachStatusIsWrittenOut() = runComposeUiTest {
        val words = listOf(
            AgendaStatus.Deadline to "Deadline",
            AgendaStatus.DueIn(3) to "Due in 3 d.",
            AgendaStatus.Overdue(2) to "2 d. overdue",
            AgendaStatus.ScheduledAgo(5) to "Scheduled 5 d. ago",
            AgendaStatus.Closed to "Closed",
            AgendaStatus.Starts to "Starts",
            AgendaStatus.Continues to "Continues",
            AgendaStatus.Ends to "Ends",
        )
        setContent {
            Column { words.forEach { (status, _) -> Text(status.label()) } }
        }

        words.forEach { (_, written) -> onNodeWithText(written).assertExists() }
    }
}
