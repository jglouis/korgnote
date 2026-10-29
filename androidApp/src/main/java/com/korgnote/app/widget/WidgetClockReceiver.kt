package com.korgnote.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Redraws the widgets when the day changes under them — at midnight, and when the clock or the time
 * zone is set — since what is today, overdue or due soon changes with it, and nothing in the notes
 * does.
 */
class WidgetClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                redrawWidgets(context.applicationContext)
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        private const val ACTION_MIDNIGHT = "com.korgnote.app.widget.MIDNIGHT"

        /**
         * Sets the redraw for the coming midnight, replacing the one already set. Each widget sets it
         * when it draws, so it is there for as long as a widget is, a reboot included, and stops
         * once the last one is removed.
         *
         * Not exact, and not waking the phone: within ten minutes of midnight is soon enough, and a
         * phone asleep has nobody looking at its home screen. It redraws when it wakes.
         */
        fun scheduleMidnight(context: Context) {
            val zone = TimeZone.currentSystemDefault()
            val midnight = Clock.System.todayIn(zone).plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
            val intent = Intent(context, WidgetClockReceiver::class.java).setAction(ACTION_MIDNIGHT)
            val pending = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            context.getSystemService(AlarmManager::class.java).setWindow(
                AlarmManager.RTC,
                midnight.toEpochMilliseconds(),
                10.minutes.inWholeMilliseconds,
                pending,
            )
        }
    }
}
