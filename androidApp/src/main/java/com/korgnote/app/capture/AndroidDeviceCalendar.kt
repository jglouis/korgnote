package com.korgnote.app.capture

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances
import android.util.Log
import com.korgnote.common.org.capture.CalendarEvent
import com.korgnote.common.org.capture.EventTime
import com.korgnote.common.org.capture.orgRepeaterFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The phone's calendars through Android's calendar provider, where the Google account's calendars
 * are synced: occurrences rather than events, so a weekly meeting is listed each week, at its time.
 */
class AndroidDeviceCalendar(private val context: Context) : DeviceCalendar {

    private fun allowed() =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /**
     * A sync of each account the calendars come from, as the calendar app's own "Refresh" asks: an
     * event made on the web or another phone is otherwise in the provider only once Google's push
     * comes through, and on a phone that pushes rarely that can be the next day's periodic sync.
     *
     * Android ignores, without a word, a sync asked of an account the app may not see: the Google
     * account is one until it is picked once in the account chooser, which the picker offers.
     */
    override suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        val now = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        calendarAccounts(context).forEach { account ->
            runCatching { ContentResolver.requestSync(account, CalendarContract.AUTHORITY, now) }
                .onFailure { Log.w(TAG, "Could not ask the calendars to sync", it) }
        }
    }

    /** The provider's own word that something in it changed, as a sync writes new events. */
    override val changes: Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val watching = allowed() && runCatching {
            context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        }.onFailure { Log.w(TAG, "Could not watch the calendars", it) }.isSuccess
        awaitClose { if (watching) context.contentResolver.unregisterContentObserver(observer) }
    }

    override suspend fun events(from: LocalDate, days: Int): List<DeviceEvent> = withContext(Dispatchers.IO) {
        if (!allowed()) return@withContext emptyList()
        val zone = TimeZone.currentSystemDefault()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.atStartOfDayIn(zone).toEpochMilliseconds())
            ContentUris.appendId(it, from.plus(DatePeriod(days = days)).atStartOfDayIn(zone).toEpochMilliseconds())
        }.build()
        val columns = arrayOf(
            Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY,
            Instances.EVENT_LOCATION, Instances.DESCRIPTION, Instances.RRULE, Instances.CALENDAR_DISPLAY_NAME,
        )
        val events = mutableListOf<DeviceEvent>()
        context.contentResolver.query(uri, columns, "${Instances.VISIBLE} = 1", null, "${Instances.BEGIN} ASC")?.use { rows ->
            while (rows.moveToNext()) {
                val begin = Instant.fromEpochMilliseconds(rows.getLong(1))
                val end = Instant.fromEpochMilliseconds(rows.getLong(2))
                // An all-day event is stored at midnight UTC, whatever zone the phone is in.
                val (start, until) = if (rows.getInt(3) == 1) {
                    EventTime.AllDay(begin.toLocalDateTime(TimeZone.UTC).date) to EventTime.AllDay(end.toLocalDateTime(TimeZone.UTC).date)
                } else {
                    EventTime.At(begin.toLocalDateTime(zone)) to EventTime.At(end.toLocalDateTime(zone))
                }
                val rule = rows.getString(6)?.takeIf { it.isNotBlank() }
                events += DeviceEvent(
                    CalendarEvent(
                        summary = rows.getString(0).orEmpty().trim(),
                        start = start,
                        end = until,
                        location = rows.getString(4)?.trim()?.takeIf { it.isNotEmpty() },
                        description = rows.getString(5)?.trim()?.takeIf { it.isNotEmpty() },
                        repeater = rule?.let { orgRepeaterFor(it, start) },
                        rule = rule,
                    ),
                    calendar = rows.getString(7).orEmpty(),
                )
            }
        }
        events
    }
}

private const val TAG = "DeviceCalendar"
