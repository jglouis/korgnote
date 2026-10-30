package com.korgnote.app.notifications

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A sync made with the app closed brings what was added elsewhere — a meeting typed in on the
 * computer — and its reminder must not wait for the app to be opened.
 */
class BackgroundRemindersTest {

    private val handedOver = mutableListOf<List<Reminder>>()
    private val scheduler = object : NotificationScheduler {
        override suspend fun replaceAll(reminders: List<Reminder>) {
            handedOver += reminders
        }
    }
    private val now = LocalDateTime(2026, 9, 23, 8, 0)

    @Test
    fun theNotesAsSyncedArePlanned() = runTest {
        val store = DocumentStore(
            InMemoryReplicaStorage(mapOf("work.org" to "* Design review <2026-09-23 Wed 10:00>\n* Other\n")),
        )

        planRemindersFrom(store, scheduler, ReminderSettings(), now)

        assertEquals(listOf("Design review"), handedOver.single().map { it.title })
    }

    /** As the app reads them: a keyword one note declares is the corpus's, so its done one is done. */
    @Test
    fun theNotesAreReadWithTheirOwnKeywords() = runTest {
        val store = DocumentStore(
            InMemoryReplicaStorage(
                mapOf(
                    "setup.org" to "#+TODO: NEXT | FINISHED\n",
                    "work.org" to "* FINISHED Old call <2026-09-23 Wed 10:00>\n* NEXT New call <2026-09-23 Wed 11:00>\n",
                ),
            ),
        )

        planRemindersFrom(store, scheduler, ReminderSettings(), now)

        assertEquals(listOf("NEXT New call"), handedOver.single().map { it.title })
    }
}
