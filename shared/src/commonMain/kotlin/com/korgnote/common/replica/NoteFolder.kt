package com.korgnote.common.replica

import kotlinx.serialization.Serializable

/**
 * Snapshot of a local file's observable state used to detect changes.
 *
 * Combines the file's last-modified timestamp and byte size into a lightweight stamp suitable for
 * comparison, persistence, and serialization during synchronization.
 */
@Serializable
data class FileStamp(val modified: Long, val size: Long)

/**
 * Local manifest entry for a folder item tracked by synchronization.
 *
 * Records the entry's relative path together with a snapshot of its observable file state, allowing
 * the sync engine to compare local state against stored or remote manifests and detect changes.
 */
data class FolderEntry(val path: String, val stamp: FileStamp)

/**
 * Abstraction over a local folder that stores note files for synchronization.
 *
 * Exposes note content through paths relative to the folder root and provides the file state needed
 * to detect local changes. Implementations handle the backing storage details, including recursive
 * listing, reading, writing, deletion, and creation of missing parent folders when writing files.
 *
 * Hidden files and directories are excluded from the synchronized view so that application metadata,
 * versioning folders, and other non-note files do not appear as note entries.
 *
 * Operations are suspendable because they may perform file system or platform storage I/O.
 */
interface NoteFolder {
    /**
     * Lists the visible note files currently present in this folder.
     *
     * Hidden files and directories are excluded from the synchronized view. Entry paths are relative to
     * the folder root and each entry includes the file state snapshot used for synchronization.
     *
     * @return the current local manifest entries for files tracked by synchronization.
     */
    suspend fun list(): List<FolderEntry>

    /**
     * Reads the text content of the file at [path].
     *
     * @param path the file path relative to the folder root.
     * @return the file content, or null if no such file is available.
     */
    suspend fun read(path: String): String?

    /**
     * Writes [content] to the file at [path].
     *
     * Creates missing parent folders as needed and replaces any existing content at the same path.
     * The path is relative to the folder root.
     *
     * @param path the file path relative to the folder root.
     * @param content the text content to write.
     * @return the file state snapshot after the write completes.
     */
    suspend fun write(path: String, content: String): FileStamp

    /**
     * Deletes the file at [path] from this folder.
     *
     * The path is relative to the folder root.
     *
     * @param path the file path relative to the folder root.
     */
    suspend fun delete(path: String)

    /**
     * Returns the current file state snapshot for the file at [path].
     *
     * The path is relative to the folder root.
     *
     * @param path the file path relative to the folder root.
     * @return the file state snapshot, or null if no such file is available.
     */
    suspend fun stamp(path: String): FileStamp?
}

/**
 * Determines whether the supplied path represents a listed note path.
 *
 * A listed note path must end with the case-sensitive ".org" suffix and every slash-separated path
 * segment must be non-empty and must not start with ".".
 *
 * @param path the path to test.
 * @return true if the path is a listed note path; false otherwise.
 */
fun isListedNotePath(path: String): Boolean =
    path.endsWith(".org") && path.split('/').none { it.isEmpty() || it.startsWith('.') }

/** The longest name, in UTF-8 bytes, of a folder or note that is synced: 255 is what most file systems take, less room for `.tmp` and a conflict copy's suffix. */
const val LONGEST_SYNCED_NAME = 200

/**
 * Determines whether the supplied path is eligible for synchronization as a note path.
 *
 * A synced note path must be a listed note path, contain no ASCII control characters or delete
 * character, and have every slash-separated path segment fit within the synced name byte-length
 * limit when encoded as UTF-8.
 *
 * @param path the path to test.
 * @return true if the path is a synced note path; false otherwise.
 */
fun isSyncedNotePath(path: String): Boolean =
    isListedNotePath(path) &&
        path.none { it.code < 0x20 || it.code == 0x7F } &&
        path.split('/').all { it.encodeToByteArray().size <= LONGEST_SYNCED_NAME }

/**
 * Requires [path] to be a safe note path relative to the replica directory.
 *
 * Rejects blank paths, absolute paths, paths containing backslashes, and paths with "." or ".." segments.
 *
 * @param path note path to validate.
 * @throws IllegalArgumentException if [path] is blank, absolute, uses backslashes, or contains "." or ".." segments.
 */
fun requireSafeNotePath(path: String) = requireSafeReplicaPath(path)
