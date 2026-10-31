package com.korgnote.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

// Before Android 13 notifications need no permission.
@Composable
actual fun rememberNotificationPermission(): PermissionAsk? =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        null
    } else {
        rememberRuntimePermission(Manifest.permission.POST_NOTIFICATIONS, Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    }

@Composable
actual fun rememberLocalNetworkPermission(): PermissionAsk? =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) null else rememberRuntimePermission(Manifest.permission.ACCESS_LOCAL_NETWORK)

/**
 * [name] as a [PermissionAsk]. Once it has been refused for good, Android shows no prompt any more
 * and answers no at once: asking then opens [settingsAction], the system screen where it can still
 * be allowed, rather than a button that does nothing.
 */
@Composable
private fun rememberRuntimePermission(name: String, settingsAction: String? = null): PermissionAsk {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED) }
    var waiting by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        val refusedForGood = !ok && context.activity()?.shouldShowRequestPermissionRationale(name) == false
        if (refusedForGood && settingsAction != null) {
            context.startActivity(
                Intent(settingsAction)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        waiting?.invoke(ok)
        waiting = null
    }
    // Allowed in the system's settings meanwhile: seen when the app comes back.
    LifecycleResumeEffect(name) {
        granted = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }
    return remember(launcher) {
        object : PermissionAsk {
            override val granted: Boolean get() = granted

            override fun request(onDone: (Boolean) -> Unit) {
                waiting = onDone
                launcher.launch(name)
            }
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
actual fun rememberCalendarPermission(): PermissionAsk? = rememberRuntimePermission(Manifest.permission.READ_CALENDAR)
