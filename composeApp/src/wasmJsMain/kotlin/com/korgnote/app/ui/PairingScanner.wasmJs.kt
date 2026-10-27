package com.korgnote.app.ui

import androidx.compose.runtime.Composable

@Composable
actual fun rememberPairingScanner(onScanned: (String) -> Unit): (() -> Unit)? = null

@Composable
actual fun rememberDeviceName(): String = "Web browser"
