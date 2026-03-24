package com.korgnote.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.korgnote.app.data.PersistentSettingsRepository
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.di.initKoin
import org.koin.dsl.module

fun main() {
    val desktopModule = module {
        single<SettingsRepository> { PersistentSettingsRepository() }
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