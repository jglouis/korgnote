package com.korgnote.app.capture

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Instances
import com.korgnote.common.org.capture.CalendarEvent
import com.korgnote.common.org.capture.EventTime
import com.korgnote.common.org.capture.orgRepeaterFor
import kotlinx.coroutines.Dispatchers
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

    override suspend fun events(from: LocalDate, days: Int): List<DeviceEvent> = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return@withContext emptyList()
        }
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
