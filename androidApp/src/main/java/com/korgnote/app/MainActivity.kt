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
import com.korgnote.app.data.NotesSource
import com.korgnote.app.data.PairingLinks
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.data.chosenNotesSource
import org.koin.mp.KoinPlatform
import com.korgnote.app.notifications.ReminderAlarms
import com.korgnote.app.notifications.ReminderTaps
import com.korgnote.app.notifications.ReminderTarget

class MainActivity : ComponentActivity() {
    // The answers need no handling: reminders are scheduled either way and are shown once
    // notifications are granted, and sync retries on its own once local network access is. Both can
    // also be granted later in the system settings. On the first start the welcome screens ask
    // instead, each when it is needed.
    private val askForPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        openReminderFrom(intent)
        // Not on a rotation, which recreates the activity with the link it was opened with.
        if (savedInstanceState == null) PairingLinks.offer(intent?.dataString)
        // Not before the welcome screens; and the local network only for the server on it.
        val source = KoinPlatform.getKoin().get<SettingsRepository>().chosenNotesSource()
        val missing = buildList {
            if (source == null) return@buildList
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && source == NotesSource.Server) {
                add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty() && savedInstanceState == null) askForPermissions.launch(missing.toTypedArray())

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

    // A tap on a reminder, or a pairing link, while the app is already open arrives here rather
    // than in onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openReminderFrom(intent)
        PairingLinks.offer(intent.dataString)
    }

    /** A notification's tap carries the note and heading it is about; the app opens them. */
    private fun openReminderFrom(intent: Intent?) {
        val file = intent?.getStringExtra(ReminderAlarms.EXTRA_FILE) ?: return
        ReminderTaps.pending.value = ReminderTarget(file, intent.getIntExtra(ReminderAlarms.EXTRA_OFFSET, 0))
        // Consumed: a rotation recreates the activity with the same intent.
        intent.removeExtra(ReminderAlarms.EXTRA_FILE)
    }
}
