package com.korgnote.app.capture

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.data.chosenNotesSource
import com.korgnote.app.sync.BackgroundSyncWorker
import com.korgnote.app.widget.WidgetStore
import com.korgnote.app.widget.redrawWidgets
import com.korgnote.app.widget.syncInBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform
import java.io.File

/**
 * Files what the share sheet captured into the notes and sends it on, with the app open or not:
 * the capture sheet closes as soon as the capture is queued, and Android may end its activity
 * before a sync over a slow network is done.
 */
class CaptureWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val koin = KoinPlatform.getKoin()
        val source = koin.get<SettingsRepository>().chosenNotesSource() ?: return Result.success()
        koin.get<CaptureFiler>().fileWaiting(koin.notesStore(source))
        // As a widget's sync does: the notes, the widgets' agenda and reminders, and the app if open.
        val problem = syncInBackground(applicationContext)
        redrawWidgets(applicationContext)
        if (problem == WidgetStore.PROBLEM_SERVER) BackgroundSyncWorker.whenOnline(applicationContext)
        return Result.success()
    }

    companion object {
        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "korgnote-capture",
                // A second capture while the first is filed waits its turn, and is filed by it.
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CaptureWorker>().build(),
            )
        }
    }
}

/** The capture queue as files in the app's own storage, which nothing but the app reads. */
class FileCaptureQueueFolder(private val directory: File) : CaptureQueueFolder {
    override suspend fun names(): List<String> = withContext(Dispatchers.IO) {
        directory.list()?.toList().orEmpty()
    }

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        File(directory, name).takeIf { it.isFile }?.readText()
    }

    override suspend fun write(name: String, text: String) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        // Whole or not at all: a capture read half written would be lost as unreadable.
        val partial = File(directory, "$name.partial")
        partial.writeText(text)
        check(partial.renameTo(File(directory, name))) { "Could not queue $name" }
    }

    override suspend fun delete(name: String) {
        withContext(Dispatchers.IO) { File(directory, name).delete() }
    }
}
