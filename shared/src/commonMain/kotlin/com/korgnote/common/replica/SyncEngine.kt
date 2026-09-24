package com.korgnote.common.replica

import com.korgnote.common.sync.MergeResult
import com.korgnote.common.sync.merge

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
    /**
     * Stamp for conflict copies, as `yyyyMMdd-HHmmss`. Injected rather than read from a clock so
     * the merge behaviour can be tested without one.
     */
    private val timestamp: () -> String = { "unknown" },
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
        val tombstones = store.tombstones().associateBy { it.path }
        // Deletions wait until everything else is pushed: a rename is a new note and a tombstone,
        // and another device must never catch the old one gone before the new one exists.
        val pendingDeletes = mutableListOf<FileMeta>()

        fun record(outcome: PushOutcome, path: String) = when (outcome) {
            PushOutcome.PUSHED -> pushed.add(path)
            PushOutcome.CONFLICT -> conflicted.add(path)
            PushOutcome.FAILED -> failed.add(path)
            PushOutcome.SKIPPED -> Unit
        }

        for (entry in manifest.entries) {
            val tombstone = tombstones[entry.path]
            if (tombstone != null) {
                if (tombstone.etag == entry.etag) {
                    pendingDeletes.add(tombstone)
                } else {
                    // Edited elsewhere after the version deleted here: the edit wins, and the note
                    // comes back rather than taking someone's work with it.
                    store.forgetTombstone(entry.path)
                    if (pull(entry.path)) pulled.add(entry.path)
                }
                continue
            }
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
                meta.etag == entry.etag -> record(pushLocal(entry.path, meta.etag), entry.path)

                // Both moved: try to reconcile them, and only involve the user if that fails.
                else -> when (reconcile(entry.path, entry.etag)) {
                    Reconciliation.MERGED -> pushed.add(entry.path)
                    Reconciliation.CONFLICTED -> conflicted.add(entry.path)
                    Reconciliation.FAILED -> failed.add(entry.path)
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
                    meta?.etag == null -> record(pushNew(path), path)
                    // Deleted on the server but edited here: keep it and put it back.
                    meta.dirty -> record(pushNew(path), path)
                    else -> {
                        store.removeLocally(path)
                        deletedRemotely.add(path)
                    }
                }
            }
            // Deleted on both sides: nothing left to tell the server.
            for (path in tombstones.keys - remote.keys) store.forgetTombstone(path)
        }

        for (path in manifest.deleted) {
            val meta = store.meta(path) ?: continue
            if (meta.dirty) {
                record(pushNew(path), path)
            } else {
                store.removeLocally(path)
                deletedRemotely.add(path)
            }
        }

        // A delta lists only what moved on the server, so a file edited or created here alone is
        // absent from it. Absence means "unchanged remotely": push it against the etag we hold.
        if (!manifest.complete) {
            val covered = remote.keys + manifest.deleted
            val unsynced = store.dirtyPaths().toSet() +
                localPaths.filter { store.meta(it)?.etag == null }
            for (path in unsynced - covered) {
                val etag = store.meta(path)?.etag
                record(if (etag == null) pushNew(path) else pushLocal(path, etag), path)
            }
            // Absent from a delta means unchanged on the server, so still the version deleted here.
            pendingDeletes += tombstones.values.filter { it.path !in covered }
        }

        for (tombstone in pendingDeletes) {
            val etag = tombstone.etag ?: continue
            when (api.delete(tombstone.path, etag)) {
                DeleteResult.Deleted -> {
                    store.forgetTombstone(tombstone.path)
                    deletedLocally.add(tombstone.path)
                }
                // Edited elsewhere in the meantime: as above, the edit wins.
                is DeleteResult.Conflict -> {
                    store.forgetTombstone(tombstone.path)
                    if (pull(tombstone.path)) pulled.add(tombstone.path)
                }
                DeleteResult.Gone -> store.forgetTombstone(tombstone.path)
                is DeleteResult.Failed -> failed.add(tombstone.path)
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
        // A local deletion is local work too: the next full cycle decides, not a hint.
        if (store.meta(path)?.let { it.dirty || it.deleted } == true) return false
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

    private enum class Reconciliation { MERGED, CONFLICTED, FAILED }

    /**
     * Merges a file both sides changed.
     *
     * A clean merge is written locally and pushed with the *remote's* etag, so the server accepts
     * it. A real conflict keeps the local version live and saves the server's beside it as
     * `name.sync-conflict-<timestamp>.org` — deliberately not conflict markers inside the note, and
     * deliberately never a choice between the two. Neither version is ever discarded.
     */
    private suspend fun reconcile(path: String, remoteEtag: String): Reconciliation {
        val local = store.read(path) ?: return Reconciliation.FAILED
        val base = store.base(path) ?: ""
        val remote = api.fetch(path) ?: return Reconciliation.FAILED

        return when (val result = merge(base, local, remote.text)) {
            is MergeResult.Merged -> {
                store.save(path, result.text)
                when (val push = api.push(path, result.text, ifMatch = remote.etag)) {
                    is PushResult.Pushed -> {
                        store.markPushed(path, push.etag, result.text)
                        Reconciliation.MERGED
                    }
                    // It moved again while we were merging; the next cycle will pick it up.
                    else -> Reconciliation.FAILED
                }
            }

            is MergeResult.Conflicted -> {
                store.writeConflictCopy(path, remote.text, timestamp())
                // The base becomes the server's version, so the next cycle sees a plain local edit
                // rather than reporting the same conflict forever.
                store.markConflicted(path, remoteEtag)
                Reconciliation.CONFLICTED
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

    /**
     * Pushes a file the server does not have, or no longer has.
     *
     * Only as a creation (`If-None-Match: *`): a note made here must never replace one someone
     * made at the same path elsewhere. When that has happened, theirs takes the path and ours is
     * kept beside it as a conflict copy, which is pushed like any other new note.
     */
    private suspend fun pushNew(path: String): PushOutcome {
        val content = store.read(path) ?: return PushOutcome.SKIPPED
        return when (val result = api.push(path, content, ifMatch = null, mustNotExist = true)) {
            is PushResult.Pushed -> {
                store.markPushed(path, result.etag, content)
                PushOutcome.PUSHED
            }
            is PushResult.Conflict -> {
                val theirs = api.fetch(path) ?: return PushOutcome.FAILED
                val same = theirs.text == content
                if (!same) store.writeConflictCopy(path, content, timestamp())
                store.acceptFromServer(path, theirs.text, theirs.etag)
                if (same) PushOutcome.SKIPPED else PushOutcome.CONFLICT
            }
            is PushResult.Failed -> PushOutcome.FAILED
        }
    }
}
