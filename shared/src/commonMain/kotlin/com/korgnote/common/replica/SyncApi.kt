package com.korgnote.common.replica

/** One file as the server's manifest describes it. */
data class RemoteEntry(val path: String, val etag: String, val size: Long = 0, val mtime: Long = 0)

data class RemoteManifest(
    val generation: Long,
    /** False when this is a delta: paths not mentioned are unchanged, not absent. */
    val complete: Boolean,
    val entries: List<RemoteEntry>,
    val deleted: List<String> = emptyList(),
)

data class RemoteFile(val path: String, val etag: String, val text: String)

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
 * The sync API v1 as the client needs it.
 *
 * An interface so the engine can be tested without a server, and so the transport stays in the
 * client module while the algorithm lives here.
 */
interface SyncApi {
    suspend fun manifest(since: Long? = null): RemoteManifest
    suspend fun fetch(path: String): RemoteFile?

    /**
     * Many files in one request: every file the server holds, or what changed since [since]. It may
     * stop short of some, as the server caps its size; those are fetched one by one. Null where the
     * transport has no such call, which leaves every file to [fetch].
     */
    suspend fun bundle(since: Long? = null): List<RemoteFile>? = null
    /**
     * Writes [path]. With [ifMatch], only over that version; with [mustNotExist] (`If-None-Match: *`),
     * only when the server has nothing there, so a new note never replaces one made elsewhere.
     */
    suspend fun push(
        path: String,
        content: String,
        ifMatch: String?,
        mustNotExist: Boolean = false,
    ): PushResult

    /** Deletes [path], but only the version [ifMatch] names: an edit made elsewhere is never deleted. */
    suspend fun delete(path: String, ifMatch: String): DeleteResult

    /**
     * Streams change notifications until cancelled.
     *
     * A hint channel: correctness comes from the manifest diff, so an implementation that never
     * emits is merely slower, never wrong. That is why it defaults to doing nothing.
     */
    suspend fun events(onChange: suspend (path: String, deleted: Boolean) -> Unit) {}
}
