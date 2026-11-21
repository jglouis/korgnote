package com.korgnote.common.replica

/**
 * In-memory implementation of [ReplicaStorage] for tests and non-persistent replicas.
 *
 * Stores the working copy, base contents, and replica metadata in mutable memory structures. Data is
 * lost when the instance is discarded. Files supplied through [initialFiles] populate only the
 * working copy; base contents and metadata start empty unless written later through the storage API.
 *
 * File stamps are derived from each file's content hash and length, and are cached until the
 * corresponding working-copy file is changed or deleted. Written working-copy and base paths are
 * validated as safe replica-relative paths.
 *
 * This implementation does not provide synchronization or persistence guarantees beyond the lifetime
 * of the object.
 */
class InMemoryReplicaStorage(
    initialFiles: Map<String, String> = emptyMap(),
) : ReplicaStorage {
    private val files = initialFiles.toMutableMap()

    /**
     * Current in-memory stamps for files in the replica, keyed by replica-relative path.
     *
     * Mirrors the visible file set tracked by storage and is used to report file state and detect
     * changes without reading file contents.
     */
    private val stampsOfFiles = mutableMapOf<String, FileStamp>()
    private val base = mutableMapOf<String, String>()
    private var meta = ReplicaMeta()

    override suspend fun list(): List<String> = files.keys.sorted()

    override suspend fun read(path: String): String? = files[path]

    override suspend fun stamps(): Map<String, FileStamp> =
        files.mapValues { (path, text) -> stampsOfFiles.getOrPut(path) { FileStamp(contentHash(text), text.length.toLong()) } }

    override suspend fun write(path: String, content: String) {
        requireSafeReplicaPath(path)
        files[path] = content
        stampsOfFiles.remove(path)
    }

    override suspend fun delete(path: String) {
        files.remove(path)
        stampsOfFiles.remove(path)
    }

    override suspend fun readBase(path: String): String? = base[path]

    override suspend fun writeBase(path: String, content: String) {
        requireSafeReplicaPath(path)
        base[path] = content
    }

    override suspend fun deleteBase(path: String) {
        base.remove(path)
    }

    override suspend fun readMeta(): ReplicaMeta = meta

    override suspend fun writeMeta(meta: ReplicaMeta) {
        this.meta = meta
    }
}
