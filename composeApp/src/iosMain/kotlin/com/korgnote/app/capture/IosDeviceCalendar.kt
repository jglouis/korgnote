package com.korgnote.app.capture

import com.korgnote.common.org.capture.CalendarEvent
import com.korgnote.common.org.capture.EventTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import platform.EventKit.EKAuthorizationStatusFullAccess
import platform.EventKit.EKEntityType
import platform.EventKit.EKEvent
import platform.EventKit.EKEventStore
import platform.EventKit.EKRecurrenceFrequency
import platform.EventKit.EKRecurrenceRule
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import kotlin.time.Instant

/** The one event store the app uses: EventKit wants it kept, not made for each read. */
internal val eventStore: EKEventStore by lazy { EKEventStore() }

/**
 * The iPhone's calendars through EventKit: Google Calendar's among them once the Google account is
 * added in Settings, which is the only way its events reach another app. Occurrences, as on Android.
 */
class IosDeviceCalendar : DeviceCalendar {

    override suspend fun events(from: LocalDate, days: Int): List<DeviceEvent> = withContext(Dispatchers.IO) {
        if (EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent) != EKAuthorizationStatusFullAccess) {
            return@withContext emptyList()
        }
        val zone = TimeZone.currentSystemDefault()
        fun date(day: LocalDate) = NSDate.dateWithTimeIntervalSince1970(day.atStartOfDayIn(zone).epochSeconds.toDouble())
        val predicate = eventStore.predicateForEventsWithStartDate(date(from), endDate = date(from.plus(DatePeriod(days = days))), calendars = null)
        eventStore.eventsMatchingPredicate(predicate).filterIsInstance<EKEvent>()
            .sortedBy { it.startDate?.timeIntervalSince1970 ?: 0.0 }
            .mapNotNull { event -> deviceEventOf(event, zone) }
    }

    private fun deviceEventOf(event: EKEvent, zone: TimeZone): DeviceEvent? {
        val begin = event.startDate?.instant() ?: return null
        val end = event.endDate?.instant()
        val (start, until) = if (event.allDay) {
            // EventKit ends an all-day event at the last second of its last day; calendars count the day after.
            val first = begin.toLocalDateTime(zone).date
            EventTime.AllDay(first) to end?.let { EventTime.AllDay(it.toLocalDateTime(zone).date.plus(DatePeriod(days = 1))) }
        } else {
            EventTime.At(begin.toLocalDateTime(zone)) to end?.let { EventTime.At(it.toLocalDateTime(zone)) }
        }
        val rule = event.recurrenceRules?.firstOrNull() as? EKRecurrenceRule
        return DeviceEvent(
            CalendarEvent(
                summary = event.title.orEmpty().trim(),
                start = start,
                end = until,
                location = event.location?.trim()?.takeIf { it.isNotEmpty() },
                description = event.notes?.trim()?.takeIf { it.isNotEmpty() },
                url = event.URL?.absoluteString,
                repeater = rule?.let(::orgRepeaterOf),
            ),
            calendar = event.calendar?.title.orEmpty(),
        )
    }

    /**
     * Org's repeater for [rule], when it is one Org can say: every so many days, weeks, months or
     * years, for ever. Days of the week beyond one, and an end, leave the event once, as on Android.
     */
    private fun orgRepeaterOf(rule: EKRecurrenceRule): String? {
        if (rule.recurrenceEnd != null || (rule.daysOfTheWeek?.size ?: 0) > 1 || rule.interval < 1) return null
        val unit = when (rule.frequency) {
            EKRecurrenceFrequency.EKRecurrenceFrequencyDaily -> "d"
            EKRecurrenceFrequency.EKRecurrenceFrequencyWeekly -> "w"
            EKRecurrenceFrequency.EKRecurrenceFrequencyMonthly -> "m"
            EKRecurrenceFrequency.EKRecurrenceFrequencyYearly -> "y"
            else -> return null
        }
        return "+${rule.interval}$unit"
    }
}

private fun NSDate.instant(): Instant = Instant.fromEpochMilliseconds((timeIntervalSince1970 * 1000).toLong())
