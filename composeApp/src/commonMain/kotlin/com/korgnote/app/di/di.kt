package com.korgnote.app.di

import com.korgnote.app.data.KtorOrgRepository
import com.korgnote.app.data.OrgRepository
import com.korgnote.app.viewmodel.FileTreeViewModel
import io.ktor.client.*
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module

// Simple Koin setup for Multiplatform Compose
val appModule = module {
    // Provide a base URL pointing to local server (platform-aware)
    single { com.korgnote.app.data.defaultServerBaseUrl() }
    // Provide Ktor HttpClient with JSON plugin (base URL includes port; client itself is engine + JSON only)
    single { KtorOrgRepository.default() }
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
