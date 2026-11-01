package com.korgnote.app.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.korgnote.app.capture.notesStore
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.data.chosenNotesSource
import com.korgnote.app.notifications.AndroidClockNotifier
import com.korgnote.app.sync.BackgroundSyncWorker
import com.korgnote.app.viewmodel.ClockRunning
import com.korgnote.app.widget.WidgetIntents
import com.korgnote.app.widget.WidgetStore
import com.korgnote.app.widget.redrawWidgets
import com.korgnote.app.widget.syncInBackground
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.edit.clockOut
import com.korgnote.common.org.edit.nodeAt
import com.korgnote.common.org.edit.runningClocks
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.replica.DocumentStore
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.mp.KoinPlatform
import kotlin.time.Clock

/**
 * The running clock's notification, as the app shows it: a tap opens the note at the heading, and
 * *Clock out* is handed to [ClockOutReceiver].
 */
fun clockNotifier(context: Context): AndroidClockNotifier = AndroidClockNotifier(
    context,
    open = { WidgetIntents.openNote(context, it.path, it.headingStart) },
    stop = { ClockOutReceiver.intent(context, it) },
)

/** The notification's *Clock out*: hands the stop to [ClockOutWorker], at the minute it was tapped. */
class ClockOutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra(EXTRA_PATH) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val start = intent.getStringExtra(EXTRA_START) ?: return
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        ClockOutWorker.start(context, path, title, start, now.toString())
    }

    companion object {
        private const val ACTION = "com.korgnote.app.clock.CLOCK_OUT"
        internal const val EXTRA_PATH = "com.korgnote.app.clock.PATH"
        internal const val EXTRA_TITLE = "com.korgnote.app.clock.TITLE"
        internal const val EXTRA_START = "com.korgnote.app.clock.START"

        fun intent(context: Context, clock: ClockRunning): Intent =
            Intent(context, ClockOutReceiver::class.java).setAction(ACTION)
                .putExtra(EXTRA_PATH, clock.path)
                .putExtra(EXTRA_TITLE, clock.title)
                .putExtra(EXTRA_START, clock.start.toString())
    }
}

/**
 * Stops a clock from its notification, with the app open or not: in the notes where they are
 * kept, as a capture is filed, then sent on. The heading is found by its title and start, which
 * a sync since the notification was posted cannot have moved.
 */
class ClockOutWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val path = inputData.getString(KEY_PATH) ?: return Result.success()
        val title = inputData.getString(KEY_TITLE) ?: return Result.success()
        val start = inputData.getString(KEY_START)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return Result.success()
        val at = inputData.getString(KEY_AT)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return Result.success()
        val koin = KoinPlatform.getKoin()
        val source = koin.get<SettingsRepository>().chosenNotesSource() ?: return Result.success()
        val store = koin.notesStore(source)
        store.read(path)?.let { text ->
            val doc = text.toOrgDocument()
            val clock = doc.runningClocks().firstOrNull { it.title == title && it.start == start }
            val heading = clock?.let { doc.nodeAt<OrgSection>(it.headingStart) }
            if (heading != null) store.save(path, doc.clockOut(heading, at).text)
        }
        // At once, rather than after the sync: the tap should take the notification away.
        clockNotifier(applicationContext).post(runningIn(store))
        // As a capture's: the notes, the widgets' agenda and reminders, and the app if it is open.
        val problem = syncInBackground(applicationContext)
        redrawWidgets(applicationContext)
        if (problem == WidgetStore.PROBLEM_SERVER) BackgroundSyncWorker.whenOnline(applicationContext)
        // A clock started elsewhere may have come in with the sync.
        clockNotifier(applicationContext).post(runningIn(store))
        return Result.success()
    }

    /** The clocks running in [store]'s notes, newest first, as the app lists them. */
    private suspend fun runningIn(store: DocumentStore): List<ClockRunning> =
        store.paths().flatMap { path ->
            val text = store.read(path)?.takeIf { "CLOCK:" in it } ?: return@flatMap emptyList()
            text.toOrgDocument().runningClocks().map { ClockRunning(path, it.headingStart, it.title, it.start) }
        }.sortedWith(compareByDescending<ClockRunning> { it.start }.thenBy { it.path })

    companion object {
        private const val KEY_PATH = "path"
        private const val KEY_TITLE = "title"
        private const val KEY_START = "start"
        private const val KEY_AT = "at"

        fun start(context: Context, path: String, title: String, start: String, at: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "korgnote-clock-out",
                // A second tap while the first is written waits its turn; it then finds nothing to stop.
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ClockOutWorker>()
                    .setInputData(workDataOf(KEY_PATH to path, KEY_TITLE to title, KEY_START to start, KEY_AT to at))
                    .build(),
            )
        }
    }
}
