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

/**
 * The sync API v1 as the client needs it.
 *
 * An interface so the engine can be tested without a server, and so the transport stays in the
 * client module while the algorithm lives here.
 */
interface SyncApi {
    suspend fun manifest(since: Long? = null): RemoteManifest
    suspend fun fetch(path: String): RemoteFile?
    suspend fun push(path: String, content: String, ifMatch: String?): PushResult
    suspend fun delete(path: String, ifMatch: String): Boolean

    /**
     * Streams change notifications until cancelled.
     *
     * A hint channel: correctness comes from the manifest diff, so an implementation that never
     * emits is merely slower, never wrong. That is why it defaults to doing nothing.
     */
    suspend fun events(onChange: suspend (path: String, deleted: Boolean) -> Unit) {}
}
