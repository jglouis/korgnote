package com.korgnote.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.korgnote.app.ui.buildOrgInlineString
import com.korgnote.app.viewmodel.ClockNotifier
import com.korgnote.app.viewmodel.ClockRunning
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.clock_channel_description
import korgnote.composeapp.generated.resources.clock_channel_name
import korgnote.composeapp.generated.resources.clock_out
import korgnote.composeapp.generated.resources.clock_running_count
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/**
 * The running clock in the notification shade and the status bar: the heading clocked, a
 * chronometer counting from its start, and *Clock out*, which stops it without opening the app.
 * A tap opens the heading. With several clocks running, the newest is shown and the others
 * counted; the app lists them all.
 *
 * What a tap and *Clock out* do is the app module's: [open] opens the note at the heading, and
 * [stop] is the broadcast that stops the clock, with the app open or not.
 */
class AndroidClockNotifier(
    context: Context,
    private val open: (ClockRunning) -> Intent,
    private val stop: (ClockRunning) -> Intent,
) : ClockNotifier {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun show(clocks: List<ClockRunning>) {
        scope.launch { post(clocks) }
    }

    /** [show], returning once the notification is up, or gone. */
    suspend fun post(clocks: List<ClockRunning>) = order.withLock {
        val notifications = NotificationManagerCompat.from(app)
        val newest = clocks.firstOrNull()
        if (newest == null) {
            notifications.cancel(NOTIFICATION_ID)
            return@withLock
        }
        if (!notifications.areNotificationsEnabled()) return@withLock
        ensureChannel()
        val started = newest.start.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(smallIcon())
            .apply { accentColor()?.let(::setColor) }
            .setContentTitle(buildOrgInlineString(newest.title, viewMode = true).text)
            .apply {
                if (clocks.size > 1) setContentText(getPluralString(Res.plurals.clock_running_count, clocks.size, clocks.size))
            }
            .setWhen(started)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setContentIntent(
                PendingIntent.getActivity(app, REQUEST_OPEN, open(newest), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
            .addAction(
                0,
                getString(Res.string.clock_out),
                PendingIntent.getBroadcast(app, REQUEST_STOP, stop(newest), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
            .build()
        try {
            notifications.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission withdrawn between the check and the post.
        }
    }

    private suspend fun ensureChannel() {
        // Low: it is there to be glanced at and stopped, never to sound or pop up.
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(Res.string.clock_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(Res.string.clock_channel_description)
                setShowBadge(false)
            },
        )
    }

    /** The monochrome unicorn the reminders use, looked up by name: it lives in the app module. */
    @Suppress("DiscouragedApi")
    private fun smallIcon(): Int =
        app.resources.getIdentifier("ic_notification", "drawable", app.packageName).takeIf { it != 0 } ?: app.applicationInfo.icon

    @Suppress("DiscouragedApi")
    private fun accentColor(): Int? =
        app.resources.getIdentifier("ic_launcher_background", "color", app.packageName).takeIf { it != 0 }?.let { app.getColor(it) }

    private companion object {
        const val CHANNEL_ID = "running_clock"
        const val NOTIFICATION_ID = 0x636c6f63 // "cloc"
        const val REQUEST_OPEN = 1
        const val REQUEST_STOP = 2

        // One at a time, in order, across every notifier made: the app's and a worker's. A stop
        // shown before the start it follows would leave the notification up.
        val order = Mutex()
    }
}
