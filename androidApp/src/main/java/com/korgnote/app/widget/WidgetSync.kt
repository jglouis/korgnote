package com.korgnote.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.korgnote.app.capture.NotesBackend
import com.korgnote.app.capture.backendFor
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.data.chosenNotesSource
import com.korgnote.app.notifications.NotificationScheduler
import com.korgnote.app.notifications.agendaOf
import com.korgnote.app.notifications.planRemindersFrom
import com.korgnote.app.notifications.reminderWords
import com.korgnote.app.sync.BackgroundSyncWorker
import com.korgnote.app.widgets.WidgetSyncs
import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.replica.DocumentStore
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.mp.KoinPlatform
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A widget's sync button: starts [WidgetSyncWorker], and returns at once. The widgets say they are
 * syncing from the tap, rather than from when the worker gets going a few seconds later.
 */
class SyncFromWidget : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetStore.setSyncing(context, true)
        WidgetSyncWorker.start(context)
    }
}

/**
 * Brings the notes up to date from a widget, with the app closed or not, then redraws the widgets.
 *
 * A worker rather than the tap's own handler, which Android may end before a sync over a slow
 * network is done; once, so that tapping again while it runs does not start a second one.
 */
class WidgetSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        // Again, for a sync started some other way, or whose tap was long enough ago to have lapsed.
        WidgetStore.setSyncing(context, true)
        redrawWidgets(context)
        try {
            syncInBackground(context)
        } finally {
            WidgetStore.setSyncing(context, false)
            redrawWidgets(context)
        }
        return Result.success()
    }

    companion object {
        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "korgnote-widget-sync",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WidgetSyncWorker>().build(),
            )
        }
    }
}

/**
 * What a sync with the app closed does: syncs the notes where they are kept, then hands the agenda
 * they now have to the widgets and plans reminders from it, so a meeting typed in on the computer
 * gets its reminder without the app being opened. Done by a widget's sync button and by
 * [com.korgnote.app.sync.BackgroundSyncWorker].
 *
 * Returns which of [WidgetStore]'s problems stopped the sync, or null; the widgets say it too.
 */
suspend fun syncInBackground(context: Context): String? {
    val synced = syncNotes(context)
    WidgetStore.setProblem(context, synced.problem)
    synced.store?.let { store ->
        val entries = agendaOf(store)
        WidgetStore.save(context, entries, synced.at)
        planReminders(entries)
    }
    // The app, if it is open, reads the notes the sync may have changed under it.
    WidgetSyncs.finished.tryEmit(Unit)
    return synced.problem
}

/**
 * [syncInBackground] after something was written with the app closed, as a capture is: the notes, the
 * widgets' agenda and reminders, and the app if it is open. A server that could not be reached is
 * tried again once the network is back.
 */
suspend fun syncAndRedraw(context: Context) {
    val problem = syncInBackground(context)
    redrawWidgets(context)
    if (problem == WidgetStore.PROBLEM_SERVER) BackgroundSyncWorker.whenOnline(context)
}

/** The notes after a sync: where they are kept now, when it got through, and what stopped it if not. */
private class SyncedNotes(val store: DocumentStore?, val at: Instant?, val problem: String?)

/** Reminders from [entries], worded in the app's language. */
private suspend fun planReminders(entries: List<AgendaEntry>) {
    val koin = KoinPlatform.getKoin()
    try {
        planRemindersFrom(
            entries,
            koin.get<NotificationScheduler>(),
            koin.get<SettingsRepository>().getReminderSettings(),
            Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
            reminderWords(),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        println("[DEBUG_LOG][WidgetSync] Planning reminders failed: ${t.message}")
    }
}

/**
 * Syncs the notes where they are kept, as the app does when it opens — the replica with the
 * server, or a look at the folder.
 *
 * What has been read is kept either way: offline, the replica still holds the notes as last synced,
 * which is what the widget showed already, and what reminders are planned from.
 */
private suspend fun syncNotes(context: Context): SyncedNotes {
    val koin = KoinPlatform.getKoin()
    val source = koin.get<SettingsRepository>().chosenNotesSource()
        ?: return SyncedNotes(store = null, at = null, problem = WidgetStore.PROBLEM_SETUP)
    var synced: Instant? = null
    var problem: String? = null
    val backend = koin.backendFor(source)
    when (backend) {
        is NotesBackend.Replica -> {
            try {
                backend.engine().sync()
                synced = Clock.System.now()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                println("[DEBUG_LOG][WidgetSync] Sync failed: ${t.message}")
                problem = WidgetStore.PROBLEM_SERVER
            }
        }
        is NotesBackend.Folder -> {
            backend.storage.rescan()
            if (backend.storage.problem == null) synced = Clock.System.now() else problem = WidgetStore.PROBLEM_FOLDER
        }
    }
    return SyncedNotes(backend.store, synced, problem)
}
