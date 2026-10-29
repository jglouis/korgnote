package com.korgnote.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.korgnote.app.data.PairingLinks
import com.korgnote.app.data.PersistentSettingsRepository
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.di.initKoin
import com.korgnote.app.files.IosNoteFolder
import com.korgnote.app.files.NoteFolderAccess
import com.korgnote.app.notifications.IosNotificationScheduler
import com.korgnote.app.notifications.NotificationScheduler
import com.korgnote.app.notifications.installReminderTapHandler
import org.koin.dsl.module
import platform.UIKit.UIViewController
import com.korgnote.common.replica.FileSystemReplicaStorage
import com.korgnote.common.replica.ReplicaStorage
import com.korgnote.common.replica.defaultReplicaRoot

@Suppress("unused")
fun MainViewController(): UIViewController {
    val iosModule = module {
        single<SettingsRepository> { PersistentSettingsRepository() }
        single<ReplicaStorage> { FileSystemReplicaStorage(defaultReplicaRoot()) }
        single<NotificationScheduler> { IosNotificationScheduler() }
        single<NoteFolderAccess> { NoteFolderAccess(::IosNoteFolder) }
    }
    initKoin(extraModules = listOf(iosModule))
    installReminderTapHandler()

    // The keyboard takes room from the app, as on Android: by default the whole view only slid up
    // until the focused field cleared it, which left the editing strip and its save button under it.
    return ComposeUIViewController(configure = { onFocusBehavior = OnFocusBehavior.DoNothing }) {
        Box(Modifier.imePadding()) {
            App()
        }
    }
}

/**
 * A `korgnote:` link the app was opened with — the Camera app offers to open the pairing QR code's
 * link here — handed to Settings. Whether it was a pairing link.
 */
@Suppress("unused")
fun openUrl(url: String): Boolean = PairingLinks.offer(url)
