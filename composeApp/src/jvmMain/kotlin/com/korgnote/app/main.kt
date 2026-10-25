package com.korgnote.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.korgnote.app.data.PersistentSettingsRepository
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.di.initKoin
import com.korgnote.app.files.DesktopFileOpener
import com.korgnote.app.files.FileOpener
import org.koin.dsl.module
import com.korgnote.common.replica.FileSystemReplicaStorage
import com.korgnote.common.replica.ReplicaStorage
import com.korgnote.common.replica.defaultDesktopReplicaRoot

fun main() {
    val desktopModule = module {
        single<SettingsRepository> { PersistentSettingsRepository() }
        single<ReplicaStorage> { FileSystemReplicaStorage(defaultDesktopReplicaRoot()) }
        single<FileOpener> { DesktopFileOpener }
    }
    initKoin(extraModules = listOf(desktopModule))

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "korgnote",
        ) {
            App()
        }
    }
}