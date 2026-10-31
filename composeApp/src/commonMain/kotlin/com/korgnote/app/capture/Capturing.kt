package com.korgnote.app.capture

import com.korgnote.app.data.NotesSource
import com.korgnote.app.di.WEBDAV
import com.korgnote.app.di.conflictTimestamp
import com.korgnote.app.files.NoteFolderAccess
import com.korgnote.common.org.capture.CaptureLink
import com.korgnote.common.org.capture.captureLinkOf
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.FolderNoteStorage
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

/**
 * The notes [source] keeps, as a store to file captures into: the replica the sync engine shares
 * for the server and WebDAV, or the folder's files read afresh.
 */
fun Koin.notesStore(source: NotesSource): DocumentStore = when (source) {
    NotesSource.Server -> get()
    NotesSource.WebDav -> get(WEBDAV)
    is NotesSource.Folder -> DocumentStore(FolderNoteStorage(get<NoteFolderAccess>().open(source.location), ::conflictTimestamp))
}
