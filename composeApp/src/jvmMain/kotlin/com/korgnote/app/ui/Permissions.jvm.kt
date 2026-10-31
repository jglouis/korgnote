package com.korgnote.app.ui

import androidx.compose.runtime.Composable

// No reminders are shown here, and nothing stands between the app and the network.

@Composable
actual fun rememberNotificationPermission(): PermissionAsk? = null

@Composable
actual fun rememberLocalNetworkPermission(): PermissionAsk? = null

@Composable
actual fun rememberCalendarPermission(): PermissionAsk? = null
