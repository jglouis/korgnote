package com.korgnote.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.korgnote.app.viewmodel.BackgroundSync
import com.korgnote.app.widget.WidgetStore
import com.korgnote.app.widget.redrawWidgets
import com.korgnote.app.widget.syncInBackground
import com.korgnote.common.replica.DocumentStore
import org.koin.mp.KoinPlatform
import java.util.concurrent.TimeUnit

/**
 * Syncs with the app closed: every hour, and once the network is back after an edit could not be
 * sent. Without it a meeting typed in on the computer got no reminder on the phone until the app
 * was opened, and an edit made offline stayed on the phone until then too.
 *
 * Hourly needs no network: offline, it still plans reminders from the notes as they are, which
 * moves the planning window on.
 */
class BackgroundSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val problem = syncInBackground(applicationContext)
        redrawWidgets(applicationContext)
        // Sent for an edit that could not be: the network is back, but the server may not be.
        val waiting = problem == WidgetStore.PROBLEM_SERVER &&
            tags.contains(WHEN_ONLINE) &&
            KoinPlatform.getKoin().getOrNull<DocumentStore>()?.dirtyPaths()?.isNotEmpty() == true
        return if (waiting && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    companion object {
        private const val HOURLY = "korgnote-background-sync"
        private const val WHEN_ONLINE = "korgnote-sync-when-online"
        private const val MAX_ATTEMPTS = 5

        /** Every hour, from now on; a second call keeps the schedule it finds. */
        fun scheduleHourly(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                HOURLY,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BackgroundSyncWorker>(1, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .build(),
            )
        }

        /** Once, as soon as there is a network; asked again while waiting, still once. */
        fun whenOnline(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WHEN_ONLINE,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BackgroundSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .addTag(WHEN_ONLINE)
                    .build(),
            )
        }
    }
}

/** The app's way to ask for [BackgroundSyncWorker.whenOnline]. */
class AndroidBackgroundSync(private val context: Context) : BackgroundSync {
    override fun whenOnline() = BackgroundSyncWorker.whenOnline(context)
}
