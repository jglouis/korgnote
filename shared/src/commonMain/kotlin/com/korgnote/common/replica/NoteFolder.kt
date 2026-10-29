package com.korgnote.common.replica

/**
 * What a note file looked like when it was listed: enough to tell that it changed since, without
 * reading it. Neither a phone's shared storage nor iOS's Files says when a file changes, so this is
 * how an edit made by Syncthing, Dropbox or Emacs is noticed.
 */
data class FileStamp(val modified: Long, val size: Long)

/** A note file in a [NoteFolder], by its path relative to the folder. */
data class FolderEntry(val path: String, val stamp: FileStamp)

/**
 * A folder of `.org` files that the user chose and that other apps also write to: the notes
 * themselves, not a copy the app keeps.
 *
 * Each platform reaches it its own way — the Storage Access Framework on Android, a bookmarked URL
 * on iOS — and both are slow next to a file in the app's own storage, so [FolderNoteStorage] asks as
 * little of it as it can. Paths are relative and `/`-separated, as in the replica.
 *
 * Every call may throw when the folder can no longer be reached: the user took KorgNote's access
 * away, or the folder was deleted or is on a card that was taken out.
 */
interface NoteFolder {
    /**
     * Every `.org` file in the folder and the folders under it, sorted. Hidden files and folders are
     * left out: `.stversions`, where Syncthing keeps old versions, would otherwise show every note
     * several times over.
     */
    suspend fun list(): List<FolderEntry>

    /** [path]'s text, or null when there is no such file. */
    suspend fun read(path: String): String?

    /** Writes [content] to [path], creating it and the folders above it when missing. */
    suspend fun write(path: String, content: String): FileStamp

    suspend fun delete(path: String)

    /** How [path] looks now, or null when there is no such file. */
    suspend fun stamp(path: String): FileStamp?
}

/** Whether a listed name is one [NoteFolder.list] keeps: a visible `.org` file in visible folders. */
fun isListedNotePath(path: String): Boolean =
    path.endsWith(".org") && path.split('/').none { it.isEmpty() || it.startsWith('.') }

/** Rejects a path that is not relative, or climbs out of the folder, as [NoteFolder]s must. */
fun requireSafeNotePath(path: String) = requireSafeReplicaPath(path)
