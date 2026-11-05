package com.korgnote.common.replica

import kotlinx.serialization.Serializable

/**
 * What the client knows about one file, beyond its content.
 *
 * [etag] is what the server last told us; [dirty] is ours alone and means "changed locally since
 * that sync". A dirty file is the only thing that ever needs pushing, and it survives restarts because
 * this metadata is written to disk beside the replica.
 *
 * Files already on devices also hold `size`, `mtime` and `lastSyncedGeneration`, which nothing read.
 * They are skipped on reading, and go the next time the file is written.
 */
@Serializable
data class FileMeta(
    val path: String,
    val etag: String? = null,
    val dirty: Boolean = false,
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

    /**
     * Every note with how its file looks now, enough to tell that it changed since without reading
     * it. What does not have files to look at makes do with the text's hash. Null when the notes cannot
     * be reached to tell, which says nothing about whether there are any.
     */
    suspend fun stamps(): Map<String, FileStamp>? =
        list().mapNotNull { path -> read(path)?.let { path to FileStamp(contentHash(it), it.length.toLong()) } }.toMap()

    /**
     * The texts of [paths], those that can be read as they now are: at once, where that is quicker than
     * in turn. Unlike [read], never what was last seen of a file that cannot be reached.
     */
    suspend fun readSeveral(paths: List<String>): Map<String, String> =
        paths.mapNotNull { path -> read(path)?.let { path to it } }.toMap()
    suspend fun write(path: String, content: String)
    suspend fun delete(path: String)

    /** The last-synced content of [path], the ancestor a three-way merge needs. */
    suspend fun readBase(path: String): String?
    suspend fun writeBase(path: String, content: String)
    suspend fun deleteBase(path: String)

    suspend fun readMeta(): ReplicaMeta
    suspend fun writeMeta(meta: ReplicaMeta)
}

/** A 64-bit hash of [text] (FNV-1a), to tell later whether a text is the one it was. */
fun contentHash(text: String): Long {
    var hash = -3750763034362895579L
    for (char in text) {
        hash = (hash xor char.code.toLong()) * 1099511628211L
    }
    return hash
}

/** Rejects paths that would escape the replica directory or are not relative. */
internal fun requireSafeReplicaPath(path: String) {
    require(path.isNotBlank()) { "empty path" }
    require(!path.startsWith("/")) { "path must be relative: $path" }
    require(!path.contains("\\")) { "path must use '/': $path" }
    require(path.split('/').none { it == ".." || it == "." }) { "path must not traverse: $path" }
}
