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
import com.korgnote.app.data.NotesSource
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.data.chosenNotesSource
import com.korgnote.app.di.WEBDAV
import com.korgnote.app.files.NoteFolderAccess
import com.korgnote.app.widgets.WidgetSyncs
import com.korgnote.common.org.agenda.agendaEntries
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.FolderNoteStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.CancellationException
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
            WidgetStore.setProblem(context, syncNotes(context))
        } finally {
            WidgetStore.setSyncing(context, false)
            redrawWidgets(context)
        }
        // The app, if it is open, reads the notes the sync may have changed under it.
        WidgetSyncs.finished.tryEmit(Unit)
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
 * Syncs the notes where they are kept, as the app does when it opens — the replica with the
 * server, or a look at the folder — and keeps the agenda they now have for the widgets. Null when
 * that got through, or else what to say on the widget instead.
 *
 * What has been read is kept either way: offline, the replica still holds the notes as last synced,
 * which is what the widget showed already.
 */
private suspend fun syncNotes(context: Context): String? {
    val koin = KoinPlatform.getKoin()
    val source = koin.get<SettingsRepository>().chosenNotesSource() ?: return "Open KorgNote to set it up"
    var synced: Instant? = null
    var problem: String? = null
    val store = when (source) {
        NotesSource.Server -> {
            try {
                koin.get<SyncEngine>().sync()
                synced = Clock.System.now()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                println("[DEBUG_LOG][WidgetSync] Sync failed: ${t.message}")
                problem = "Server unreachable"
            }
            koin.get<DocumentStore>()
        }
        // The same, with the WebDAV sync's own engine and replica.
        NotesSource.WebDav -> {
            try {
                koin.get<SyncEngine>(WEBDAV).sync()
                synced = Clock.System.now()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                println("[DEBUG_LOG][WidgetSync] WebDAV sync failed: ${t.message}")
                problem = "Server unreachable"
            }
            koin.get<DocumentStore>(WEBDAV)
        }
        is NotesSource.Folder -> {
            // Only read here, never written, so it never makes a conflict copy to name.
            val storage = FolderNoteStorage(koin.get<NoteFolderAccess>().open(source.location)) { "" }
            storage.rescan()
            if (storage.problem == null) synced = Clock.System.now() else problem = "Folder unreachable"
            DocumentStore(storage)
        }
    }
    val texts = store.paths().mapNotNull { path -> store.read(path)?.let { path to it } }.toMap()
    WidgetStore.save(context, agendaEntries(texts), synced)
    return problem
}
