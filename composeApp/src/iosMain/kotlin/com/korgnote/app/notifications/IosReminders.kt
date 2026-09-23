package com.korgnote.app.notifications

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSDateComponents
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
import kotlin.coroutines.resume

private const val KEY_FILE = "file"
private const val KEY_OFFSET = "offset"

/**
 * iOS reminders: one calendar-triggered local notification per reminder. iOS keeps at most 64
 * pending, which [MAX_REMINDERS] stays under; the system shows them with the app closed and no
 * network.
 */
class IosNotificationScheduler : NotificationScheduler {
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun replaceAll(reminders: List<Reminder>) {
        // Asks the first time, answers at once every time after.
        val allowed = requestAuthorization()
        center.removeAllPendingNotificationRequests()
        if (!allowed) return
        for (reminder in reminders) {
            val content = UNMutableNotificationContent().apply {
                setTitle(reminder.title)
                setBody(reminder.body)
                setSound(UNNotificationSound.defaultSound())
                val target = reminder.file?.let { file ->
                    mapOf<Any?, Any?>(KEY_FILE to file, KEY_OFFSET to (reminder.offset ?: 0).toString())
                }
                if (target != null) setUserInfo(target)
            }
            val at = reminder.at
            val components = NSDateComponents().apply {
                setYear(at.year.toLong())
                setMonth((at.month.ordinal + 1).toLong())
                setDay(at.day.toLong())
                setHour(at.hour.toLong())
                setMinute(at.minute.toLong())
            }
            val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(components, repeats = false)
            center.addNotificationRequest(
                UNNotificationRequest.requestWithIdentifier(reminder.id, content, trigger),
                withCompletionHandler = null,
            )
        }
    }

    private suspend fun requestAuthorization(): Boolean = suspendCancellableCoroutine { continuation ->
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, _ ->
            continuation.resume(granted)
        }
    }
}

/**
 * Receives taps on reminders and hands them to the app, and lets a reminder show as a banner when
 * the app is already open — iOS hides it otherwise.
 */
private class ReminderCenterDelegate : NSObject(), UNUserNotificationCenterDelegateProtocol {
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        val info = didReceiveNotificationResponse.notification.request.content.userInfo
        val file = info[KEY_FILE] as? String
        if (file != null) {
            ReminderTaps.pending.value = ReminderTarget(file, (info[KEY_OFFSET] as? String)?.toIntOrNull() ?: 0)
        }
        withCompletionHandler()
    }

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) {
        withCompletionHandler(
            UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList or UNNotificationPresentationOptionSound,
        )
    }
}

// The notification centre holds its delegate weakly, so something has to keep it alive.
private val reminderDelegate = ReminderCenterDelegate()

/**
 * Routes reminder taps to the app. Called while the app starts, so that the tap that launched it
 * is delivered too.
 */
fun installReminderTapHandler() {
    UNUserNotificationCenter.currentNotificationCenter().delegate = reminderDelegate
}
