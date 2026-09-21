package com.korgnote.common.replica

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
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
        mutex.withLock {
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
        _changes.emit(ReplicaChange.Saved(path))
    }

    /** Removes [path] and its base copy. */
    suspend fun delete(path: String) {
        mutex.withLock {
            storage.delete(path)
            storage.deleteBase(path)
            val meta = storage.readMeta()
            storage.writeMeta(meta.copy(files = meta.files - path))
        }
        _changes.emit(ReplicaChange.Deleted(path))
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
        mutex.withLock {
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
        _changes.emit(ReplicaChange.PulledFromServer(path))
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
                if (meta.files[path]?.dirty == true) continue
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

    /** The content as the server last had it: the ancestor a three-way merge (T11) will need. */
    suspend fun base(path: String): String? = storage.readBase(path)
}
