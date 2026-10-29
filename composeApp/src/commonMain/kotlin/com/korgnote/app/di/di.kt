package com.korgnote.app.di

import com.korgnote.app.data.CertificatePins
import com.korgnote.app.data.DevicePairing
import com.korgnote.app.data.KtorSyncApi
import com.korgnote.app.data.endpointsFor
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.data.NotesSource
import com.korgnote.app.files.FileOpener
import com.korgnote.app.files.NoteFolderAccess
import com.korgnote.app.viewmodel.NotesSourceChoice
import com.korgnote.common.replica.FolderNoteStorage
import com.korgnote.app.files.NoFileOpener
import com.korgnote.app.notifications.NoopNotificationScheduler
import com.korgnote.app.notifications.NotificationScheduler
import com.korgnote.app.viewmodel.NotesViewModel
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

    // The certificate TLS may present: the paired computer's. The HTTP client trusts nothing else.
    single { CertificatePins { get<SettingsRepository>().getPairedServer()?.fingerprint } }

    // Provide Ktor HttpClient with JSON plugin (base URL includes port; client itself is engine + JSON only)
    single { platformBuildHttpClient(get()) }

    // The sync API v1 client. Nothing here parses Org: it moves text and etags.
    single<SyncApi> {
        val settings = get<SettingsRepository>()
        KtorSyncApi(get()) { endpointsFor(settings) }
    }

    // A fresh client for each pairing, trusting only the certificate its QR code names.
    single { DevicePairing({ pins -> platformBuildHttpClient(pins) }, get()) }

    single { SyncEngine(get(), get(), ::conflictTimestamp) }
    // The local replica. Each platform contributes the ReplicaStorage it can actually use:
    // a real directory on desktop, Android and iOS; memory on the web, which is online by
    // definition and reloads from the server anyway.
    single { DocumentStore(get()) }

    // Which of the two the notes are in: the replica above, or a folder of the user's.
    single { NotesSourceChoice(get(), get()) }

    // ViewModel reads and writes the replica; the repository only refreshes it. Reminders go to
    // the platform's scheduler where there is one (Android, iOS) and nowhere otherwise; linked
    // files, likewise, open where the platform can open them (Android, desktop). There is one for
    // each source, given as a parameter: a folder's notes get a store of their own, which the sync
    // engine never sees, so nothing it pulls can land in the user's folder.
    viewModel { params ->
        val settings = get<SettingsRepository>()
        val source = params.getOrNull<NotesSource>() ?: NotesSource.Server
        val folder = (source as? NotesSource.Folder)?.let {
            FolderNoteStorage(get<NoteFolderAccess>().open(it.location), ::conflictTimestamp)
        }
        NotesViewModel(
            get<SyncEngine>(),
            folder?.let(::DocumentStore) ?: get<DocumentStore>(),
            notifications = getOrNull<NotificationScheduler>() ?: NoopNotificationScheduler,
            reminderSettings = settings::getReminderSettings,
            newNotesFolder = settings::getNewNotesFolder,
            noteTemplate = settings::getNoteTemplate,
            pinnedNotes = settings::getPinnedNotes,
            savePinnedNotes = settings::setPinnedNotes,
            fileLinkMappings = settings::getFileLinkMappings,
            fileOpener = getOrNull<FileOpener>() ?: NoFileOpener,
            folder = folder,
        )
    }
}

fun initKoin(extraModules: List<Module> = emptyList()) {
    startKoin {
        modules(appModule + extraModules)
    }
}

// Platform-specific client builder (Android adds Logging, others are minimal). Over TLS it trusts
// what [pins] accepts and nothing else.
expect fun platformBuildHttpClient(pins: CertificatePins): HttpClient

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
