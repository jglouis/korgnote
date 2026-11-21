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
    /**
     * Lists the paths currently present in the replica's working copy.
     *
     * Returned paths are relative to the replica root, use `/` as the separator, and do not start with
     * a slash.
     *
     * @return the paths of files currently available in the working copy.
     */
    suspend fun list(): List<String>

    /**
     * Reads the current content of a file in the replica's working copy.
     *
     * The path is relative to the replica root, uses `/` as the separator, and does not start with a
     * slash.
     *
     * @param path the relative path of the file to read.
     * @return the file content, or null if the file is not available to read.
     */
    suspend fun read(path: String): String?

    /**
     * Builds stamps for the files currently readable in the replica's working copy.
     *
     * Each stamp is keyed by the file's relative path and is derived from the file's current content hash
     * and content length. Paths that are listed but cannot be read are omitted.
     *
     * @return a map from relative file paths to their current stamps, or null if stamps cannot be
     * produced.
     */
    suspend fun stamps(): Map<String, FileStamp>? =
        list().mapNotNull { path -> read(path)?.let { path to FileStamp(contentHash(it), it.length.toLong()) } }.toMap()

    /**
     * Reads the current content of several files in the replica's working copy.
     *
     * The paths are relative to the replica root, use `/` as the separator, and do not start with a
     * slash. Paths that are not available to read are omitted from the returned map.
     *
     * @param paths the relative paths of the files to read.
     * @return a map from each readable relative path to its current file content.
     */
    suspend fun readSeveral(paths: List<String>): Map<String, String> =
        paths.mapNotNull { path -> read(path)?.let { path to it } }.toMap()
    /**
     * Writes content to a file in the replica's working copy.
     *
     * The path is relative to the replica root, uses `/` as the separator, and does not start with a
     * slash. If a file already exists at the path, its current content is replaced.
     *
     * @param path the relative path of the file to write.
     * @param content the content to write to the file.
     */
    suspend fun write(path: String, content: String)
    /**
     * Deletes a file from the replica's working copy.
     *
     * The path is relative to the replica root, uses `/` as the separator, and does not start with a
     * slash.
     *
     * @param path the relative path of the file to delete.
     */
    suspend fun delete(path: String)

    /**
     * Reads the stored base content of a file for this replica.
     *
     * The path is relative to the replica root, uses `/` as the separator, and does not start with a
     * slash.
     *
     * @param path the relative path of the file whose base content should be read.
     * @return the stored base content, or null if no base content is available for the path.
     */
    suspend fun readBase(path: String): String?
    /**
     * Stores the base content of a file for this replica.
     *
     * The path is relative to the replica root, uses `/` as the separator, and does not start with a
     * slash. If base content already exists for the path, it is replaced.
     *
     * @param path the relative path of the file whose base content should be stored.
     * @param content the base content to store for the file.
     */
    suspend fun writeBase(path: String, content: String)
    /**
     * Deletes the stored base content of a file for this replica.
     *
     * The path is relative to the replica root, uses `/` as the separator, and does not start with a
     * slash.
     *
     * @param path the relative path of the file whose stored base content should be deleted.
     */
    suspend fun deleteBase(path: String)

    /**
     * Reads the stored metadata for this replica.
     *
     * The metadata describes the replica's known generation and file state.
     *
     * @return the current replica metadata.
     */
    suspend fun readMeta(): ReplicaMeta
    /**
     * Writes the stored metadata for this replica.
     *
     * The metadata describes the replica's known generation and file state. If metadata already exists,
     * it is replaced.
     *
     * @param meta the replica metadata to store.
     */
    suspend fun writeMeta(meta: ReplicaMeta)
}

/**
 * Computes a deterministic 64-bit content hash for the supplied text.
 *
 * The hash is derived from each character code in order and is intended for detecting content changes,
 * not for cryptographic verification.
 *
 * @param text the text to hash.
 * @return the computed hash value.
 */
fun contentHash(text: String): Long {
    var hash = -3750763034362895579L
    for (char in text) {
        hash = (hash xor char.code.toLong()) * 1099511628211L
    }
    return hash
}

/**
 * Verifies that [path] is a safe relative replica path.
 *
 * The path must be non-blank, relative, use `/` as its separator, and must not contain `.` or `..`
 * path segments. Throws [IllegalArgumentException] if any requirement is not met.
 *
 * @param path the replica path to validate.
 */
internal fun requireSafeReplicaPath(path: String) {
    require(path.isNotBlank()) { "empty path" }
    require(!path.startsWith("/")) { "path must be relative: $path" }
    require(!path.contains("\\")) { "path must use '/': $path" }
    require(path.split('/').none { it == ".." || it == "." }) { "path must not traverse: $path" }
}
