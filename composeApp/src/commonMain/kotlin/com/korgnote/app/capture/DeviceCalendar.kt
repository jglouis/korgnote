package com.korgnote.app.capture

import com.korgnote.common.org.capture.CalendarEvent
import kotlinx.datetime.LocalDate

/** An event in the phone's calendar, with the calendar it is in, for the picker to say. */
data class DeviceEvent(val event: CalendarEvent, val calendar: String)

/**
 * The phone's own calendar — Google Calendar's events among them, which no app shares — read for
 * the capture sheet's "From calendar": Android's calendar provider, iOS's EventKit.
 */
interface DeviceCalendar {
    /** The events from [from] for [days] days, soonest first; empty without the permission to read them. */
    suspend fun events(from: LocalDate, days: Int): List<DeviceEvent>
}
