package com.korgnote.app.notifications

import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.org.agenda.agendaEntries
import com.korgnote.common.replica.DocumentStore
import kotlinx.datetime.LocalDateTime

/** Every dated entry in the notes as [store] now holds them, each read with its own keywords, as the app reads them. */
suspend fun agendaOf(store: DocumentStore): List<AgendaEntry> =
    agendaEntries(store.paths().mapNotNull { path -> store.read(path)?.let { path to it } }.toMap())

/**
 * Plans reminders from [entries], for a sync made with the app closed: a meeting typed in on another
 * computer gets its reminder without waiting for the app to be opened, and the planning window moves
 * on even when nothing was synced.
 */
suspend fun planRemindersFrom(
    entries: List<AgendaEntry>,
    notifications: NotificationScheduler,
    settings: ReminderSettings,
    now: LocalDateTime,
    words: ReminderWords = ReminderWords(),
) {
    notifications.replaceAll(planReminders(entries, now, settings, words))
}
