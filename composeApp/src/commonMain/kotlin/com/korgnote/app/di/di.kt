package com.korgnote.app.di

import com.korgnote.app.data.KtorOrgRepository
import com.korgnote.app.data.OrgRepository
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.viewmodel.FileTreeViewModel
import io.ktor.client.HttpClient
import org.koin.core.module.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

// Simple Koin setup for Multiplatform Compose
val appModule = module {

    // Provide Ktor HttpClient with JSON plugin (base URL includes port; client itself is engine + JSON only)
    single { platformBuildHttpClient() }

    // Repository implementation
    single {
        val repo = get<SettingsRepository>()
        val baseUrlProvider = { buildBaseUrl(repo.getServerHost(), repo.getServerPort()) }
        KtorOrgRepository(get(), baseUrlProvider)
    } bind OrgRepository::class
    // ViewModel uses repository
    viewModel { FileTreeViewModel(get<OrgRepository>()) }
}

fun initKoin(extraModules: List<Module> = emptyList()) {
    startKoin {
        modules(appModule + extraModules)
    }
}

// Helper to build base URL from host and port
fun buildBaseUrl(host: String, port: Int): String {
    val normalized = if (host.startsWith("http://") || host.startsWith("https://")) host else "http://$host"
    return "$normalized:$port"
}

// Platform-specific client builder (Android adds Logging, others are minimal)
expect fun platformBuildHttpClient(): HttpClient
