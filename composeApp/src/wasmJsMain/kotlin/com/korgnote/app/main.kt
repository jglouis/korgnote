package com.korgnote.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.korgnote.app.data.PersistentSettingsRepository
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.di.initKoin
import kotlinx.browser.document
import org.koin.dsl.module
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.ReplicaStorage

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val webModule = module {
        single<SettingsRepository> { PersistentSettingsRepository() }
        single<ReplicaStorage> { InMemoryReplicaStorage() }
    }
    initKoin(extraModules = listOf(webModule))

    ComposeViewport(document.body!!) {
        App()
    }
}