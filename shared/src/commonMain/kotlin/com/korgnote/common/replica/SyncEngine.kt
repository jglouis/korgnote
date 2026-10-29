package com.korgnote.common.replica

import com.korgnote.common.sync.MergeResult
import com.korgnote.common.sync.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What one sync cycle did, mostly so tests and the UI can say something specific. */
data class SyncReport(
    val pulled: List<String> = emptyList(),
    val pushed: List<String> = emptyList(),
    /** Pushed after merging with the server's version, so the text here changed too. */
    val merged: List<String> = emptyList(),
    val deletedLocally: List<String> = emptyList(),
    val deletedRemotely: List<String> = emptyList(),
    val conflicted: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val generation: Long = 0,
) {
    val changedAnything: Boolean
        get() = pulled.isNotEmpty() || pushed.isNotEmpty() ||
            deletedLocally.isNotEmpty() || deletedRemotely.isNotEmpty()

    /**
     * True when what the replica holds is no longer what was on screen: something arrived, merged,
     * went, or was set aside as a conflict copy. A plain push leaves the text as it was.
     */
    val replicaMoved: Boolean
        get() = pulled.isNotEmpty() || merged.isNotEmpty() || deletedRemotely.isNotEmpty() ||
            conflicted.isNotEmpty()
}

/**
 * Reconciles the local replica with the server, per `docs/05-sync-protocol.md`.
 *
 * Each path is decided by comparing three versions: local (L), base (B — what the server last had)
 * and remote (R). The store tracks L against B with its dirty flag and B against R with the stored
 * etag, so the whole table reduces to two booleans.
 *
 * The rule that matters above all is that no branch ever discards an edit the user made, nor one
 * the server holds. The server is the source of truth: when two versions cannot be merged, its
 * version keeps the path and this device's is kept beside it as a conflict copy.
 */
class SyncEngine(
    private val store: DocumentStore,
    private val api: SyncApi,
    /**
     * Stamp for conflict copies, as `yyyyMMdd-HHmmss`. Injected rather than read from a clock so
     * the merge behaviour can be tested without one. The default is still that shape, since it is
     * what marks a file as a conflict copy.
     */
    private val timestamp: () -> String = { "00000000-000000" },
) {

    /** Lets one cycle run at a time; see [sync]. */
    private val cycle = Mutex()

    /**
     * One full cycle: diff the manifest, pull what is stale, push what is dirty.
     *
     * One at a time: a save, the change stream and a retry each start one, and two running together
     * both sent what was pending — a deletion three times, an edit twice, the second time against a
     * version the first had already replaced. One that waits finds that work done.
     */
    suspend fun sync(): SyncReport = cycle.withLock { syncLocked() }

    private suspend fun syncLocked(): SyncReport {
        val since = store.generation().takeIf { it > 0 }
        val manifest = api.manifest(since)

        val pulled = mutableListOf<String>()
        val pushed = mutableListOf<String>()
        val merged = mutableListOf<String>()
        val deletedLocally = mutableListOf<String>()
        val deletedRemotely = mutableListOf<String>()
        val conflicted = mutableListOf<String>()
        val failed = mutableListOf<String>()

        val remote = manifest.entries.associateBy { it.path }
        val localPaths = store.paths().toSet()
        val tombstones = store.tombstones().associateBy { it.path }
        val bundled = bundleFor(manifest, since, localPaths, tombstones.keys)

        // Takes the server's copy of [path], from the bundle when it holds it.
        suspend fun pullOne(path: String): Boolean {
            val file = bundled[path] ?: return pull(path)
            if (store.meta(path)?.let { it.dirty || it.deleted } == true) return false
            store.acceptFromServer(path, file.text, file.etag)
            return true
        }
        // Deletions wait until everything else is pushed: a rename is a new note and a tombstone,
        // and another device must never catch the old one gone before the new one exists.
        val pendingDeletes = mutableListOf<FileMeta>()

        fun record(outcome: PushOutcome, path: String) = when (outcome) {
            PushOutcome.PUSHED -> pushed.add(path)
            PushOutcome.MERGED -> { pushed.add(path); merged.add(path) }
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
                    if (pullOne(entry.path)) pulled.add(entry.path)
                }
                continue
            }
            val meta = store.meta(entry.path)
            val haveLocally = entry.path in localPaths
            val dirty = meta?.dirty == true

            when {
                // Never seen it, or the copy went missing: take the server's.
                !haveLocally || meta == null -> if (pullOne(entry.path)) pulled.add(entry.path)

                // Untouched locally and unchanged remotely: nothing to do.
                !dirty && meta.etag == entry.etag -> Unit

                // Untouched locally, moved remotely: take theirs.
                !dirty -> if (pullOne(entry.path)) pulled.add(entry.path)

                // Edited locally, server unchanged since we synced: push.
                meta.etag == entry.etag -> record(pushLocal(entry.path, meta.etag), entry.path)

                // Both moved: try to reconcile them, and only involve the user if that fails.
                else -> record(reconcile(entry.path), entry.path)
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
            merged = merged,
            deletedLocally = deletedLocally,
            deletedRemotely = deletedRemotely,
            conflicted = conflicted,
            failed = failed,
            generation = manifest.generation,
        )
    }

    /**
     * The files this cycle will pull, in one request, when there are enough of them to be worth it:
     * one at a time, the first sync of a few hundred notes took over a minute on a phone. Empty when
     * there are few, or the server cannot bundle.
     */
    private suspend fun bundleFor(
        manifest: RemoteManifest,
        since: Long?,
        localPaths: Set<String>,
        tombstones: Set<String>,
    ): Map<String, RemoteFile> {
        val wanted = manifest.entries.count { entry ->
            if (entry.path in tombstones) return@count false
            val meta = store.meta(entry.path)
            entry.path !in localPaths || meta == null || (!meta.dirty && meta.etag != entry.etag)
        }
        if (wanted < BUNDLE_FROM) return emptyMap()
        return api.bundle(since)?.associateBy { it.path }.orEmpty()
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

    /**
     * Merges a file both sides changed.
     *
     * A clean merge is written locally and pushed with the *remote's* etag, so the server accepts
     * it. A real conflict keeps the server's version at the path and saves this device's beside it
     * as `name.sync-conflict-<timestamp>.org` — deliberately not conflict markers inside the note.
     * Neither version is ever discarded; the user resolves it by keeping one.
     */
    private suspend fun reconcile(path: String): PushOutcome {
        val local = store.read(path) ?: return PushOutcome.SKIPPED
        val base = store.base(path) ?: ""
        val remote = api.fetch(path) ?: return PushOutcome.FAILED

        return when (val result = merge(base, local, remote.text)) {
            is MergeResult.Merged -> {
                // Saved here while the server was being asked: merge again next cycle, with it.
                if (!store.saveIfUnchanged(path, local, result.text)) return PushOutcome.FAILED
                when (val push = api.push(path, result.text, ifMatch = remote.etag)) {
                    is PushResult.Pushed -> {
                        store.markPushed(path, push.etag, result.text)
                        PushOutcome.MERGED
                    }
                    // It moved again while we were merging; the next cycle will pick it up.
                    else -> PushOutcome.FAILED
                }
            }

            is MergeResult.Conflicted ->
                if (store.keepServerVersion(path, remote.text, remote.etag, timestamp()) != null) {
                    PushOutcome.CONFLICT
                } else {
                    PushOutcome.SKIPPED
                }
        }
    }

    private enum class PushOutcome { PUSHED, MERGED, CONFLICT, FAILED, SKIPPED }

    private suspend fun pushLocal(path: String, ifMatch: String?): PushOutcome {
        val content = store.read(path) ?: return PushOutcome.SKIPPED
        return when (val result = api.push(path, content, ifMatch)) {
            is PushResult.Pushed -> {
                store.markPushed(path, result.etag, content)
                PushOutcome.PUSHED
            }
            // The server moved between the manifest and this push — an Emacs save landing in that
            // window. Merge exactly as if the manifest had shown it: recording the new etag and
            // pushing again next cycle would overwrite that save without anyone seeing it.
            is PushResult.Conflict -> reconcile(path)
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
                if (store.keepServerVersion(path, theirs.text, theirs.etag, timestamp()) != null) {
                    PushOutcome.CONFLICT
                } else {
                    PushOutcome.SKIPPED
                }
            }
            is PushResult.Failed -> PushOutcome.FAILED
        }
    }

    private companion object {
        /** How many files a cycle pulls before one bundle beats asking for each. */
        const val BUNDLE_FROM = 8
    }
}
