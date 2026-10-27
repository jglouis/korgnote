package com.korgnote.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIDevice

/** None yet: on iOS the link is pasted. */
@Composable
actual fun rememberPairingScanner(onScanned: (String) -> Unit): (() -> Unit)? = null

@Composable
actual fun rememberDeviceName(): String = remember { UIDevice.currentDevice.name }
