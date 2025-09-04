package com.korgnote.app

import androidx.compose.ui.window.ComposeUIViewController
import com.korgnote.app.data.PersistentSettingsRepository
import com.korgnote.app.data.SettingsRepository

fun MainViewController() = ComposeUIViewController { 
    val iosModule = org.koin.dsl.module {
        single<SettingsRepository> { PersistentSettingsRepository() }
    }
    App(extraModules = listOf(iosModule))
}