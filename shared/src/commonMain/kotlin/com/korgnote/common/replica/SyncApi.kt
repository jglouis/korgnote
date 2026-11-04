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

/**
 * What a write must find on the server, or be refused. There is no unconditional write: the protocol
 * allows one, and with it a device could replace an edit it never saw.
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
 * The sync API v1 as the client needs it.
 *
 * An interface so the engine can be tested without a server, and so the transport stays in the
 * client module while the algorithm lives here.
 */
interface SyncApi {
    suspend fun manifest(since: Long? = null): RemoteManifest
    suspend fun fetch(path: String): FetchResult

    /**
     * Many files in one request: every file the server holds, or what changed since [since]. It may
     * stop short of some, as the server caps its size; those are fetched one by one. Null where the
     * transport has no such call, which leaves every file to [fetch].
     */
    suspend fun bundle(since: Long? = null): List<RemoteFile>? = null
    /** Writes [path], if the server's copy meets [condition]. */
    suspend fun push(path: String, content: String, condition: PushCondition): PushResult

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
