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
import com.korgnote.app.widget.WidgetIntents

class MainActivity : ComponentActivity() {
    // The answer needs no handling: sync retries on its own once local network access is granted,
    // which can also be done later in the system settings. On the first start the welcome screens
    // ask instead.
    private val askForPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        openReminderFrom(intent)
        WidgetIntents.deliver(intent)
        // Not on a rotation, which recreates the activity with the link it was opened with.
        if (savedInstanceState == null) PairingLinks.offer(intent?.dataString)
        // The local network, for the server on it, which sync cannot do without. Notifications are
        // not asked for here: the welcome screens ask, and Settings, and "Not now" there must not be
        // followed by the same question at the next start.
        val source = KoinPlatform.getKoin().get<SettingsRepository>().chosenNotesSource()
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && source == NotesSource.Server) {
                add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty() && savedInstanceState == null) askForPermissions.launch(missing.toTypedArray())

        // Koin is started once in KorgNoteApplication; the Activity is recreated on rotation.
        setContent {
            // Edge-to-edge means the window no longer shrinks for the soft keyboard: without this
            // the keyboard is drawn over the editor and its save buttons. iOS does the same in
            // MainViewController.
            Box(Modifier.imePadding()) {
                App()
            }
        }
    }

    // A tap on a reminder or a widget, or a pairing link, while the app is already open arrives
    // here rather than in onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openReminderFrom(intent)
        WidgetIntents.deliver(intent)
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
