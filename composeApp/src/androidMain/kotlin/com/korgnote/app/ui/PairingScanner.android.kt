package com.korgnote.app.ui

import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * ZXing's scanner, which reads the code on the device itself: no Play services, nothing sent
 * anywhere. It asks for the camera the first time.
 */
@Composable
actual fun rememberPairingScanner(onScanned: (String) -> Unit): (() -> Unit)? {
    val current by rememberUpdatedState(onScanned)
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { current(it) }
    }
    return {
        launcher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("The pairing code on the computer's Devices page")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }
}

/** The name set in the phone's settings, such as "Jean's S25+"; its model when there is none. */
@Composable
actual fun rememberDeviceName(): String {
    val context = LocalContext.current
    return remember {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL
    }
}
