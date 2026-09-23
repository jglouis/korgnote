package com.korgnote.app.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * Android reminders: one alarm per reminder, each firing [ReminderReceiver], which posts the
 * notification.
 *
 * Exact alarms, because inexact ones were measured on an emulator to be useless for this: Android
 * may deliver them up to three quarters of the time remaining late, capped at an hour — a digest
 * set for 21:23 arrived at 21:27, and one three days out was allowed until an hour past its time.
 * A reminder ten minutes before a meeting cannot arrive after it has started. `USE_EXACT_ALARM`
 * grants them from Android 13 without asking (Play reserves it for calendar and alarm apps, which
 * an agenda is); `SCHEDULE_EXACT_ALARM` is granted by default on 12. Should exact alarms be
 * refused anyway, reminders fall back to inexact ones rather than not being set.
 */
class AndroidNotificationScheduler(context: Context) : NotificationScheduler {
    private val app = context.applicationContext

    override suspend fun replaceAll(reminders: List<Reminder>) = withContext(Dispatchers.IO) {
        ReminderAlarms.replaceAll(app, reminders)
    }
}

/**
 * The alarms themselves. The plan is kept in preferences because Android forgets every alarm on
 * reboot: [RestoreRemindersReceiver] reads it back and schedules what is still ahead.
 */
object ReminderAlarms {
    const val CHANNEL_ID = "agenda_reminders"
    const val EXTRA_FILE = "com.korgnote.app.reminder.FILE"
    const val EXTRA_OFFSET = "com.korgnote.app.reminder.OFFSET"
    private const val ACTION_FIRE = "com.korgnote.app.reminder.FIRE"
    private const val EXTRA_ID = "com.korgnote.app.reminder.ID"
    private const val EXTRA_TITLE = "com.korgnote.app.reminder.TITLE"
    private const val EXTRA_BODY = "com.korgnote.app.reminder.BODY"
    private const val PREFS = "korgnote_reminders"
    private const val KEY_COUNT = "count"
    private const val KEY_PLAN = "plan"

    private val planSerializer = ListSerializer(Reminder.serializer())

    /** Cancels every alarm this app set, then sets one per reminder. */
    fun replaceAll(context: Context, reminders: List<Reminder>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alarms = context.getSystemService(AlarmManager::class.java)
        // An alarm is identified by its request code, so the previous plan's are 0 until its size.
        repeat(prefs.getInt(KEY_COUNT, 0)) { alarms.cancel(fireIntent(context, it, null)) }
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        reminders.forEachIndexed { index, reminder ->
            val at = reminder.at.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
            val fire = fireIntent(context, index, reminder)
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
        }
        prefs.edit {
            putInt(KEY_COUNT, reminders.size)
            putString(KEY_PLAN, Json.encodeToString(planSerializer, reminders))
        }
    }

    /** After a reboot or a clock change: the stored plan, minus what has already passed. */
    fun restore(context: Context) {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PLAN, null) ?: return
        val plan = runCatching { Json.decodeFromString(planSerializer, stored) }.getOrNull() ?: return
        val now = Clock.System.now()
        replaceAll(context, plan.filter { it.at.toInstant(TimeZone.currentSystemDefault()) > now })
    }

    private fun fireIntent(context: Context, requestCode: Int, reminder: Reminder?): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE)
        if (reminder != null) {
            intent.putExtra(EXTRA_ID, reminder.id)
                .putExtra(EXTRA_TITLE, reminder.title)
                .putExtra(EXTRA_BODY, reminder.body)
            reminder.file?.let { intent.putExtra(EXTRA_FILE, it) }
            reminder.offset?.let { intent.putExtra(EXTRA_OFFSET, it) }
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun post(context: Context, intent: Intent) {
        val notifications = NotificationManagerCompat.from(context)
        // Without the permission (Android 13+) or with notifications turned off, there is nothing
        // to do; the alarms stay scheduled so that granting it later is enough.
        if (!notifications.areNotificationsEnabled()) return
        ensureChannel(context)

        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.getStringExtra(EXTRA_FILE)?.let { putExtra(EXTRA_FILE, it) }
            if (intent.hasExtra(EXTRA_OFFSET)) putExtra(EXTRA_OFFSET, intent.getIntExtra(EXTRA_OFFSET, 0))
        }
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon(context))
            .setContentTitle(intent.getStringExtra(EXTRA_TITLE))
            .setContentText(body.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .apply {
                if (open != null) {
                    setContentIntent(
                        PendingIntent.getActivity(
                            context, id.hashCode(), open,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
            }
            .build()
        try {
            notifications.notify(id.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission withdrawn between the check and the post.
        }
    }

    /**
     * The status-bar icon: a monochrome one from the app when it has one — the status bar draws
     * only an icon's shape, so the launcher icon comes out as a blank blob — and the launcher icon
     * otherwise. Looked up by name because the drawable lives in the app module, not this one.
     */
    @Suppress("DiscouragedApi")
    private fun smallIcon(context: Context): Int =
        context.resources.getIdentifier("ic_notification", "drawable", context.packageName)
            .takeIf { it != 0 } ?: context.applicationInfo.icon

    private fun ensureChannel(context: Context) {
        // Channels exist from Android 8; before it, the builder's priority is what counts.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agenda reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Before timed entries in your notes, and the morning digest"
            },
        )
    }
}

/** Fired by an alarm: posts that reminder's notification. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ReminderAlarms.post(context, intent)
}

/** Reboot, app update, clock or time-zone change: sets the stored reminders again. */
class RestoreRemindersReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ReminderAlarms.restore(context)
}
