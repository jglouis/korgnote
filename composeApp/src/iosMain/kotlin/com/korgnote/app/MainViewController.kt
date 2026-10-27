package com.korgnote.app

import androidx.compose.ui.window.ComposeUIViewController
import com.korgnote.app.data.PairingLinks
import com.korgnote.app.data.PersistentSettingsRepository
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.di.initKoin
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
    }
    initKoin(extraModules = listOf(iosModule))
    installReminderTapHandler()

    return ComposeUIViewController { App() }
}

/**
 * A `korgnote:` link the app was opened with — the Camera app offers to open the pairing QR code's
 * link here — handed to Settings. Whether it was a pairing link.
 */
@Suppress("unused")
fun openUrl(url: String): Boolean = PairingLinks.offer(url)
