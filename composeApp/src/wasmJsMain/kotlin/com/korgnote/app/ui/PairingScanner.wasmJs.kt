package com.korgnote.app.ui

import androidx.compose.runtime.Composable
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.data_device_name_web
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun rememberPairingScanner(onScanned: (String) -> Unit): (() -> Unit)? = null

@Composable
actual fun rememberDeviceName(): String = stringResource(Res.string.data_device_name_web)
