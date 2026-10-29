package com.korgnote.common

import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.org.agenda.agendaEntries
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Agenda entries kept as JSON, as the Android widgets keep them: what comes back must be the same
 * entries, every part of every stamp included, or the widget would show another agenda than the app.
 */
class AgendaSerializationTest {
    private val serializer = ListSerializer(AgendaEntry.serializer())

    @Test
    fun entriesComeBackAsTheyWent() {
        val entries = agendaEntries(
            mapOf(
                "work.org" to """
                    #+FILETAGS: :work:
                    * TODO [#A] Quarterly report :finance:
                    DEADLINE: <2026-09-30 Wed -5d> SCHEDULED: <2026-09-25 Fri 09:00-10:30>
                    * Stand-up <2026-09-21 Mon 09:30 .+1d>
                    * Trip
                    <2026-10-02 Fri>--<2026-10-05 Mon>
                    * DONE Water the plants
                    SCHEDULED: <2026-09-20 Sun ++1w --2d>
                """.trimIndent(),
            ),
        )
        assertTrue(entries.size >= 5)

        val json = Json.encodeToString(serializer, entries)

        assertEquals(entries, Json.decodeFromString(serializer, json))
    }
}
