package com.korgnote.app.di

import com.korgnote.app.data.KtorSyncApi
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.viewmodel.FileTreeViewModel
import io.ktor.client.HttpClient
import org.koin.core.module.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.SyncApi
import com.korgnote.common.replica.SyncEngine

// Simple Koin setup for Multiplatform Compose
val appModule = module {

    // Provide Ktor HttpClient with JSON plugin (base URL includes port; client itself is engine + JSON only)
    single { platformBuildHttpClient() }

    // The sync API v1 client. Nothing here parses Org: it moves text and etags.
    single<SyncApi> {
        val settings = get<SettingsRepository>()
        val baseUrlProvider = { buildBaseUrl(settings.getServerHost(), settings.getServerPort()) }
        KtorSyncApi(get(), baseUrlProvider, settings::getServerToken)
    }

    single { SyncEngine(get(), get()) }
    // The local replica. Each platform contributes the ReplicaStorage it can actually use:
    // a real directory on desktop, Android and iOS; memory on the web, which is online by
    // definition and reloads from the server anyway.
    single { DocumentStore(get()) }

    // ViewModel reads and writes the replica; the repository only refreshes it.
    viewModel { FileTreeViewModel(get<SyncEngine>(), get<DocumentStore>()) }
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
