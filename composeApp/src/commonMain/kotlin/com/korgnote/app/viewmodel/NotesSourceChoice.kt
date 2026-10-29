package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.korgnote.app.data.NotesSource
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.data.chosenNotesSource
import com.korgnote.app.data.offersNotesFolder
import com.korgnote.common.replica.DocumentStore

/**
 * Where this device's notes are, as chosen on the welcome screens or later in Settings; null until
 * then, which is what shows the welcome screens.
 *
 * Each source has its own notes and its own [NotesViewModel]: the server's replica is never the
 * folder's, so nothing synced with the server can land in the user's folder, nor the other way
 * round. Choosing another source stops the one that was in use from following its changes.
 */
class NotesSourceChoice(
    private val settings: SettingsRepository,
    /** The server's replica, which holds notes on a device synced before there was a choice. */
    private val replica: DocumentStore,
    private val offersFolder: Boolean = offersNotesFolder,
) {
    var current by mutableStateOf(settings.chosenNotesSource(offersFolder))
        private set

    /** False until it is known whether there is a choice to make, so the welcome is not flashed. */
    var settled by mutableStateOf(current != null)
        private set

    /** The sync of the notes on screen, for [choose] to stop when they are no longer the ones used. */
    var active: NotesSync? = null

    /**
     * Works out whether there is a choice to make. A device whose replica holds notes, though it is
     * not paired any more, has been syncing with a server: it goes on with the server.
     */
    suspend fun settle() {
        if (current == null && replica.paths().isNotEmpty()) choose(NotesSource.Server)
        settled = true
    }

    fun choose(source: NotesSource) {
        if (source != current) {
            active?.stop()
            active = null
        }
        settings.setNotesSource(source)
        current = source
        settled = true
    }
}
