package com.korgnote.common.replica

import kotlinx.coroutines.sync.Mutex
import com.korgnote.common.sync.conflictCopyName
import kotlinx.coroutines.sync.withLock

/**
 * The app's view of its local notes: the only thing that writes to the replica.
 *
 * Reads and writes are local and always succeed, which is the point — the app works with the server
 * unreachable, and a save is never lost to a network error. Saving marks the file dirty; a dirty
 * file is what the sync engine pushes. Nothing here talks to the network.
 *
 * Every mutation is serialised through a mutex: saves arrive from the UI while pulls arrive from
 * sync, and the metadata must not be read-modify-written by two of them at once.
 */
class DocumentStore(private val storage: ReplicaStorage) {

    private val mutex = Mutex()

    /**
     * Retrieves the available storage paths.
     *
     * @return a list of path strings from storage.
     */
    suspend fun paths(): List<String> = storage.list()

    /**
     * Reads the content stored at the specified path.
     *
     * @param path the path of the content to read.
     * @return the content at the specified path, or null if no content is available.
     */
    suspend fun read(path: String): String? = storage.read(path)

    /**
     * Retrieves the stored file stamps keyed by their associated file identifier or path.
     *
     * @return a map of stored file stamps, or null if no stamp information is available
     */
    suspend fun stamps(): Map<String, FileStamp>? = storage.stamps()

    /**
     * Reads the contents associated with multiple storage paths.
     *
     * @param paths the storage paths to read
     * @return a map where each key is a requested path and each value is the content read from that path
     */
    suspend fun readSeveral(paths: List<String>): Map<String, String> = storage.readSeveral(paths)

    /**
     * Checks whether the file metadata for the specified path is marked as dirty.
     *
     * @param path The path of the file to check.
     * @return `true` if the file exists in metadata and is marked as dirty; otherwise `false`.
     */
    suspend fun isDirty(path: String): Boolean = storage.readMeta().files[path]?.dirty == true

    /**
     * Retrieves the paths of all files currently marked as dirty.
     *
     * @return a sorted list of dirty file paths.
     */
    suspend fun dirtyPaths(): List<String> =
        storage.readMeta().files.values.filter { it.dirty }.map { it.path }.sorted()

    /**
     * Retrieves metadata for the file identified by the given path.
     *
     * @param path the path of the file whose metadata should be retrieved
     * @return the file metadata for the given path, or null if no metadata exists
     */
    suspend fun meta(path: String): FileMeta? = storage.readMeta().files[path]

    /**
     * Reads the stored file metadata records.
     *
     * @return a map of file metadata records keyed by their associated file identifier
     */
    suspend fun records(): Map<String, FileMeta> = storage.readMeta().files

    /**
     * Saves the specified content at the given replica path.
     *
     * The path is validated before writing, and the write operation is performed
     * under synchronization to prevent concurrent modifications.
     *
     * @param path the replica path where the content should be saved
     * @param content the content to save
     */
    suspend fun save(path: String, content: String) {
        requireSafeReplicaPath(path)
        mutex.withLock { saveLocked(path, content) }
    }

    /**
     * Saves new content to the replica at the specified path only if its current content matches the expected value.
     *
     * @param path the replica path to save to
     * @param expected the content that must currently be stored at the path for the save to proceed
     * @param content the new content to store when the current content matches the expected value
     * @return true if the content was saved, or false if the current content did not match the expected value
     */
    suspend fun saveIfUnchanged(path: String, expected: String, content: String): Boolean {
        requireSafeReplicaPath(path)
        return mutex.withLock {
            if (storage.read(path) != expected) return@withLock false
            saveLocked(path, content)
            true
        }
    }

    private suspend fun saveLocked(path: String, content: String) {
        val meta = storage.readMeta()
        val existing = meta.files[path]
        val saved = meta.copy(
            files = meta.files + (path to (existing ?: FileMeta(path)).copy(
                path = path,
                dirty = true,
                // Saved over its own tombstone, which it replaces, keeping the etag: as in
                // [create], the text is pushed as an edit of the version that was deleted.
                deleted = false,
            )),
        )
        // What a crash between the two writes leaves has to be harmless. Over a note on record, the
        // record goes first: the old text marked dirty is pushed as an identical write, where the new
        // text marked clean would never be pushed, and the next pull would overwrite it. With no text
        // yet, the file goes first: a file with no record is pushed as a new note, or a tombstone's
        // deletion is sent first, where a dirty record of no text would refuse the server's note of
        // that name for ever.
        val onRecord = existing != null && !existing.deleted
        if (onRecord) storage.writeMeta(saved)
        storage.write(path, content)
        if (!onRecord) storage.writeMeta(saved)
    }

    /**
     * Deletes the entry identified by the specified path.
     *
     * The operation is performed while holding the internal mutex to ensure the
     * deletion state is updated atomically.
     *
     * @param path the path of the entry to delete
     */
    suspend fun delete(path: String) {
        mutex.withLock { tombstoneLocked(path) }
    }

    private suspend fun tombstoneLocked(path: String) {
        storage.delete(path)
        storage.deleteBase(path)
        val meta = storage.readMeta()
        val existing = meta.files[path]
        val files = if (existing?.etag == null) {
            meta.files - path
        } else {
            meta.files + (path to existing.copy(deleted = true, dirty = false))
        }
        storage.writeMeta(meta.copy(files = files))
    }

    /**
     * Checks whether a value exists in storage at the specified path.
     *
     * @param path the storage path to check.
     * @return true if data exists at the specified path, false otherwise.
     */
    private suspend fun isTaken(path: String): Boolean =
        storage.read(path) != null

    /**
     * Creates content at the specified replica path if the path is not already taken.
     *
     * The path is validated before creation. If the path is already in use, no content is created.
     *
     * @param path The replica path where the content should be created.
     * @param content The content to store at the specified path.
     * @return `true` if the content was created, or `false` if the path was already taken.
     */
    suspend fun create(path: String, content: String): Boolean {
        requireSafeReplicaPath(path)
        return mutex.withLock {
            if (isTaken(path)) return@withLock false
            createLocked(path, content)
            true
        }
    }

    private suspend fun createLocked(path: String, content: String) {
        storage.write(path, content)
        val meta = storage.readMeta()
        val tombstone = meta.files[path]?.takeIf { it.deleted }
        storage.writeMeta(
            meta.copy(
                files = meta.files + (path to FileMeta(
                    path = path,
                    etag = tombstone?.etag,
                    dirty = true,
                )),
            ),
        )
    }

    /**
     * Renames the stored content from one replica path to another.
     *
     * The target path is validated before the rename is attempted. If the source and target paths
     * are the same, the operation is not performed. The rename succeeds only when the source exists
     * and the target path is not already taken.
     *
     * @param from the existing replica path to rename from
     * @param to the target replica path to rename to
     * @return true if the content was renamed successfully, or false if the source does not exist,
     * the target is already taken, or both paths are the same
     */
    suspend fun rename(from: String, to: String): Boolean {
        requireSafeReplicaPath(to)
        if (from == to) return false
        return mutex.withLock {
            val content = storage.read(from) ?: return@withLock false
            if (isTaken(to)) return@withLock false
            createLocked(to, content)
            tombstoneLocked(from)
            true
        }
    }

    /**
     * Retrieves metadata for files marked as deleted, sorted by file path.
     *
     * @return a list of deleted file metadata sorted by path
     */
    suspend fun tombstones(): List<FileMeta> =
        storage.readMeta().files.values.filter { it.deleted }.sortedBy { it.path }

    /**
     * Removes the stored deletion marker for the file at the specified path.
     *
     * If the metadata entry exists and is marked as deleted, the entry is removed
     * from storage. If the path is not present or is not marked as deleted, this
     * function leaves the metadata unchanged.
     *
     * @param path the file path whose tombstone metadata should be removed
     */
    suspend fun forgetTombstone(path: String) {
        mutex.withLock {
            val meta = storage.readMeta()
            if (meta.files[path]?.deleted == true) storage.writeMeta(meta.copy(files = meta.files - path))
        }
    }

    /**
     * Accepts content received from the server for the replica at the specified path.
     *
     * The path is validated before the update is applied. The operation is performed
     * while holding the replica mutex to ensure serialized access to the underlying
     * replica state.
     *
     * @param path safe replica path identifying the content to update.
     * @param content server-provided content to store for the specified path.
     * @param etag optional server entity tag associated with the accepted content.
     * @throws IllegalArgumentException if the provided path is not a safe replica path.
     */
    internal suspend fun acceptFromServer(path: String, content: String, etag: String? = null) {
        requireSafeReplicaPath(path)
        mutex.withLock { acceptLocked(path, content, etag) }
    }

    /**
     * Accepts and stores replica content for the specified path only if the local metadata has not changed
     * from the expected known ETag and the file is neither dirty nor deleted.
     *
     * @param path the safe replica path of the file to accept
     * @param content the content to store for the file
     * @param etag the new ETag associated with the accepted content
     * @param knownEtag the expected current ETag from local metadata, or null if no ETag is expected
     * @return true if the content was accepted and stored; false if the file was dirty, deleted, or its
     * ETag did not match knownEtag
     */
    suspend fun acceptIfUntouched(path: String, content: String, etag: String, knownEtag: String?): Boolean {
        requireSafeReplicaPath(path)
        return mutex.withLock {
            val now = storage.readMeta().files[path]
            if (now?.dirty == true || now?.deleted == true || now?.etag != knownEtag) return@withLock false
            acceptLocked(path, content, etag)
            true
        }
    }

    /**
     * Accepts the provided remote files only when their local metadata is not marked as dirty or deleted.
     *
     * Each file path is validated before processing. Files that are currently marked dirty or deleted in
     * metadata are skipped. For accepted files, both the working content and base content are written, and
     * metadata is updated with the file path and ETag. If no files are accepted, an empty list is returned.
     *
     * @param files remote files to accept when their local metadata is untouched
     * @return the paths of files that were accepted and written
     */
    suspend fun acceptAllUntouched(files: List<RemoteFile>): List<String> {
        files.forEach { requireSafeReplicaPath(it.path) }
        if (files.isEmpty()) return emptyList()
        return mutex.withLock {
            val meta = storage.readMeta()
            val taken = files.filterNot { file -> meta.files[file.path]?.let { it.dirty || it.deleted } == true }
            if (taken.isEmpty()) return@withLock emptyList()
            val written = mutableListOf<RemoteFile>()
            try {
                for (file in taken) {
                    storage.write(file.path, file.text)
                    storage.writeBase(file.path, file.text)
                    written += file
                }
            } finally {
                storage.writeMeta(meta.copy(files = meta.files + written.map { it.path to FileMeta(it.path, etag = it.etag) }))
            }
            written.map { it.path }
        }
    }

    private suspend fun acceptLocked(path: String, content: String, etag: String?) {
        storage.write(path, content)
        storage.writeBase(path, content)
        val meta = storage.readMeta()
        storage.writeMeta(meta.copy(files = meta.files + (path to FileMeta(path = path, etag = etag))))
    }

    /**
     * Marks the content at the specified path as successfully pushed to remote storage.
     *
     * Updates the stored base content and metadata with the supplied ETag when the local content still
     * matches the pushed content. If the file was deleted while the push was in progress, records a
     * clean tombstone with the new ETag. If the file was edited again while the push was in progress,
     * keeps it dirty while advancing the base content and ETag to the pushed version.
     *
     * @param path path of the file whose push state should be recorded
     * @param etag remote ETag returned for the pushed content
     * @param pushedContent content that was sent during the completed push
     */
    suspend fun markPushed(path: String, etag: String, pushedContent: String) {
        mutex.withLock {
            val current = storage.read(path)
            val meta = storage.readMeta()
            val existing = meta.files[path] ?: FileMeta(path)
            if (current == null) {
                // Deleted mid-flight, and the push still reached the server: a tombstone at the
                // version it now holds. A note that had none left no record to find, and with clean
                // meta and an etag the next cycle would pull it straight back.
                val tombstone = existing.copy(etag = etag, dirty = false, deleted = true)
                storage.writeMeta(meta.copy(files = meta.files + (path to tombstone)))
                return@withLock
            }
            if (current != pushedContent) {
                // Edited again mid-flight: keep it dirty, but the base did move.
                storage.writeBase(path, pushedContent)
                storage.writeMeta(meta.copy(files = meta.files + (path to existing.copy(etag = etag))))
                return@withLock
            }
            storage.writeBase(path, pushedContent)
            storage.writeMeta(
                meta.copy(
                    files = meta.files + (path to existing.copy(etag = etag, dirty = false)),
                ),
            )
        }
    }

    /**
     * Keeps the server version of the content at the specified replica path.
     *
     * If a different local version already exists, it is saved as a timestamped copy before
     * the server content and ETag are accepted as the current stored version.
     *
     * @param path the replica path whose content should be replaced with the server version
     * @param serverText the text content received from the server
     * @param serverEtag the ETag associated with the server content
     * @param timestamp the timestamp used when creating a copy of the previous local content
     * @return the path of the saved local copy if a differing local version existed, or null otherwise
     */
    suspend fun keepServerVersion(path: String, serverText: String, serverEtag: String, timestamp: String): String? {
        requireSafeReplicaPath(path)
        return mutex.withLock {
            val local = storage.read(path)
            val copy = if (local != null && local != serverText) {
                freeCopyName(path, timestamp).also { createLocked(it, local) }
            } else {
                null
            }
            acceptLocked(path, serverText, serverEtag)
            copy
        }
    }

    /**
     * Generates an available copy name for the specified path using the given timestamp.
     *
     * If the initial conflict copy name is already taken, incrementing numeric suffixes are
     * appended to the timestamp until a free name is found.
     *
     * @param path The original path used to derive the copy name.
     * @param timestamp The timestamp used as part of the generated copy name.
     * @return A copy name that is not currently taken.
     */
    private suspend fun freeCopyName(path: String, timestamp: String): String {
        var name = conflictCopyName(path, timestamp)
        var counter = 2
        while (isTaken(name)) name = conflictCopyName(path, "$timestamp-${counter++}")
        return name
    }

    /**
     * Replaces the content at the specified replica path with the content stored at the copy path,
     * then marks the copy as tombstoned.
     *
     * @param path the replica path whose content should be replaced.
     * @param copy the path of the stored copy whose content should be used as the replacement.
     * @return true if the replacement was completed and the copy was tombstoned, or false if either
     * the copy or the target path does not exist.
     */
    suspend fun replaceWithCopy(path: String, copy: String): Boolean {
        requireSafeReplicaPath(path)
        return mutex.withLock {
            val content = storage.read(copy) ?: return@withLock false
            if (storage.read(path) == null) return@withLock false
            saveLocked(path, content)
            tombstoneLocked(copy)
            true
        }
    }

    /**
     * Resolves a conflict between a note and its replica copy when both still match
     * the expected contents.
     *
     * The operation succeeds only if the current content at [path] equals
     * [expectedNote] and the current content at [copy] equals [expectedCopy]. When
     * successful, [path] is updated to [content] if it differs from [expectedNote],
     * and [copy] is tombstoned.
     *
     * @param path safe replica path of the primary note to keep.
     * @param copy safe replica path of the conflicting copy to remove.
     * @param expectedNote expected current content of the primary note.
     * @param expectedCopy expected current content of the conflicting copy.
     * @param content resolved content to store in the primary note.
     * @return `true` if the conflict was resolved, or `false` if either file no
     * longer matches its expected content.
     */
    suspend fun resolveConflict(path: String, copy: String, expectedNote: String, expectedCopy: String, content: String): Boolean {
        requireSafeReplicaPath(path)
        return mutex.withLock {
            if (storage.read(path) != expectedNote || storage.read(copy) != expectedCopy) return@withLock false
            if (content != expectedNote) saveLocked(path, content)
            tombstoneLocked(copy)
            true
        }
    }

    /**
     * Sets the persisted generation value.
     *
     * The metadata is updated only when the current generation differs from the
     * supplied value.
     *
     * @param generation the generation value to store
     */
    suspend fun setGeneration(generation: Long) {
        mutex.withLock {
            val meta = storage.readMeta()
            if (meta.generation != generation) storage.writeMeta(meta.copy(generation = generation))
        }
    }

    /**
     * Retrieves the current generation value from the stored metadata.
     *
     * @return the current generation value.
     */
    suspend fun generation(): Long = storage.readMeta().generation

    /**
     * Removes the file identified by the given path from local storage and updates
     * local metadata so the path is no longer tracked.
     *
     * The removal is performed while holding the internal mutex to ensure local
     * storage and metadata remain consistent.
     *
     * @param path the path of the file to remove from local storage
     */
    suspend fun removeLocally(path: String) {
        mutex.withLock {
            storage.delete(path)
            storage.deleteBase(path)
            val meta = storage.readMeta()
            storage.writeMeta(meta.copy(files = meta.files - path))
        }
    }

    /**
     * Reads the base value associated with the specified path.
     *
     * @param path the path whose base value should be read
     * @return the base value for the path, or null if no value is available
     */
    suspend fun base(path: String): String? = storage.readBase(path)
}
