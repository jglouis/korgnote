package com.korgnote.app.di

import com.korgnote.app.capture.CaptureFiler
import com.korgnote.app.capture.CaptureQueue
import com.korgnote.app.capture.CaptureQueueFolder
import com.korgnote.app.capture.InMemoryCaptureQueueFolder
import com.korgnote.app.data.CertificatePins
import com.korgnote.app.data.DevicePairing
import com.korgnote.app.data.KtorSyncApi
import com.korgnote.app.data.endpointsFor
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.data.NotesSource
import com.korgnote.app.data.WebDavSyncApi
import com.korgnote.app.files.FileOpener
import com.korgnote.app.files.NoteFolderAccess
import com.korgnote.app.viewmodel.NotesSourceChoice
import com.korgnote.common.replica.FolderNoteStorage
import com.korgnote.app.files.NoFileOpener
import com.korgnote.app.notifications.NoopNotificationScheduler
import com.korgnote.app.notifications.NotificationScheduler
import com.korgnote.app.viewmodel.BackgroundSync
import com.korgnote.app.viewmodel.NoBackgroundSync
import com.korgnote.app.viewmodel.NotesViewModel
import com.korgnote.app.widgets.AgendaWidgets
import com.korgnote.app.widgets.NoAgendaWidgets
import io.ktor.client.HttpClient
import org.koin.core.module.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.dsl.module
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.ReplicaStorage
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

    // The experimental WebDAV sync, apart from all of the above: a client of its own, trusting the
    // certificates the platform trusts rather than the paired computer's, a replica of its own and
    // an engine of its own, so nothing synced with a WebDAV folder lands in the server's replica,
    // nor the other way round. Its replica comes from the platforms that offer WebDAV, Android and
    // iOS: the welcome screens offer it wherever there is one.
    single<WebDavSyncApi>(WEBDAV) { WebDavSyncApi(platformBuildWebDavClient(), account = { get<SettingsRepository>().getWebDavAccount() }) }
    single<DocumentStore>(WEBDAV) { DocumentStore(get<ReplicaStorage>(WEBDAV)) }
    single<SyncEngine>(WEBDAV) { SyncEngine(get(WEBDAV), get<WebDavSyncApi>(WEBDAV), ::conflictTimestamp) }

    // Captures wait in a queue each platform keeps — the App Group's on iOS, which the share
    // extension adds to — and only the app files them, into whichever notes are in use.
    single { CaptureQueue(getOrNull<CaptureQueueFolder>() ?: InMemoryCaptureQueueFolder()) }
    single {
        val settings = get<SettingsRepository>()
        CaptureFiler(get(), settings::getNewNotesFolder, settings::getNoteTemplate)
    }

    // Which of the sources the notes are in: the server's replica, the WebDAV one, or a folder of
    // the user's.
    single { NotesSourceChoice(get(), get()) }

    // ViewModel reads and writes the replica; the repository only refreshes it. Reminders go to
    // the platform's scheduler where there is one (Android, iOS) and nowhere otherwise, and the
    // agenda to the home-screen widgets where there are some (Android); linked
    // files, likewise, open where the platform can open them (Android, desktop). There is one for
    // each source, given as a parameter: a folder's notes get a store of their own, which the sync
    // engine never sees, so nothing it pulls can land in the user's folder.
    viewModel { params ->
        val settings = get<SettingsRepository>()
        val source = params.getOrNull<NotesSource>() ?: NotesSource.Server
        val folder = (source as? NotesSource.Folder)?.let {
            FolderNoteStorage(get<NoteFolderAccess>().open(it.location), ::conflictTimestamp)
        }
        val webDav = source == NotesSource.WebDav
        NotesViewModel(
            if (webDav) get<SyncEngine>(WEBDAV) else get<SyncEngine>(),
            folder?.let(::DocumentStore) ?: if (webDav) get<DocumentStore>(WEBDAV) else get<DocumentStore>(),
            notifications = getOrNull<NotificationScheduler>() ?: NoopNotificationScheduler,
            backgroundSync = getOrNull<BackgroundSync>() ?: NoBackgroundSync,
            widgets = getOrNull<AgendaWidgets>() ?: NoAgendaWidgets,
            reminderSettings = settings::getReminderSettings,
            newNotesFolder = settings::getNewNotesFolder,
            noteTemplate = settings::getNoteTemplate,
            pinnedNotes = settings::getPinnedNotes,
            savePinnedNotes = settings::setPinnedNotes,
            savedSearches = settings::getSavedSearches,
            saveSavedSearches = settings::setSavedSearches,
            fileLinkMappings = settings::getFileLinkMappings,
            fileOpener = getOrNull<FileOpener>() ?: NoFileOpener,
            folder = folder,
            captures = get(),
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
 * The client for a WebDAV server: one that can send `PROPFIND` and `MKCOL`, and trusts what the
 * platform trusts, as a browser would. Never the paired computer's client, whose trust is that one
 * certificate and nothing else.
 */
expect fun platformBuildWebDavClient(): HttpClient

/**
 * Names the WebDAV sync's own client, replica and engine; see [appModule].
 *
 * A getter, not a stored value: [appModule] is built while this file's properties are still being
 * set, in order, and a stored one declared below it was still null then — which bound the WebDAV
 * replica and engine as the server's own.
 */
val WEBDAV: Qualifier get() = named("webdav")

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
