package com.korgnote.app

import androidx.compose.ui.window.ComposeUIViewController
import com.korgnote.app.data.PersistentSettingsRepository
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.di.initKoin
import org.koin.dsl.module

fun MainViewController(): platform.UIKit.UIViewController {
    val iosModule = module {
        single<SettingsRepository> { PersistentSettingsRepository() }
    }
    initKoin(extraModules = listOf(iosModule))

    return ComposeUIViewController {
        App()
    }
}