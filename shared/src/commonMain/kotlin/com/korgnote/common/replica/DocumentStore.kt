package com.korgnote.common.replica

import kotlinx.coroutines.sync.Mutex
import com.korgnote.common.sync.conflictCopyName
import kotlinx.coroutines.sync.withLock

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
    }

    /**
     * [save], but only while [path] still holds [expected]; false, writing nothing, when it moved.
     *
     * For a write computed from what was read a moment ago — a merge, across a network round trip —
     * which must not land on an edit saved in between.
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
     * Deletes [path] here and records it for the server as a tombstone.
     *
     * A file the server never had is simply forgotten; there is nobody to tell. Otherwise the
     * tombstone keeps the etag the file was last synced at, and sync deletes only that version.
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
        return mutex.withLock {
            val content = storage.read(from) ?: return@withLock false
            if (isTaken(to)) return@withLock false
            createLocked(to, content)
            tombstoneLocked(from)
            true
        }
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
    suspend fun acceptFromServer(path: String, content: String, etag: String? = null) {
        requireSafeReplicaPath(path)
        mutex.withLock { acceptLocked(path, content, etag) }
    }

    /**
     * [acceptFromServer] for text fetched across a network round trip: only while [path] is as it
     * was when the server was asked, which is at [knownEtag] with nothing done to it here. False,
     * writing nothing, when it moved, and what was fetched is dropped.
     *
     * The check and the write share the lock, so a save cannot land between them and be overwritten
     * and marked as synced. An edit left dirty is not lost track of: the next cycle finds it meeting
     * the server's change, and merges them.
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

    private suspend fun acceptLocked(path: String, content: String, etag: String?) {
        storage.write(path, content)
        storage.writeBase(path, content)
        val meta = storage.readMeta()
        storage.writeMeta(meta.copy(files = meta.files + (path to FileMeta(path = path, etag = etag))))
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
        return mutex.withLock {
            val content = storage.read(copy) ?: return@withLock false
            if (storage.read(path) == null) return@withLock false
            saveLocked(path, content)
            tombstoneLocked(copy)
            true
        }
    }

    /**
     * Resolves a conflict with [content] made from both versions, chunk by chunk: it becomes
     * [path]'s, as an edit to push, and [copy] is deleted. When [content] is what the note already
     * has, only the copy goes, and nothing is pushed for the note.
     *
     * Only while the note still holds [expectedNote] and the copy [expectedCopy], the texts the
     * choices were made on: false, changing nothing, when either moved or is gone — a sync landing
     * while the reader was choosing.
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
    }

    /** The content as the server last had it: the ancestor a three-way merge (T11) will need. */
    suspend fun base(path: String): String? = storage.readBase(path)
}
