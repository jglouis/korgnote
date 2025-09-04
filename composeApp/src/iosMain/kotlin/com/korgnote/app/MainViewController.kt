package com.korgnote.app

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController() = ComposeUIViewController { 
    val iosModule = org.koin.dsl.module {
        single<com.korgnote.app.data.SettingsRepository> { com.korgnote.app.data.PersistentSettingsRepository() }
    }
    App(extraModules = listOf(iosModule))
}