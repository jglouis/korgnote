package com.korgnote.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Modifier
import com.korgnote.app.notifications.ReminderAlarms
import com.korgnote.app.notifications.ReminderTaps
import com.korgnote.app.notifications.ReminderTarget

class MainActivity : ComponentActivity() {
    // The answer needs no handling: reminders are scheduled either way, and are shown once the
    // permission is granted, here or later in the system settings.
    private val askForNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        openReminderFrom(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Koin is started once in KorgNoteApplication; the Activity is recreated on rotation.
        setContent {
            // Edge-to-edge means the window no longer shrinks for the soft keyboard: without this
            // the keyboard is drawn over the editor and its save buttons. Android only — on iOS,
            // ComposeUIViewController already lifts the focused field above the keyboard.
            Box(Modifier.imePadding()) {
                App()
            }
        }
    }

    // A tap on a reminder while the app is already open arrives here rather than in onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openReminderFrom(intent)
    }

    /** A notification's tap carries the note and heading it is about; the app opens them. */
    private fun openReminderFrom(intent: Intent?) {
        val file = intent?.getStringExtra(ReminderAlarms.EXTRA_FILE) ?: return
        ReminderTaps.pending.value = ReminderTarget(file, intent.getIntExtra(ReminderAlarms.EXTRA_OFFSET, 0))
        // Consumed: a rotation recreates the activity with the same intent.
        intent.removeExtra(ReminderAlarms.EXTRA_FILE)
    }
}
