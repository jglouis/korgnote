package com.korgnote.app.notifications

import com.korgnote.common.org.agenda.agendaEntries
import com.korgnote.common.replica.DocumentStore
import kotlinx.datetime.LocalDateTime

/**
 * Plans reminders from the notes as [store] now holds them, for a sync made with the app closed:
 * a meeting typed in on another computer gets its reminder without waiting for the app to be
 * opened, and the planning window moves on even when nothing was synced.
 *
 * The notes are read as the app reads them, with the keywords the whole corpus declares.
 */
suspend fun planRemindersFrom(
    store: DocumentStore,
    notifications: NotificationScheduler,
    settings: ReminderSettings,
    now: LocalDateTime,
    words: ReminderWords = ReminderWords(),
) {
    val texts = store.paths().mapNotNull { path -> store.read(path)?.let { path to it } }.toMap()
    notifications.replaceAll(planReminders(agendaEntries(texts), now, settings, words))
}
