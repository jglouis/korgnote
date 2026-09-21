package com.korgnote.app

import androidx.compose.ui.window.ComposeUIViewController
import com.korgnote.app.data.PersistentSettingsRepository
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.di.initKoin
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
    }
    initKoin(extraModules = listOf(iosModule))

    return ComposeUIViewController { App() }
}
