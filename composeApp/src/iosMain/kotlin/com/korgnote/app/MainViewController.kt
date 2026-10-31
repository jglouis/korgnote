package com.korgnote.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.korgnote.app.capture.CaptureLinks
import com.korgnote.app.capture.CaptureQueueFolder
import com.korgnote.app.capture.DeviceCalendar
import com.korgnote.app.capture.IosDeviceCalendar
import com.korgnote.app.capture.IosCaptureQueueFolder
import com.korgnote.app.capture.iosCaptureQueueDirectory
import com.korgnote.app.data.PairingLinks
import com.korgnote.app.data.PersistentSettingsRepository
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.di.WEBDAV
import com.korgnote.app.di.initKoin
import com.korgnote.app.files.IosNoteFolder
import com.korgnote.app.files.NoteFolderAccess
import com.korgnote.app.notifications.IosNotificationScheduler
import com.korgnote.app.notifications.NotificationScheduler
import com.korgnote.app.notifications.installReminderTapHandler
import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.dsl.module
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.UIKit.UIViewController
import com.korgnote.common.replica.FileSystemReplicaStorage
import com.korgnote.common.replica.ReplicaStorage
import com.korgnote.common.replica.defaultReplicaRoot

@Suppress("unused")
fun MainViewController(): UIViewController {
    val replica = defaultReplicaRoot()
    val webDavReplica = "$replica-webdav"
    // Nothing of the notes leaves the iPhone in a backup, as on Android: they are kept where the
    // user chose, and a new iPhone syncs them again.
    keepOutOfBackups(replica)
    keepOutOfBackups(webDavReplica)
    val iosModule = module {
        single<SettingsRepository> { PersistentSettingsRepository() }
        single<ReplicaStorage> { FileSystemReplicaStorage(replica) }
        // The WebDAV sync's, beside it and never mixed with it.
        single<ReplicaStorage>(WEBDAV) { FileSystemReplicaStorage(webDavReplica) }
        single<NotificationScheduler> { IosNotificationScheduler() }
        single<NoteFolderAccess> { NoteFolderAccess(::IosNoteFolder) }
        // Shared with the share extension, which queues what it captures there for the app to file.
        single<DeviceCalendar> { IosDeviceCalendar() }
        single<CaptureQueueFolder> { IosCaptureQueueFolder(iosCaptureQueueDirectory(fallback = replica.substringBeforeLast('/'))) }
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
 * A `korgnote:` link the app was opened with: the pairing QR code's, which the Camera app offers to
 * open here, handed to Settings; or a `korgnote://capture` one, from Shortcuts or a bookmarklet, shown
 * in the capture sheet. Whether it was either.
 */
@Suppress("unused")
fun openUrl(url: String): Boolean = PairingLinks.offer(url) || CaptureLinks.offer(url)

/**
 * Marks the folder at [path], made if missing, as left out of iCloud and computer backups. Logged
 * and left as it was when that fails: the notes stay readable either way.
 */
@OptIn(ExperimentalForeignApi::class)
private fun keepOutOfBackups(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    if (!NSURL.fileURLWithPath(path).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)) {
        println("[DEBUG_LOG][Backups] Could not keep $path out of backups")
    }
}
