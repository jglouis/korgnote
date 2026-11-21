package com.korgnote.common.replica

import com.korgnote.common.sync.MergeResult
import com.korgnote.common.sync.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Summary of the effects of a synchronization run.
 *
 * Records the note paths that were downloaded, uploaded, merged, deleted on either side, left in
 * conflict, or failed during synchronization. The report can be used by callers to decide whether
 * visible state should be refreshed, whether follow-up conflict handling is needed, and which remote
 * generation was reached by the run.
 */
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
 * Coordinates synchronization between the local replica and the remote service.
 *
 * SyncEngine compares the remote manifest with local state, pulls stale remote files,
 * pushes dirty local files, handles deletions, and records the generation or timestamp
 * reached by a successful cycle. A full synchronization is serialized so overlapping
 * triggers do not push or delete the same pending work multiple times; a later caller
 * observes the work already completed by the cycle in progress.
 *
 * Remote change events are treated as hints. A single-path pull may update the local
 * replica when there is no competing local edit, but the next full synchronization is
 * responsible for deciding the authoritative result from the manifest and local change
 * state.
 *
 * When both sides changed the same file, the engine attempts to reconcile the versions.
 * Clean merges are written locally and pushed against the remote version they merged
 * from. True conflicts preserve both versions: the remote version keeps the original
 * path, while the local version is saved as a conflict copy using the engine timestamp.
 * New local files are pushed only as creations, so they never overwrite independently
 * created remote files at the same path.
 *
 * The engine depends on the local store for replica state, the remote API for manifests,
 * files, pushes, and event streams, a timestamp source for conflict names and bookkeeping,
 * and a cycle guard for serialized synchronization.
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
     * Runs one synchronization cycle between the local store and the remote API.
     *
     * Only one cycle is allowed to run at a time; concurrent callers wait for the current cycle to
     * finish before their own cycle begins.
     *
     * @return a report summarizing the files pulled, pushed, merged, deleted, conflicted, failed, and
     * the remote generation reached by the cycle.
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

        // What the bundle holds of what the loop below decides to pull, all written at once after it.
        val fromBundle = mutableListOf<RemoteFile>()

        // Takes the server's copy of [path], from the bundle when it holds it.
        suspend fun pullOne(path: String): PullOutcome {
            val file = bundled[path] ?: return pullForCycle(path)
            fromBundle += file
            return PullOutcome.LEFT
        }
        var fetchFailed = false

        fun record(outcome: PullOutcome, path: String) {
            when (outcome) {
                PullOutcome.TAKEN -> pulled.add(path)
                PullOutcome.FAILED -> { failed.add(path); fetchFailed = true }
                PullOutcome.LEFT -> Unit
            }
        }
        // Deletions wait until everything else is pushed: a rename is a new note and a tombstone,
        // and another device must never catch the old one gone before the new one exists.
        val pendingDeletes = mutableListOf<FileMeta>()

        fun record(outcome: PushOutcome, path: String) = when (outcome) {
            PushOutcome.PUSHED -> pushed.add(path)
            PushOutcome.MERGED -> { pushed.add(path); merged.add(path) }
            PushOutcome.CONFLICT -> conflicted.add(path)
            PushOutcome.FAILED -> failed.add(path)
            PushOutcome.NOT_FETCHED -> { failed.add(path); fetchFailed = true }
            PushOutcome.SKIPPED -> Unit
        }

        try {
            for ((path, etag) in manifest.entries) {
                val tombstone = tombstones[path]
                if (tombstone != null) {
                    if (tombstone.etag == etag) {
                        pendingDeletes.add(tombstone)
                    } else {
                        // Edited elsewhere after the version deleted here: the edit wins, and the note
                        // comes back rather than taking someone's work with it.
                        store.forgetTombstone(path)
                        record(pullOne(path), path)
                    }
                    continue
                }
                val meta = store.meta(path)
                val haveLocally = path in localPaths
                val dirty = meta?.dirty == true

                when {
                    // Never seen it, or the copy went missing: take the server's.
                    !haveLocally -> record(pullOne(path), path)

                    // Here, but with no record of what was synced — a corrupt meta.json is read as
                    // empty — so it may hold edits never pushed. Theirs takes the path, and ours is kept
                    // beside it unless the two are the same.
                    meta == null -> record(keepTheirs(path), path)

                    // Untouched locally and unchanged remotely: nothing to do.
                    !dirty && meta.etag == etag -> Unit

                    // Untouched locally, moved remotely: take theirs.
                    !dirty -> record(pullOne(path), path)

                    // Edited locally, server unchanged since we synced: push.
                    meta.etag == etag -> record(pushLocal(path, etag), path)

                    // Both moved: try to reconcile them, and only involve the user if that fails.
                    else -> record(reconcile(path), path)
                }
            }
        } finally {
            // Even when the server is lost partway, what already arrived is kept: a first sync that
            // fails near the end need not start again.
            pulled += store.acceptAllUntouched(fromBundle)
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
            val records = store.records()
            val unsynced = store.dirtyPaths().toSet() +
                localPaths.filter { records[it]?.etag == null }
            for (path in unsynced - covered) {
                val etag = store.meta(path)?.etag
                record(if (etag == null) pushNew(path) else pushLocal(path, etag), path)
            }
            // Absent from a delta means unchanged on the server, so still the version deleted here.
            pendingDeletes += tombstones.values.filter { it.path !in covered }
        }

        // A conflict copy made above is a new note, sent now: the next cycle may be a long way off,
        // and on this device alone, settling the conflict would delete the only copy there is. Once
        // the server holds it, that deletion leaves it in the server's trash.
        val records = store.records()
        for (path in store.paths() - localPaths) {
            if (records[path]?.etag == null) record(pushNew(path), path)
        }

        for ((path, etag1) in pendingDeletes) {
            val etag = etag1 ?: continue
            when (api.delete(path, etag)) {
                DeleteResult.Deleted -> {
                    store.forgetTombstone(path)
                    deletedLocally.add(path)
                }
                // Edited elsewhere in the meantime: as above, the edit wins.
                is DeleteResult.Conflict -> {
                    store.forgetTombstone(path)
                    record(pullForCycle(path), path)
                }
                DeleteResult.Gone -> store.forgetTombstone(path)
                is DeleteResult.Failed -> failed.add(path)
            }
        }

        // A file that could not be fetched keeps the generation where it was, or the next delta would
        // not list it and it would stay stale until a complete manifest. Nothing else waits for it:
        // every other file went through above, and does in the cycles after, so one the server never
        // serves costs longer deltas, at worst a complete manifest each time, and nothing more.
        if (!fetchFailed) store.setGeneration(manifest.generation)
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
     * Builds a bundled set of remote files when the manifest indicates enough files are worth fetching in
     * bulk.
     *
     * Entries that have pending local deletions are ignored. Entries are considered wanted when they are
     * absent locally, have no stored metadata, or are clean locally and have a different remote ETag. Dirty
     * local files are not included in the decision because local changes take precedence.
     *
     * @param manifest remote manifest whose entries are evaluated for bundled retrieval.
     * @param since optional synchronization point passed to the remote bundle request.
     * @param localPaths paths currently present in the local replica.
     * @param tombstones paths deleted locally but not yet confirmed remotely.
     * @return remote files keyed by path when the wanted-entry count reaches the bundle threshold, or an empty map otherwise.
     */
    private suspend fun bundleFor(
        manifest: RemoteManifest,
        since: Long?,
        localPaths: Set<String>,
        tombstones: Set<String>,
    ): Map<String, RemoteFile> {
        val records = store.records()
        val wanted = manifest.entries.count { entry ->
            if (entry.path in tombstones) return@count false
            val meta = records[entry.path]
            entry.path !in localPaths || meta == null || (!meta.dirty && meta.etag != entry.etag)
        }
        if (wanted < BUNDLE_FROM) return emptyMap()
        return api.bundle(since)?.associateBy { it.path }.orEmpty()
    }

    /**
     * Fetches the remote file at the specified path and stores it in the local replica only if the
     * local file has not changed since its metadata was read.
     *
     * Local dirty files, locally deleted files, files whose ETag changed during the fetch, missing
     * remote files, and failed fetches are left unchanged.
     *
     * @param path the replica path of the file to pull from the remote API.
     * @return true if the remote content was fetched and accepted into the local store; false otherwise.
     */
    suspend fun pull(path: String): Boolean {
        // A cycle can run while this is fetched, a save's most of all, and leave something newer.
        val knownEtag = store.meta(path)?.etag
        val outcome = fetchAndAccept(path) { store.acceptIfUntouched(path, it.text, it.etag, knownEtag) }
        return outcome == PullOutcome.TAKEN
    }

    /**
     * Fetches a remote file for the current synchronization cycle and accepts it into the local store
     * only when the corresponding local file is still untouched.
     *
     * @param path the replica path of the file to fetch and accept.
     * @return the pull outcome indicating whether the remote file was taken, left unchanged, or failed to fetch.
     */
    private suspend fun pullForCycle(path: String): PullOutcome =
        fetchAndAccept(path) { store.acceptAllUntouched(listOf(it)).isNotEmpty() }

    private suspend fun fetchAndAccept(path: String, accept: suspend (RemoteFile) -> Boolean): PullOutcome {
        // A local deletion is local work too: the next full cycle decides, not a hint.
        if (store.meta(path)?.let { it.dirty || it.deleted } == true) return PullOutcome.LEFT
        return when (val answer = api.fetch(path)) {
            is FetchResult.Fetched -> if (accept(answer.file)) PullOutcome.TAKEN else PullOutcome.LEFT
            // Deleted on the server since it was listed, and the next manifest will say so.
            FetchResult.Gone -> PullOutcome.LEFT
            is FetchResult.Failed -> PullOutcome.FAILED
        }
    }

    /**
     * Watches remote change events and applies accepted changes to the local replica.
     *
     * Remote deletions remove the local file only when the local metadata is absent or not dirty.
     * Remote creations or updates are pulled and stored locally when they can be accepted without
     * overwriting local changes. The callback is invoked only after a remote change has been applied
     * locally.
     *
     * @param onApplied callback invoked with the path of each remote change successfully applied to the local store.
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
     * Reconciles divergent local and remote content at the specified path by performing a three-way merge
     * against the stored base version.
     *
     * If the file is absent locally, no action is taken. If no base version is available, the remote version
     * is kept and any differing local content is handled as a conflict. When the remote file can be fetched,
     * the local, base, and remote contents are merged. A clean merge is saved only if the local content has
     * not changed during reconciliation, then pushed back to the remote only if the remote ETag still matches.
     * Merge conflicts keep the server version at the original path and preserve the local changes as a
     * conflict.
     *
     * @param path the replica path of the file to reconcile.
     * @return the push outcome indicating whether the file was merged, conflicted, skipped, not fetched, or failed.
     */
    private suspend fun reconcile(path: String): PushOutcome {
        val local = store.read(path) ?: return PushOutcome.SKIPPED
        val base = store.base(path) ?: return keepTheirs(path)
        val answer = api.fetch(path)
        val remote = (answer as? FetchResult.Fetched)?.file ?: return outcomeOfNoFile(answer)

        return when (val result = merge(base, local, remote.text)) {
            is MergeResult.Merged -> {
                // Saved here while the server was being asked: merge again next cycle, with it.
                if (!store.saveIfUnchanged(path, local, result.text)) return PushOutcome.FAILED
                when (val push = api.push(path, result.text, PushCondition.Matches(remote.etag))) {
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

    /**
     * Converts a fetch result from a no-file reconciliation path into the corresponding push outcome.
     *
     * A failed fetch is reported as [PushOutcome.NOT_FETCHED] because the remote content could not be
     * inspected. Any non-failed result is treated as [PushOutcome.FAILED].
     *
     * @param answer the result returned by the attempted remote fetch.
     * @return the push outcome to report for the no-file case.
     */
    private fun outcomeOfNoFile(answer: FetchResult) =
        if (answer is FetchResult.Failed) PushOutcome.NOT_FETCHED else PushOutcome.FAILED

    private enum class PushOutcome { PUSHED, MERGED, CONFLICT, FAILED, NOT_FETCHED, SKIPPED }

    private enum class PullOutcome { TAKEN, LEFT, FAILED }

    private suspend fun pushLocal(path: String, ifMatch: String): PushOutcome {
        val content = store.read(path) ?: return PushOutcome.SKIPPED
        return when (val result = api.push(path, content, PushCondition.Matches(ifMatch))) {
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
     * Pushes a locally present file as a new remote item, requiring the remote path to be absent.
     *
     * If no local content exists at the path, the push is skipped. When the remote create succeeds,
     * the local store is marked with the returned ETag and pushed content. If the remote item already
     * exists, the remote version is kept and any differing local content is handled as a conflict.
     *
     * @param path the replica path of the local file to create remotely.
     * @return the push outcome indicating whether the file was pushed, skipped, conflicted, or failed.
     */
    private suspend fun pushNew(path: String): PushOutcome {
        val content = store.read(path) ?: return PushOutcome.SKIPPED
        return when (val result = api.push(path, content, PushCondition.Absent)) {
            is PushResult.Pushed -> {
                store.markPushed(path, result.etag, content)
                PushOutcome.PUSHED
            }
            is PushResult.Conflict -> keepTheirs(path)
            is PushResult.Failed -> PushOutcome.FAILED
        }
    }

    /**
     * Keeps the remote version of the file at the specified path in the local store.
     *
     * The remote file is fetched and saved as the server version. If saving the server version produces a
     * preserved local conflict, the operation reports a conflict. If the server version is kept without a
     * local conflict, the operation is skipped because no local content is pushed.
     *
     * @param path the replica path whose remote version should be kept locally.
     * @return the push outcome indicating whether keeping the remote version caused a conflict, was skipped, failed, or could not fetch the remote file.
     */
    private suspend fun keepTheirs(path: String): PushOutcome {
        val answer = api.fetch(path)
        val theirs = (answer as? FetchResult.Fetched)?.file ?: return outcomeOfNoFile(answer)
        return if (store.keepServerVersion(path, theirs.text, theirs.etag, timestamp()) != null) {
            PushOutcome.CONFLICT
        } else {
            PushOutcome.SKIPPED
        }
    }

    private companion object {
        /** How many files a cycle pulls before one bundle beats asking for each. */
        const val BUNDLE_FROM = 8
    }
}
