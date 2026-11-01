package com.korgnote.app.capture

import com.korgnote.common.org.capture.CalendarEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.LocalDate

/** An event in the phone's calendar, with the calendar it is in, for the picker to say. */
data class DeviceEvent(val event: CalendarEvent, val calendar: String)

/**
 * The phone's own calendar — Google Calendar's events among them, which no app shares — read for
 * the capture sheet's "From calendar": Android's calendar provider, iOS's EventKit.
 *
 * The phone holds a copy of the Google account's calendars, fetched now and then: an event just
 * made in Google Calendar is in Google's, not yet in the phone's. So the picker asks for a fetch
 * with [refresh] and reads again on each of [changes], for the event to show up while it is open.
 */
interface DeviceCalendar {
    /** The events from [from] for [days] days, soonest first; empty without the permission to read them. */
    suspend fun events(from: LocalDate, days: Int): List<DeviceEvent>

    /** Asks the phone to fetch its calendars from their accounts now, rather than when it next would. */
    suspend fun refresh() {}

    /** Says each time the phone's calendars change, as a fetch brings new events in. */
    val changes: Flow<Unit> get() = emptyFlow()
}
