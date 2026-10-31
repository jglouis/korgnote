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
