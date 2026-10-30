package com.korgnote.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.data_device_name_desktop
import org.jetbrains.compose.resources.stringResource
import java.net.InetAddress

@Composable
actual fun rememberPairingScanner(onScanned: (String) -> Unit): (() -> Unit)? = null

@Composable
actual fun rememberDeviceName(): String {
    val fallback = stringResource(Res.string.data_device_name_desktop)
    return remember(fallback) {
        runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.removeSuffix(".local")?.takeIf { it.isNotBlank() }
            ?: fallback
    }
}
