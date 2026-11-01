package com.korgnote.app.ui

import androidx.compose.runtime.Composable

/** A runtime permission the app may ask for: whether it has it, and asking for it. */
interface PermissionAsk {
    /** State: true once granted, including after [request] returns. */
    val granted: Boolean

    /** Shows the system's prompt; [onDone] receives whether the permission was granted. */
    fun request(onDone: (Boolean) -> Unit)
}

/**
 * Showing notifications, which reminders and the morning digest need. Null where the app needs no
 * permission for them, or cannot show them at all.
 */
@Composable
expect fun rememberNotificationPermission(): PermissionAsk?

/**
 * Reaching addresses on the local network, where the computer with the server usually is. Null
 * where no such permission exists: before Android 17, and everywhere but Android.
 */
@Composable
expect fun rememberLocalNetworkPermission(): PermissionAsk?

/**
 * Reading the phone's calendar, for capturing one of its events. Null where there is no calendar to
 * read: desktop and web.
 */
@Composable
expect fun rememberCalendarPermission(): PermissionAsk?

/**
 * Android's leave to ask the calendars' accounts to sync, for an event just made in Google Calendar
 * elsewhere to reach the phone at once: Android ignores a sync asked of an account the app may not
 * see, and the Google account is one until it is picked once in Android's account chooser.
 */
interface CalendarAccountAccess {
    /** Whether a calendar's account is still hidden from the app. Reads the calendars: not on the main thread. */
    suspend fun missing(): Boolean

    /** Whether the chooser was shown before, so that it is not shown unasked again after a no. */
    val askedBefore: Boolean

    /** Shows Android's account chooser; [onDone] once it closes, whatever was picked. */
    fun request(onDone: () -> Unit)
}

/** The leave for the calendars' accounts to sync on asking. Null where none is needed: iOS fetches on asking. */
@Composable
expect fun rememberCalendarAccountAccess(): CalendarAccountAccess?
