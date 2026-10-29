package com.korgnote.app.files

import androidx.compose.runtime.Composable
import com.korgnote.common.replica.NoteFolder

/**
 * Reaching the folder a [com.korgnote.app.data.NotesSource.Folder] names, on this platform: the
 * location is whatever the platform needs to find it again after a restart, as the picker gave it.
 */
fun interface NoteFolderAccess {
    fun open(location: String): NoteFolder
}

/**
 * The system's folder picker, asking for the right to read and write the folder for good: a
 * function that opens it, after which [onPicked] receives the folder's location. Null where notes
 * cannot be kept in a folder of the user's.
 */
@Composable
expect fun rememberNotesFolderPicker(onPicked: (String) -> Unit): (() -> Unit)?

/** The folder at [location], as the user would recognise it: `Internal storage/Sync/org`. */
expect fun notesFolderLabel(location: String): String

/** Where a notes folder can be, said for this platform, for the welcome screen's choice. */
internal expect val notesFolderPlaces: String

/** What to pick in the folder picker, and what it will not let you pick, said for this platform. */
internal expect val notesFolderPickingHint: String
