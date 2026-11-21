package com.korgnote.common.replica

/**
 * Remote manifest entry for a note file tracked by synchronization.
 *
 * Records the entry's relative path together with remote object metadata used to compare local and
 * remote state, detect changes, and decide whether content must be uploaded, downloaded, or removed.
 * The entity tag identifies the remote content or revision as reported by the backing storage, while
 * size and modification time provide additional observable state when available.
 */
data class RemoteEntry(val path: String, val etag: String, val size: Long = 0, val mtime: Long = 0)

data class RemoteManifest(
    val generation: Long,
    /** False when this is a delta: paths not mentioned are unchanged, not absent. */
    val complete: Boolean,
    val entries: List<RemoteEntry>,
    val deleted: List<String> = emptyList(),
)

data class RemoteFile(val path: String, val etag: String, val text: String)

/**
 * Condition that must be satisfied by the remote item before a push is accepted.
 *
 * Used to make synchronization writes conditional and prevent overwriting remote changes made by
 * another client. Implementations represent HTTP-style preconditions based on entity tags: [Matches]
 * requires the remote item to still have a known ETag, while [Absent] requires the remote item not
 * to exist.
 */
sealed interface PushCondition {
    /** Only over this version: `If-Match`. */
    data class Matches(val etag: String) : PushCondition
    /** Only when the server has nothing there, so a new note never replaces one made elsewhere: `If-None-Match: *`. */
    data object Absent : PushCondition
}

sealed interface FetchResult {
    data class Fetched(val file: RemoteFile) : FetchResult
    /** Nothing at that path: it was deleted since it was listed, or never there. */
    data object Gone : FetchResult
    /** The server is there and would not serve it. The file may well exist, and nothing can be said of it. */
    data class Failed(val reason: String) : FetchResult
}

sealed interface PushResult {
    data class Pushed(val etag: String) : PushResult
    /** The server moved on; [currentEtag] is what it holds now. */
    data class Conflict(val currentEtag: String?) : PushResult
    data class Failed(val reason: String) : PushResult
}

sealed interface DeleteResult {
    data object Deleted : DeleteResult
    /** The server's copy moved since the etag the delete was made against; it holds [currentEtag]. */
    data class Conflict(val currentEtag: String?) : DeleteResult
    /** Nothing at that path any more: someone else deleted it first. */
    data object Gone : DeleteResult
    data class Failed(val reason: String) : DeleteResult
}

/**
 * Remote synchronization endpoint used to list, read, write, delete, and observe files.
 *
 * The API is designed around optimistic concurrency. Remote items are identified by their path and
 * versioned by ETags, and mutating operations are conditional so that changes made by another client
 * are not overwritten or deleted accidentally.
 *
 * A synchronization client typically obtains remote state with [manifest], retrieves file contents
 * with [fetch] or [bundle], and applies local changes with [push] and [delete]. Implementations may
 * also provide [events] as a best-effort notification stream to prompt faster resynchronization.
 *
 * Suspend functions may perform network or storage I/O. Operation-level failures that are part of
 * normal synchronization flow are reported through the corresponding result types.
 */
interface SyncApi {
    suspend fun manifest(since: Long? = null): RemoteManifest
    suspend fun fetch(path: String): FetchResult

    /**
     * Retrieves a batch of remote files, including their paths, ETags, and text content.
     *
     * When [since] is provided, the returned bundle may be limited to files changed after that
     * synchronization point, according to the implementation's remote change tracking. When [since] is
     * null, the bundle represents the implementation's default file set, typically the current remote
     * contents available for synchronization.
     *
     * A null result indicates that bundled retrieval is not available and callers should use
     * [manifest] and [fetch] to obtain remote state and file contents.
     *
     * @param since optional synchronization point used to request only files changed after that point.
     * @return the bundled remote files, or null if bundled retrieval is not supported.
     */
    suspend fun bundle(since: Long? = null): List<RemoteFile>? = null
    /**
     * Pushes [content] to the remote item at [path] if [condition] is satisfied.
     *
     * The write is conditional to avoid overwriting remote changes made by another client. Use
     * [PushCondition.Matches] to update only a known remote version, or [PushCondition.Absent] to create
     * the item only when it does not already exist.
     *
     * @param path the remote path of the item to create or update.
     * @param content the text content to store remotely.
     * @param condition the precondition that must hold for the push to be accepted.
     * @return the result of the push, including the new ETag on success, conflict information when the
     * remote state does not satisfy the condition, or failure details.
     */
    suspend fun push(path: String, content: String, condition: PushCondition): PushResult

    /**
     * Deletes the remote item at [path] if its current ETag matches [ifMatch].
     *
     * The delete is conditional to avoid removing remote changes made by another client. If the remote
     * item has changed since the caller observed it, the operation reports a conflict instead of
     * deleting the item.
     *
     * @param path the remote path of the item to delete.
     * @param ifMatch the ETag that the remote item must currently have for the delete to be accepted.
     * @return the result of the delete, indicating whether the item was deleted, already gone, conflicted
     * with a newer remote version, or failed.
     */
    suspend fun delete(path: String, ifMatch: String): DeleteResult

    /**
     * Subscribes to remote change events and invokes [onChange] for each changed item.
     *
     * The callback receives the remote path of the changed item and a flag indicating whether the item
     * was deleted. Implementations may keep this call active while listening for events and may invoke
     * the callback multiple times.
     *
     * @param onChange callback invoked when a remote item changes; receives the changed item path and
     * whether the change represents a deletion.
     */
    suspend fun events(onChange: suspend (path: String, deleted: Boolean) -> Unit) {}
}
