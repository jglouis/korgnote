package com.korgnote.common.replica

import kotlinx.serialization.Serializable

/**
 * What the client knows about one file, beyond its content.
 *
 * [etag] and [lastSyncedGeneration] are what the server last told us; [dirty] is ours alone and
 * means "changed locally since that sync". A dirty file is the only thing that ever needs pushing,
 * and it survives restarts because this metadata is written to disk beside the replica.
 */
@Serializable
data class FileMeta(
    val path: String,
    val size: Long = 0,
    val mtime: Long = 0,
    val etag: String? = null,
    val dirty: Boolean = false,
    val lastSyncedGeneration: Long = 0,
    /**
     * A tombstone: deleted here, and the server has not been told yet.
     *
     * The working copy and base are already gone; what is kept is [etag], the version the delete
     * was made against, so the server deletes only that version. Without it the next full manifest
     * would list the file and the replica would take it straight back.
     */
    val deleted: Boolean = false,
)

/** The whole of `meta.json`: what the replica believes about every file it holds. */
@Serializable
data class ReplicaMeta(
    val generation: Long = 0,
    val files: Map<String, FileMeta> = emptyMap(),
)

/**
 * The replica's storage, abstracted so the store can be exercised without a disk.
 *
 * Two layers are kept: the working copy the user edits, and `base` — the content as it was when it
 * last matched the server. Keeping the base as stored bytes rather than re-fetching it at conflict
 * time is what makes a three-way merge possible *offline*, which is exactly when conflicts get
 * made. See `docs/05-sync-protocol.md`.
 *
 * Paths are relative, `/`-separated, and never start with a slash.
 */
interface ReplicaStorage {
    /** Paths of every file in the working copy, sorted. */
    suspend fun list(): List<String>

    suspend fun read(path: String): String?
    suspend fun write(path: String, content: String)
    suspend fun delete(path: String)

    /** The last-synced content of [path], the ancestor a three-way merge needs. */
    suspend fun readBase(path: String): String?
    suspend fun writeBase(path: String, content: String)
    suspend fun deleteBase(path: String)

    suspend fun readMeta(): ReplicaMeta
    suspend fun writeMeta(meta: ReplicaMeta)
}

/** Rejects paths that would escape the replica directory or are not relative. */
internal fun requireSafeReplicaPath(path: String) {
    require(path.isNotBlank()) { "empty path" }
    require(!path.startsWith("/")) { "path must be relative: $path" }
    require(!path.contains("\\")) { "path must use '/': $path" }
    require(path.split('/').none { it == ".." || it == "." }) { "path must not traverse: $path" }
}
