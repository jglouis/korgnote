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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

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

    single { SyncEngine(get(), get(), ::conflictTimestamp) }
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

/**
 * Stamp for conflict copies: `yyyyMMdd-HHmmss` in local time, matching Syncthing's naming so the
 * files look familiar next to the ones it already makes.
 */
internal fun conflictTimestamp(): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    // Month is an enum here, so its number comes from the ordinal; January is 0.
    fun pad(value: Int, width: Int = 2) = value.toString().padStart(width, '0')
    return "${pad(now.year, 4)}${pad(now.month.ordinal + 1)}${pad(now.day)}-" +
        "${pad(now.hour)}${pad(now.minute)}${pad(now.second)}"
}
