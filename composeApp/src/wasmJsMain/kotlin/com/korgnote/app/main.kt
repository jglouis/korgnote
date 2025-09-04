package com.korgnote.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        run {
                val webModule = org.koin.dsl.module {
                    single<com.korgnote.app.data.SettingsRepository> { com.korgnote.app.data.InMemorySettingsRepository() }
                }
                App(extraModules = listOf(webModule))
            }
    }
}