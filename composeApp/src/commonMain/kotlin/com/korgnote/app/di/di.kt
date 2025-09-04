package com.korgnote.app.di

import com.korgnote.app.data.KtorOrgRepository
import com.korgnote.app.data.OrgRepository
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.data.buildBaseUrl
import com.korgnote.app.viewmodel.FileTreeViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module

// Simple Koin setup for Multiplatform Compose
val appModule = module {

    // Provide Ktor HttpClient with JSON plugin (base URL includes port; client itself is engine + JSON only)
    single { KtorOrgRepository.default() }

    // Provide dynamic base URL provider that reads from settings each call
    single<() -> String> {
        val repo = get<SettingsRepository>()
        val provider: () -> String = { buildBaseUrl(repo.getServerHost(), repo.getServerPort()) }
        println("[DEBUG_LOG][DI] Base URL provider installed")
        provider
    }

    // Repository implementation
    single { KtorOrgRepository(get(), get()) } bind OrgRepository::class
    // ViewModel uses repository
    viewModel { FileTreeViewModel(get<OrgRepository>()) }
}

// Expose an init function to start Koin from platform-specific entry points if needed
//fun initKoin(additionalModules: List<Module> = emptyList()): Koin =
//    startKoin {
//        modules(appModule + additionalModules)
//    }.koin
