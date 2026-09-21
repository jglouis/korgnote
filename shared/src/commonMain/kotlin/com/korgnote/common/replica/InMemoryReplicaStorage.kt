package com.korgnote.common.replica

/**
 * The replica held in memory: the web build's storage, and what every [DocumentStore] test runs
 * against so the store's behaviour is proven without a filesystem.
 */
class InMemoryReplicaStorage(
    initialFiles: Map<String, String> = emptyMap(),
) : ReplicaStorage {
    private val files = initialFiles.toMutableMap()
    private val base = mutableMapOf<String, String>()
    private var meta = ReplicaMeta()

    override suspend fun list(): List<String> = files.keys.sorted()

    override suspend fun read(path: String): String? = files[path]

    override suspend fun write(path: String, content: String) {
        requireSafeReplicaPath(path)
        files[path] = content
    }

    override suspend fun delete(path: String) {
        files.remove(path)
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
