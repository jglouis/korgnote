package com.korgnote.app.notifications

import com.korgnote.common.index.NoteIndex
import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.replica.DocumentStore
import kotlinx.datetime.LocalDateTime

/**
 * Every dated entry in the notes as [store] now holds them, each read with its own keywords, as the app
 * reads them. Reads only what moved since [index] last looked, and holds none of it.
 */
suspend fun agendaOf(store: DocumentStore, index: NoteIndex = NoteIndex()): List<AgendaEntry> {
    index.refresh(store)
    val facts = index.facts
    return facts.keys.sorted().flatMap { facts.getValue(it).agenda }
}

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
