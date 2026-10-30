package com.korgnote.common.replica

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import com.korgnote.common.sync.conflictCopyName
import kotlinx.coroutines.sync.withLock

/** Something that happened to the replica, for the view models to react to. */
sealed interface ReplicaChange {
    val path: String

    data class Saved(override val path: String) : ReplicaChange
    data class Deleted(override val path: String) : ReplicaChange
    /** Content arrived from the server and replaced what was here. */
    data class PulledFromServer(override val path: String) : ReplicaChange
    /** The whole replica was repopulated. */
    data class Reloaded(override val path: String = "") : ReplicaChange
}

/**
 * The app's view of its local notes: the only thing that writes to the replica.
 *
 * Reads and writes are local and always succeed, which is the point — the app works with the server
 * unreachable, and a save is never lost to a network error. Saving marks the file dirty; a dirty
 * file is what the sync engine will later push (T10). Nothing here talks to the network.
 *
 * Every mutation is serialised through a mutex: saves arrive from the UI while pulls arrive from
 * sync, and the metadata must not be read-modify-written by two of them at once.
 */
class DocumentStore(private val storage: ReplicaStorage) {

    private val mutex = Mutex()
    private val _changes = MutableSharedFlow<ReplicaChange>(extraBufferCapacity = 64)

    /** Changes to the replica, for view models to observe. */
    val changes: Flow<ReplicaChange> = _changes.asSharedFlow()

    suspend fun paths(): List<String> = storage.list()

    suspend fun read(path: String): String? = storage.read(path)

    /** True when [path] has local changes that have not reached the server. */
    suspend fun isDirty(path: String): Boolean = storage.readMeta().files[path]?.dirty == true

    suspend fun dirtyPaths(): List<String> =
        storage.readMeta().files.values.filter { it.dirty }.map { it.path }.sorted()

    suspend fun meta(path: String): FileMeta? = storage.readMeta().files[path]

    /**
     * Writes [content] locally and marks it dirty.
     *
     * Deliberately cannot fail on a network problem: there is no network here. The file is on disk
     * before this returns, so an edit made offline survives a restart.
     */
    suspend fun save(path: String, content: String) {
        requireSafeReplicaPath(path)
        mutex.withLock { saveLocked(path, content) }
        _changes.emit(ReplicaChange.Saved(path))
    }

    /**
     * [save], but only while [path] still holds [expected]; false, writing nothing, when it moved.
     *
     * For a write computed from what was read a moment ago — a merge, across a network round trip —
     * which must not land on an edit saved in between.
     */
    suspend fun saveIfUnchanged(path: String, expected: String, content: String): Boolean {
        requireSafeReplicaPath(path)
        val saved = mutex.withLock {
            if (storage.read(path) != expected) return@withLock false
            saveLocked(path, content)
            true
        }
        if (saved) _changes.emit(ReplicaChange.Saved(path))
        return saved
    }

    private suspend fun saveLocked(path: String, content: String) {
        storage.write(path, content)
        val meta = storage.readMeta()
        val existing = meta.files[path]
        storage.writeMeta(
            meta.copy(
                files = meta.files + (path to (existing ?: FileMeta(path)).copy(
                    path = path,
                    size = content.length.toLong(),
                    dirty = true,
                )),
            ),
        )
    }

    /**
     * Deletes [path] here and records it for the server as a tombstone.
     *
     * A file the server never had is simply forgotten; there is nobody to tell. Otherwise the
     * tombstone keeps the etag the file was last synced at, and sync deletes only that version.
     */
    suspend fun delete(path: String) {
        mutex.withLock { tombstoneLocked(path) }
        _changes.emit(ReplicaChange.Deleted(path))
    }

    private suspend fun tombstoneLocked(path: String) {
        storage.delete(path)
        storage.deleteBase(path)
        val meta = storage.readMeta()
        val existing = meta.files[path]
        val files = if (existing?.etag == null) {
            meta.files - path
        } else {
            meta.files + (path to existing.copy(deleted = true, dirty = false, size = 0))
        }
        storage.writeMeta(meta.copy(files = files))
    }

    /** True when a note exists at [path] here. */
    private suspend fun isTaken(path: String): Boolean =
        storage.read(path) != null

    /**
     * Creates [path] with [content], dirty and never synced, so the next sync pushes it as new.
     * Returns false, writing nothing, when a note already exists there.
     *
     * A path whose tombstone has not reached the server yet is taken over rather than refused: the
     * new content is pushed as an edit of the version that was deleted, which is what the user sees.
     */
    suspend fun create(path: String, content: String): Boolean {
        requireSafeReplicaPath(path)
        val created = mutex.withLock {
            if (isTaken(path)) return@withLock false
            createLocked(path, content)
            true
        }
        if (created) _changes.emit(ReplicaChange.Saved(path))
        return created
    }

    private suspend fun createLocked(path: String, content: String) {
        storage.write(path, content)
        val meta = storage.readMeta()
        val tombstone = meta.files[path]?.takeIf { it.deleted }
        storage.writeMeta(
            meta.copy(
                files = meta.files + (path to FileMeta(
                    path = path,
                    size = content.length.toLong(),
                    etag = tombstone?.etag,
                    dirty = true,
                )),
            ),
        )
    }

    /**
     * Moves [from] to [to]: a new note with the same content, and a tombstone for the old one.
     * Returns false, changing nothing, when [from] is missing or [to] is taken.
     *
     * Both happen under one lock, so nothing here ever sees one without the other; the sync engine
     * pushes the new note before it deletes the old one, so another device may briefly see both,
     * but never neither. Unsynced edits travel with the content.
     */
    suspend fun rename(from: String, to: String): Boolean {
        requireSafeReplicaPath(to)
        if (from == to) return false
        val renamed = mutex.withLock {
            val content = storage.read(from) ?: return@withLock false
            if (isTaken(to)) return@withLock false
            createLocked(to, content)
            tombstoneLocked(from)
            true
        }
        if (renamed) {
            _changes.emit(ReplicaChange.Deleted(from))
            _changes.emit(ReplicaChange.Saved(to))
        }
        return renamed
    }

    /** Deletions the server has not been told about yet. */
    suspend fun tombstones(): List<FileMeta> =
        storage.readMeta().files.values.filter { it.deleted }.sortedBy { it.path }

    /** Drops [path]'s tombstone once the server has deleted it, or it no longer needs deleting. */
    suspend fun forgetTombstone(path: String) {
        mutex.withLock {
            val meta = storage.readMeta()
            if (meta.files[path]?.deleted == true) storage.writeMeta(meta.copy(files = meta.files - path))
        }
    }

    /**
     * Records content that came from the server: it becomes both the working copy and the base, and
     * the file is no longer dirty.
     *
     * This is how the existing network calls populate the replica today, and how the sync engine
     * will apply a pull tomorrow.
     */
    suspend fun acceptFromServer(
        path: String,
        content: String,
        etag: String? = null,
        generation: Long = 0,
    ) {
        requireSafeReplicaPath(path)
        mutex.withLock { acceptLocked(path, content, etag, generation) }
        _changes.emit(ReplicaChange.PulledFromServer(path))
    }

    private suspend fun acceptLocked(path: String, content: String, etag: String?, generation: Long = 0) {
        storage.write(path, content)
        storage.writeBase(path, content)
        val meta = storage.readMeta()
        storage.writeMeta(
            meta.copy(
                generation = maxOf(meta.generation, generation),
                files = meta.files + (path to FileMeta(
                    path = path,
                    size = content.length.toLong(),
                    etag = etag,
                    dirty = false,
                    lastSyncedGeneration = generation,
                )),
            ),
        )
    }

    /**
     * Replaces the replica with what the server sent, **keeping local edits**.
     *
     * A dirty file is left exactly as it is: it holds work the server has not seen, and overwriting
     * it here would lose an offline edit — the one thing this whole design exists to prevent.
     */
    suspend fun acceptAllFromServer(contents: Map<String, String>, generation: Long = 0) {
        mutex.withLock {
            val meta = storage.readMeta()
            val updated = meta.files.toMutableMap()
            for ((path, content) in contents) {
                requireSafeReplicaPath(path)
                // Local work — an edit, or a deletion — outranks what the server sent.
                if (meta.files[path]?.let { it.dirty || it.deleted } == true) continue
                storage.write(path, content)
                storage.writeBase(path, content)
                updated[path] = FileMeta(
                    path = path,
                    size = content.length.toLong(),
                    dirty = false,
                    lastSyncedGeneration = generation,
                )
            }
            storage.writeMeta(
                meta.copy(generation = maxOf(meta.generation, generation), files = updated),
            )
        }
        _changes.emit(ReplicaChange.Reloaded())
    }

    /**
     * Records that [path]'s current content reached the server: it becomes the base, and the file is
     * no longer dirty.
     *
     * The content is re-read under the lock rather than passed in, so an edit made while the push
     * was in flight stays dirty instead of being marked as synced.
     */
    suspend fun markPushed(path: String, etag: String, pushedContent: String) {
        mutex.withLock {
            val current = storage.read(path)
            val meta = storage.readMeta()
            val existing = meta.files[path] ?: FileMeta(path)
            if (current != pushedContent) {
                // Edited again mid-flight: keep it dirty, but the base did move.
                storage.writeBase(path, pushedContent)
                storage.writeMeta(meta.copy(files = meta.files + (path to existing.copy(etag = etag))))
                return@withLock
            }
            storage.writeBase(path, pushedContent)
            storage.writeMeta(
                meta.copy(
                    files = meta.files + (path to existing.copy(
                        etag = etag,
                        dirty = false,
                        size = pushedContent.length.toLong(),
                    )),
                ),
            )
        }
    }

    /**
     * Settles a conflict the server's way: [path] becomes [serverText] at [serverEtag], and what was
     * here is saved beside it as a conflict copy, whose name is returned. Null when there was nothing
     * here to keep, or it already matched the server.
     *
     * The server's version keeps the path because the server is the source of truth: a file in the
     * notes directory is never replaced by a version nobody merged. The copy is a new note, dirty, so
     * the next sync pushes it and every device sees it until someone resolves it.
     *
     * One lock for both, and the local text read under it, so a save made while the server was
     * being asked lands in the copy rather than being overwritten.
     */
    suspend fun keepServerVersion(path: String, serverText: String, serverEtag: String, timestamp: String): String? {
        requireSafeReplicaPath(path)
        val copy = mutex.withLock {
            val local = storage.read(path)
            val copy = if (local != null && local != serverText) {
                freeCopyName(path, timestamp).also { createLocked(it, local) }
            } else {
                null
            }
            acceptLocked(path, serverText, serverEtag)
            copy
        }
        copy?.let { _changes.emit(ReplicaChange.Saved(it)) }
        _changes.emit(ReplicaChange.PulledFromServer(path))
        return copy
    }

    /** A conflict copy name for [path] that nothing here has yet: two conflicts can share a second. */
    private suspend fun freeCopyName(path: String, timestamp: String): String {
        var name = conflictCopyName(path, timestamp)
        var counter = 2
        while (isTaken(name)) name = conflictCopyName(path, "$timestamp-${counter++}")
        return name
    }

    /**
     * Resolves a conflict in favour of [copy]: its content becomes [path]'s, as an edit to push, and
     * the copy is deleted. False, changing nothing, when either is missing.
     *
     * Under one lock, so the replica never holds the note without the content chosen for it; the
     * engine pushes the edit before it sends the deletion, so the server never has neither.
     */
    suspend fun replaceWithCopy(path: String, copy: String): Boolean {
        requireSafeReplicaPath(path)
        val replaced = mutex.withLock {
            val content = storage.read(copy) ?: return@withLock false
            if (storage.read(path) == null) return@withLock false
            saveLocked(path, content)
            tombstoneLocked(copy)
            true
        }
        if (replaced) {
            _changes.emit(ReplicaChange.Saved(path))
            _changes.emit(ReplicaChange.Deleted(copy))
        }
        return replaced
    }

    /**
     * Remembers how far the replica has caught up, so the next sync can ask for a delta.
     *
     * Taken as the server gives it, even when lower: a server whose clock went back across a
     * restart counts from behind where it was, and a client kept at the higher number would ask
     * for changes since a generation that server reaches only later, missing all before it.
     */
    suspend fun setGeneration(generation: Long) {
        mutex.withLock {
            val meta = storage.readMeta()
            storage.writeMeta(meta.copy(generation = generation))
        }
    }

    suspend fun generation(): Long = storage.readMeta().generation

    /** Removes [path] locally without recording a deletion to push. */
    suspend fun removeLocally(path: String) {
        mutex.withLock {
            storage.delete(path)
            storage.deleteBase(path)
            val meta = storage.readMeta()
            storage.writeMeta(meta.copy(files = meta.files - path))
        }
        _changes.emit(ReplicaChange.Deleted(path))
    }

    /** The content as the server last had it: the ancestor a three-way merge (T11) will need. */
    suspend fun base(path: String): String? = storage.readBase(path)
}
