package com.korgnote.app.widget

import android.content.Context
import android.content.Intent
import com.korgnote.app.MainActivity
import com.korgnote.app.notifications.ReminderAlarms
import com.korgnote.app.ui.CalendarMode
import com.korgnote.app.widgets.CalendarTarget
import com.korgnote.app.widgets.CalendarTaps
import kotlinx.datetime.LocalDate

/**
 * What a tap on a widget opens: a note at a heading, the way a reminder's tap does, or the calendar
 * at a view and a day. Over the app if it is open, as a reminder's tap does too.
 */
object WidgetIntents {
    private const val EXTRA_CALENDAR_MODE = "com.korgnote.app.widget.CALENDAR_MODE"
    private const val EXTRA_CALENDAR_DATE = "com.korgnote.app.widget.CALENDAR_DATE"

    fun openApp(context: Context): Intent = app(context)

    fun openNote(context: Context, file: String, offset: Int): Intent = app(context)
        .putExtra(ReminderAlarms.EXTRA_FILE, file)
        .putExtra(ReminderAlarms.EXTRA_OFFSET, offset)

    fun openCalendar(context: Context, mode: CalendarMode, date: LocalDate): Intent = app(context)
        .putExtra(EXTRA_CALENDAR_MODE, mode.name)
        .putExtra(EXTRA_CALENDAR_DATE, date.toString())

    /** Hands a tap on a widget's calendar to the app, if [intent] is one. */
    fun deliver(intent: Intent?) {
        val date = intent?.getStringExtra(EXTRA_CALENDAR_DATE) ?: return
        val mode = intent.getStringExtra(EXTRA_CALENDAR_MODE)
        val target = runCatching {
            CalendarTarget(CalendarMode.valueOf(mode ?: CalendarMode.Month.name), LocalDate.parse(date))
        }.getOrNull()
        if (target != null) CalendarTaps.pending.value = target
        // Consumed: a rotation recreates the activity with the same intent.
        intent.removeExtra(EXTRA_CALENDAR_DATE)
    }

    private fun app(context: Context) = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
