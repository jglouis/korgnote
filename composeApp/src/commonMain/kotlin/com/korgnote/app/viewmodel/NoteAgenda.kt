package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.korgnote.app.notifications.NotificationScheduler
import com.korgnote.app.notifications.Reminder
import com.korgnote.app.notifications.ReminderSettings
import com.korgnote.app.notifications.ReminderTarget
import com.korgnote.app.notifications.planReminders
import com.korgnote.app.widgets.AgendaWidgets
import com.korgnote.app.widgets.NoAgendaWidgets
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaOptions
import com.korgnote.common.org.agenda.agendaEntries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.time.Instant

/** Every dated entry in the notes, for the calendar, and the reminders planned from them. */
class NoteAgenda(
    private val scope: CoroutineScope,
    private val library: NoteLibrary,
    private val today: () -> LocalDate,
    /** Where reminders go: the device's own notifications, or nowhere on desktop and web. */
    private val notifications: NotificationScheduler,
    /** Read each time reminders are planned, so a change in Settings applies on the next plan. */
    private val reminderSettings: () -> ReminderSettings,
    /** The wall-clock time reminders are planned from. */
    private val now: () -> LocalDateTime,
    /** Opens a note at a character offset in it. */
    private val open: (path: String, offset: Int) -> Unit,
    /** The home-screen widgets showing the agenda, where the platform has them. */
    private val widgets: AgendaWidgets = NoAgendaWidgets,
    /** When the notes last matched where they are kept, for the widgets to say how fresh they are. */
    private val lastSynced: () -> Instant? = { null },
) {
    /** Every dated entry in the notes, which the calendar asks for a day at a time. */
    var index by mutableStateOf(build(library.texts(), library.corpusKeywords))
        private set

    /** Rebuilds the index from the notes as they now are, and plans reminders from it. */
    fun refresh() = show(build(library.texts(), library.corpusKeywords))

    /** [refresh], with the index already built by [build]: away from the main thread, say. */
    fun show(built: AgendaIndex) {
        index = built
        refreshReminders()
        refreshWidgets()
    }

    /**
     * The index of [texts], read with [keywords] where a note declares none. Touches no state, so
     * it can be built on any thread.
     *
     * Rebuilt with everything else derived from the notes. Today is read here, so the index a day
     * old still carries over to the right day once anything changes.
     */
    fun build(texts: Map<String, String>, keywords: OrgTodoConfig): AgendaIndex =
        AgendaIndex(agendaEntries(texts, keywords), AgendaOptions(today = today()))

    /** Opens the file an agenda entry comes from, at its heading. */
    fun openEntry(entry: AgendaEntry) = open(entry.file, entry.headingOffset)

    /** The reminders last handed to [notifications]; null until the notes have been read once. */
    private var plannedReminders: List<Reminder>? = null

    /** One replacement at a time, so an older plan can never land after a newer one. */
    private val reminderLock = Mutex()

    /**
     * Plans reminders from the agenda and hands them to the device — only when the plan changed,
     * so a sync that changes nothing leaves the device's notifications alone.
     *
     * Called whenever the notes change, and by the app when it comes to the foreground, which is
     * what moves the planning window on and drops reminders that have already fired.
     */
    fun refreshReminders() {
        // Before the replica has been read the agenda is empty, and replacing with that would
        // cancel every reminder the device holds until the notes arrive.
        if (!library.loaded) return
        val plan = planReminders(index.entries, now(), reminderSettings())
        if (plan == plannedReminders) return
        plannedReminders = plan
        scope.launch {
            reminderLock.withLock {
                runCatching { notifications.replaceAll(plan) }
                    .onFailure { println("[DEBUG_LOG][NoteAgenda] Scheduling reminders failed: ${it.message}") }
            }
        }
    }

    /** The entries and sync time last handed to [widgets]. */
    private var shownOnWidgets: Pair<List<AgendaEntry>, Instant?>? = null

    /**
     * Hands the agenda to the widgets, when it or the sync time changed: a save that moves no date
     * leaves them alone. Not before the notes have been read, for the same reason as reminders: the
     * empty agenda would blank every widget until they arrive.
     */
    private fun refreshWidgets() {
        if (!library.loaded) return
        val shown = index.entries to lastSynced()
        if (shown == shownOnWidgets) return
        shownOnWidgets = shown
        widgets.show(shown.first, shown.second)
    }

    /** A tapped reminder that arrived before the notes were read, opened once they are. */
    private var pendingReminderOpen: ReminderTarget? = null

    /**
     * Opens the note a tapped reminder points at. A tap can start the app, in which case it arrives
     * before the replica has been read; it is then held until [onNotesShown].
     */
    fun openReminderTarget(target: ReminderTarget) {
        when {
            target.file in library -> open(target.file, target.offset)
            // Not read yet: hold it. Read, and still not there: the note is gone, so is the tap.
            !library.loaded -> pendingReminderOpen = target
        }
    }

    /** The notes have been read and shown: a tap held until then can be opened now. */
    internal fun onNotesShown() {
        pendingReminderOpen?.let { target ->
            pendingReminderOpen = null
            if (target.file in library) open(target.file, target.offset)
        }
    }
}
