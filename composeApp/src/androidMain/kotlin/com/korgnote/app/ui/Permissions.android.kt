package com.korgnote.app.ui

import android.Manifest
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

// Before Android 13 notifications need no permission.
@Composable
actual fun rememberNotificationPermission(): PermissionAsk? =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) null else rememberRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)

@Composable
actual fun rememberLocalNetworkPermission(): PermissionAsk? =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) null else rememberRuntimePermission(Manifest.permission.ACCESS_LOCAL_NETWORK)

@Composable
private fun rememberRuntimePermission(name: String): PermissionAsk {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED) }
    var waiting by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        waiting?.invoke(ok)
        waiting = null
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
