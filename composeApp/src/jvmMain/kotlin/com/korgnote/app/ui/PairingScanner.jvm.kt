package com.korgnote.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.net.InetAddress

@Composable
actual fun rememberPairingScanner(onScanned: (String) -> Unit): (() -> Unit)? = null

@Composable
actual fun rememberDeviceName(): String = remember {
    runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.removeSuffix(".local")?.takeIf { it.isNotBlank() }
        ?: "Desktop"
}
