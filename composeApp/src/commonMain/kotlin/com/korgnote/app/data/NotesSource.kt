package com.korgnote.app.data

/**
 * Where this device's notes are: synced with the KorgNote server on a computer, into a copy the app
 * keeps, or the files of a folder the user chose, which another app may sync — or nothing does.
 */
sealed interface NotesSource {
    /** The replica, kept in step with the server this device is paired with. */
    data object Server : NotesSource

    /**
     * The `.org` files in a folder on this device. [location] is how the platform reaches it again
     * after a restart: the document tree's URI on Android, a bookmark of the folder on iOS.
     */
    data class Folder(val location: String) : NotesSource

    fun encode(): String = when (this) {
        Server -> SERVER
        is Folder -> FOLDER_PREFIX + location
    }

    companion object {
        private const val SERVER = "server"
        private const val FOLDER_PREFIX = "folder:"

        fun decode(stored: String?): NotesSource? = when {
            stored == SERVER -> Server
            stored != null && stored.startsWith(FOLDER_PREFIX) && stored.length > FOLDER_PREFIX.length ->
                Folder(stored.removePrefix(FOLDER_PREFIX))
            else -> null
        }
    }
}

/**
 * The source this device uses, or null when the user has yet to choose one, which is what the
 * welcome screens are for.
 *
 * Where a folder is not offered, the server is all there is. A device paired before there was a
 * choice to make has made it: it syncs with the server, and its notes are in the replica.
 */
fun SettingsRepository.chosenNotesSource(offersFolder: Boolean = offersNotesFolder): NotesSource? =
    getNotesSource() ?: NotesSource.Server.takeIf { !offersFolder || getPairedServer() != null }

/** Whether notes can be kept in a folder of the user's here, and so whether there is a choice to make. */
internal expect val offersNotesFolder: Boolean
