package com.korgnote.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "korgnote",
    ) {
        run {
                val desktopModule = org.koin.dsl.module {
                    single<com.korgnote.app.data.SettingsRepository> { com.korgnote.app.data.PersistentSettingsRepository() }
                }
                App(extraModules = listOf(desktopModule))
                }
    }
}