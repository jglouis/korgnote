package com.korgnote.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.korgnote.app.data.InMemorySettingsRepository
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.di.initKoin
import kotlinx.browser.document
import org.koin.dsl.module

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val webModule = module {
        single<SettingsRepository> { InMemorySettingsRepository() }
    }
    initKoin(extraModules = listOf(webModule))

    ComposeViewport(document.body!!) {
        App()
    }
}