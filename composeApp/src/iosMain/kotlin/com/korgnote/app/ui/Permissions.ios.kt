package com.korgnote.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberNotificationPermission(): PermissionAsk? {
    val center = remember { UNUserNotificationCenter.currentNotificationCenter() }
    var granted by remember { mutableStateOf(false) }
    LaunchedEffect(center) {
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val allowed = settings?.authorizationStatus == UNAuthorizationStatusAuthorized
            dispatch_async(dispatch_get_main_queue()) { granted = allowed }
        }
    }
    return remember(center) {
        object : PermissionAsk {
            override val granted: Boolean get() = granted

            override fun request(onDone: (Boolean) -> Unit) {
                center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { ok, _ ->
                    // Answered off the main thread, where the screen must not be changed.
                    dispatch_async(dispatch_get_main_queue()) {
                        granted = ok
                        onDone(ok)
                    }
                }
            }
        }
    }
}

// iOS asks by itself, the first time the app reaches the local network, with the reason the app
// gives in its Info.plist.
@Composable
actual fun rememberLocalNetworkPermission(): PermissionAsk? = null
