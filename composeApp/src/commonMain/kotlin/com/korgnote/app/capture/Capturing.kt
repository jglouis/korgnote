package com.korgnote.app.capture

import com.korgnote.app.data.NotesSource
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.data.chosenNotesSource
import com.korgnote.app.di.WEBDAV
import com.korgnote.app.di.conflictTimestamp
import com.korgnote.app.files.NoteFolderAccess
import com.korgnote.common.org.capture.CaptureLink
import com.korgnote.common.org.capture.captureLinkOf
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.FolderNoteStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.Koin

/**
 * A `korgnote://capture` link the app was opened with, waiting for the app to show it in the capture
 * sheet. A process-wide mailbox, like the pairing links', because the link can start the app.
 */
object CaptureLinks {
    val pending = MutableStateFlow<CaptureLink?>(null)

    /** Keeps [uri] for the app when it is a capture link; whether it was one. */
    fun offer(uri: String?): Boolean {
        val link = uri?.let(::captureLinkOf) ?: return false
        pending.value = link
        return true
    }
}

/** Captures added to the queue by this process: the app, if it is open, files them at once. */
object CaptureSignals {
    val queued = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

/** Where the notes of a [NotesSource] are, and what keeps them in step with where they are kept. */
sealed interface NotesBackend {
    val store: DocumentStore

    /** A replica the sync engine shares, for the server and for WebDAV. */
    class Replica(override val store: DocumentStore, val engine: () -> SyncEngine) : NotesBackend

    /** A folder's files, read afresh: there is nothing to sync with, only the folder to look at. */
    class Folder(val storage: FolderNoteStorage) : NotesBackend {
        override val store = DocumentStore(storage)
    }
}

/** The one place a [NotesSource] is turned into the notes it names. */
fun Koin.backendFor(source: NotesSource): NotesBackend = when (source) {
    NotesSource.Server -> NotesBackend.Replica(get()) { get() }
    NotesSource.WebDav -> NotesBackend.Replica(get(WEBDAV)) { get(WEBDAV) }
    is NotesSource.Folder -> NotesBackend.Folder(FolderNoteStorage(get<NoteFolderAccess>().open(source.location), ::conflictTimestamp))
}

/** The notes [source] keeps, as a store to file captures into. */
fun Koin.notesStore(source: NotesSource): DocumentStore = backendFor(source).store

/** True when the notes in use hold an edit that has not reached where they are kept. */
suspend fun Koin.hasUnsentEdits(): Boolean {
    val source = get<SettingsRepository>().chosenNotesSource() ?: return false
    return notesStore(source).dirtyPaths().isNotEmpty()
}
