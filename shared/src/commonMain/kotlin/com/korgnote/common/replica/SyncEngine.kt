package com.korgnote.common.replica

/** What one sync cycle did, mostly so tests and the UI can say something specific. */
data class SyncReport(
    val pulled: List<String> = emptyList(),
    val pushed: List<String> = emptyList(),
    val deletedLocally: List<String> = emptyList(),
    val deletedRemotely: List<String> = emptyList(),
    val conflicted: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val generation: Long = 0,
) {
    val changedAnything: Boolean
        get() = pulled.isNotEmpty() || pushed.isNotEmpty() ||
            deletedLocally.isNotEmpty() || deletedRemotely.isNotEmpty()
}

/**
 * Reconciles the local replica with the server, per `docs/05-sync-protocol.md`.
 *
 * Each path is decided by comparing three versions: local (L), base (B — what the server last had)
 * and remote (R). The store tracks L against B with its dirty flag and B against R with the stored
 * etag, so the whole table reduces to two booleans.
 *
 * Merging is **not** here: a push refused with 409 keeps the local version and flags it (T11). The
 * rule that matters until then is that no branch ever discards an edit the user made.
 */
class SyncEngine(
    private val store: DocumentStore,
    private val api: SyncApi,
) {

    /** One full cycle: diff the manifest, pull what is stale, push what is dirty. */
    suspend fun sync(): SyncReport {
        val since = store.generation().takeIf { it > 0 }
        val manifest = api.manifest(since)

        val pulled = mutableListOf<String>()
        val pushed = mutableListOf<String>()
        val deletedLocally = mutableListOf<String>()
        val deletedRemotely = mutableListOf<String>()
        val conflicted = mutableListOf<String>()
        val failed = mutableListOf<String>()

        val remote = manifest.entries.associateBy { it.path }
        val localPaths = store.paths().toSet()

        for (entry in manifest.entries) {
            val meta = store.meta(entry.path)
            val haveLocally = entry.path in localPaths
            val dirty = meta?.dirty == true

            when {
                // Never seen it, or the copy went missing: take the server's.
                !haveLocally || meta == null -> if (pull(entry.path)) pulled.add(entry.path)

                // Untouched locally and unchanged remotely: nothing to do.
                !dirty && meta.etag == entry.etag -> Unit

                // Untouched locally, moved remotely: take theirs.
                !dirty -> if (pull(entry.path)) pulled.add(entry.path)

                // Edited locally, server unchanged since we synced: push.
                meta.etag == entry.etag -> when (val result = pushLocal(entry.path, meta.etag)) {
                    PushOutcome.PUSHED -> pushed.add(entry.path)
                    PushOutcome.CONFLICT -> conflicted.add(entry.path)
                    PushOutcome.FAILED -> failed.add(entry.path)
                    PushOutcome.SKIPPED -> Unit
                }

                // Both moved. Merging is T11; until then keep the local edit and say so.
                else -> {
                    store.markConflicted(entry.path, entry.etag)
                    conflicted.add(entry.path)
                }
            }
        }

        // A complete manifest is authoritative about what exists; a delta is not, so absence from a
        // delta means "unchanged", never "deleted".
        if (manifest.complete) {
            for (path in localPaths - remote.keys) {
                val meta = store.meta(path)
                when {
                    // Never synced: it is a local creation, not a remote deletion.
                    meta == null || meta.etag == null ->
                        if (pushNew(path)) pushed.add(path) else failed.add(path)
                    // Deleted on the server but edited here: keep it and put it back.
                    meta.dirty -> if (pushNew(path)) pushed.add(path) else failed.add(path)
                    else -> {
                        store.removeLocally(path)
                        deletedRemotely.add(path)
                    }
                }
            }
        }

        for (path in manifest.deleted) {
            val meta = store.meta(path) ?: continue
            if (meta.dirty) {
                if (pushNew(path)) pushed.add(path) else failed.add(path)
            } else {
                store.removeLocally(path)
                deletedRemotely.add(path)
            }
        }

        store.setGeneration(manifest.generation)
        return SyncReport(
            pulled = pulled,
            pushed = pushed,
            deletedLocally = deletedLocally,
            deletedRemotely = deletedRemotely,
            conflicted = conflicted,
            failed = failed,
            generation = manifest.generation,
        )
    }

    /**
     * Fetches one path, for an SSE `changed` event.
     *
     * A local edit wins: the event is a hint, and the next full cycle will decide properly.
     */
    suspend fun pull(path: String): Boolean {
        if (store.meta(path)?.dirty == true) return false
        val file = api.fetch(path) ?: return false
        store.acceptFromServer(path, file.text, file.etag)
        return true
    }

    /**
     * Follows the server's event stream, applying each change to the replica.
     *
     * Runs until cancelled. [onApplied] fires only when the replica actually moved, so the UI is not
     * redrawn for an event about a file the user is editing.
     */
    suspend fun watch(onApplied: suspend (String) -> Unit) {
        api.events { path, deleted ->
            if (deleted) {
                if (store.meta(path)?.dirty != true) {
                    store.removeLocally(path)
                    onApplied(path)
                }
            } else if (pull(path)) {
                onApplied(path)
            }
        }
    }

    private enum class PushOutcome { PUSHED, CONFLICT, FAILED, SKIPPED }

    private suspend fun pushLocal(path: String, ifMatch: String?): PushOutcome {
        val content = store.read(path) ?: return PushOutcome.SKIPPED
        return when (val result = api.push(path, content, ifMatch)) {
            is PushResult.Pushed -> {
                store.markPushed(path, result.etag, content)
                PushOutcome.PUSHED
            }
            is PushResult.Conflict -> {
                store.markConflicted(path, result.currentEtag)
                PushOutcome.CONFLICT
            }
            is PushResult.Failed -> PushOutcome.FAILED
        }
    }

    /** Pushes a file the server does not have, or no longer has. */
    private suspend fun pushNew(path: String): Boolean {
        val content = store.read(path) ?: return false
        return when (val result = api.push(path, content, ifMatch = null)) {
            is PushResult.Pushed -> {
                store.markPushed(path, result.etag, content)
                true
            }
            else -> false
        }
    }
}
