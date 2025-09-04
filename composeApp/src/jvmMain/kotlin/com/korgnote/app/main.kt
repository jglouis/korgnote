package com.korgnote.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.korgnote.app.data.PersistentSettingsRepository
import com.korgnote.app.data.SettingsRepository

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "korgnote",
    ) {
        run {
            val desktopModule = org.koin.dsl.module {
                single<SettingsRepository> { PersistentSettingsRepository() }
            }
            App(extraModules = listOf(desktopModule))
        }
    }
}