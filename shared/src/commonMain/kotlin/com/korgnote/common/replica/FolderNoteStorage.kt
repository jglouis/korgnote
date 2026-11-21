package com.korgnote.common.replica

import com.korgnote.common.index.NoteBudgets
import com.korgnote.common.index.SizedCache
import com.korgnote.common.sync.conflictCopyName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Stores replica notes as files in a [NoteFolder].
 *
 * Provides a [ReplicaStorage] implementation backed by a folder, with serialized access,
 * cached note text, cached file stamps, and deferred writes when the folder cannot be reached.
 * Failed writes are kept in memory and retried on later access or during [rescan].
 *
 * Detects external changes before overwriting a note. When a note has changed outside this
 * storage since it was last read or written, the new content is written to a conflict copy
 * instead of replacing the external change. Conflict paths can be retrieved with
 * [takeConflicts].
 *
 * The storage keeps track of the last folder listing and exposes reachability failures through
 * [problem]. A null [problem] means the most recent folder operation succeeded.
 *
 * @param folder folder used to list, read, write, delete, and stamp note files.
 * @param textBudget maximum cached text size, in bytes, used for recently read or written notes.
 * @param timestamp supplies the timestamp segment used when naming conflict copies.
 */
class FolderNoteStorage(
    private val folder: NoteFolder,
    textBudget: Long = NoteBudgets.forThisDevice().folderTexts,
    private val timestamp: () -> String,
) : ReplicaStorage {

    private val mutex = Mutex()

    /**
     * Last successfully known file stamps for the folder, keyed by note path.
     *
     * A `null` value means the folder has not yet been listed successfully. Once populated, this cache is
     * used when the folder cannot be reached and is updated after successful rescans, writes, and deletes.
     */
    private var known: Map<String, FileStamp>? = null

    /**
     * Cache of recently read note text together with the file stamp it was read from.
     *
     * Entries are sized by an estimated memory cost of the text content plus fixed overhead, allowing the
     * cache to evict older text when the configured text budget is exceeded.
     */
    private val texts = SizedCache<Pair<FileStamp, String>>(textBudget) { (_, text) -> text.length * 2L + TEXT_OVERHEAD }

    /**
     * Remembers files already observed by this storage, keyed by note path.
     *
     * The stored pair contains the last known folder stamp and the fingerprint of the text associated
     * with that observation, allowing later writes to detect outside changes and avoid overwriting them.
     */
    private val seen = mutableMapOf<String, Pair<FileStamp, Long>>()

    /**
     * Pending note writes that have not yet been persisted to the folder.
     *
     * Keys are note paths and values are the text content to write. Entries remain here until they are
     * successfully flushed, allowing reads and stamp queries to reflect local changes even when the
     * folder is temporarily unreachable.
     */
    private val unsaved = mutableMapOf<String, String>()

    /**
     * Paths of conflict copies created while writing notes.
     *
     * Entries are kept in insertion order until they are returned and cleared by `takeConflicts`.
     */
    private val conflicts = mutableListOf<String>()

    /**
     * Most recent folder reachability or I/O problem message.
     *
     * `null` indicates that the last checked folder operation completed without a recorded problem.
     */
    var problem: String? = null
        private set

    /**
     * Flushes pending unsaved writes, lists the folder again, and updates the remembered file stamps.
     *
     * Cached text and seen state for files no longer present in the folder are discarded.
     *
     * @return `true` when the folder was reached and the known file set or stamps changed, `false` when
     * the folder could not be reached, pending writes could not be flushed, or the rescan found no
     * changes.
     */
    suspend fun rescan(): Boolean = mutex.withLock {
        val before = known
        if (!flushUnsaved()) return@withLock false
        val now = reach { folder.list().associate { it.path to it.stamp } } ?: return@withLock false
        known = now
        texts.retain(now.keys)
        seen.keys.retainAll(now.keys)
        before != now
    }

    /**
     * Returns the paths of conflict copies recorded since the previous call, then clears the recorded
     * conflicts.
     *
     * @return the recorded conflict paths, in insertion order, or an empty list when no conflicts have
     * been recorded
     */
    suspend fun takeConflicts(): List<String> = mutex.withLock {
        conflicts.toList().also { conflicts.clear() }
    }

    override suspend fun list(): List<String> = mutex.withLock {
        (entries().keys + unsaved.keys).sorted()
    }

    override suspend fun read(path: String): String? = mutex.withLock {
        unsaved[path]?.let { return@withLock it }
        val stamp = entries()[path] ?: return@withLock null
        texts[path]?.takeIf { it.first == stamp }?.let { return@withLock it.second }
        val text = reach { folder.read(path) } ?: return@withLock texts[path]?.second
        remember(path, stamp, text)
        text
    }

    private fun remember(path: String, stamp: FileStamp, text: String) {
        texts[path] = stamp to text
        seen[path] = stamp to contentHash(text)
    }

    /**
     * Returns the file stamps currently known for the folder, with pending unsaved writes reflected in
     * the result.
     *
     * Pending writes take precedence over listed folder entries and are stamped from their content hash
     * and text length. If the folder has never been listed successfully and cannot be reached now, the
     * contents are unknown.
     *
     * @return the known file stamps by path, including pending unsaved writes, or `null` when the folder
     * could not be listed and no previous listing is known
     */
    override suspend fun stamps(): Map<String, FileStamp>? = mutex.withLock {
        val listed = entries()
        // Never listed, and it failed: nobody knows what is there, which is not the same as nothing,
        // nor as only the saves waiting here.
        if (known == null) return@withLock null
        listed + unsaved.mapValues { (_, text) -> FileStamp(contentHash(text), text.length.toLong()) }
    }

    /**
     * Reads the text for the requested note paths, using pending unsaved content and valid cached text
     * before reading from the folder.
     *
     * Files that are not known to exist, cannot be read, or are unavailable because the folder cannot be
     * reached are omitted from the result.
     *
     * @param paths the note paths to read
     * @return a map from each successfully read path to its text
     */
    override suspend fun readSeveral(paths: List<String>): Map<String, String> = mutex.withLock {
        val stamps = entries()
        val found = mutableMapOf<String, String>()
        val toRead = mutableListOf<String>()
        for (path in paths) {
            val kept = unsaved[path] ?: texts[path]?.takeIf { it.first == stamps[path] }?.second
            when {
                kept != null -> found[path] = kept
                path in stamps -> toRead += path
            }
        }
        val limit = Semaphore(PARALLEL_READS)
        coroutineScope {
            toRead.map { path -> async { limit.withPermit { path to reach { folder.read(path) } } } }.awaitAll()
        }.forEach { (path, text) ->
            if (text != null) {
                remember(path, stamps.getValue(path), text)
                found[path] = text
            }
        }
        found
    }

    override suspend fun write(path: String, content: String) {
        requireSafeReplicaPath(path)
        mutex.withLock {
            if (!flushUnsaved() || !writeLocked(path, content)) unsaved[path] = content
        }
    }

    override suspend fun delete(path: String) {
        mutex.withLock {
            unsaved.remove(path)
            texts.remove(path)
            seen.remove(path)
            known = known?.minus(path)
            reach { folder.delete(path) }
        }
    }

    // Nothing to sync with, so nothing to keep for it.
    override suspend fun readBase(path: String): String? = null
    override suspend fun writeBase(path: String, content: String) {}
    override suspend fun deleteBase(path: String) {}
    override suspend fun readMeta(): ReplicaMeta = ReplicaMeta()
    override suspend fun writeMeta(meta: ReplicaMeta) {}

    /**
     * Returns the file stamps currently listed for the folder, using the remembered listing when
     * available.
     *
     * When no listing is remembered, the folder is listed and the result is cached. If the folder cannot
     * be reached before any listing has been cached, an empty map is returned.
     *
     * @return the remembered or newly listed file stamps by path, or an empty map when the folder cannot
     * be reached and no remembered listing exists
     */
    private suspend fun entries(): Map<String, FileStamp> {
        known?.let { return it }
        val listed = reach { folder.list().associate { it.path to it.stamp } } ?: return emptyMap()
        known = listed
        return listed
    }

    /**
     * Writes [content] while the caller holds the storage lock.
     *
     * If [path] has been changed outside this storage since it was last read or remembered, the new
     * content is written to a conflict copy instead of overwriting the external change. The original path
     * is then remembered with its externally changed content so a later write can resolve it.
     *
     * @param path the note path to write
     * @param content the text content to write
     * @return `true` when the content was written and local state was updated, or `false` when the folder
     * could not be reached, the current file state could not be verified, a conflict copy name could not
     * be found, or the write failed
     */
    private suspend fun writeLocked(path: String, content: String): Boolean {
        val current = reach { Present(folder.stamp(path)) } ?: return false
        val outside = current.stamp?.let { changedOutside(path, it) ?: return false }?.text
        val target = if (outside != null) freeCopyName(path) ?: return false else path
        val stamp = reach { folder.write(target, content) } ?: return false
        known = (known ?: emptyMap()) + (target to stamp)
        remember(target, stamp, content)
        if (outside != null) {
            conflicts += path
            // The note now holds what was written elsewhere: that is what to show, and to save over next.
            // Only now: with the save still to be written, the next try has to find the same change.
            known = (known ?: emptyMap()) + (path to current.stamp)
            remember(path, current.stamp, outside)
        }
        return true
    }

    /**
     * Checks whether the file at [path] differs from the last text read or from the remembered folder
     * stamp.
     *
     * A non-null result means the check completed. Its text is `null` when no outside content change was
     * found, or the current folder text when an outside change was found. A `null` return means the file
     * content could not be read or the folder could not be reached, so the outside-change state could not
     * be determined.
     *
     * @param path the note path to check
     * @param current the file stamp currently listed for the path
     * @return the detected outside change information, or `null` when it cannot be determined
     */
    private suspend fun changedOutside(path: String, current: FileStamp): Outside? {
        val read = seen[path]
        if (read != null && read.first == current) return Outside(null)
        if (read == null && known?.get(path) == current) return Outside(null)
        val now = reach { folder.read(path) } ?: return null
        return Outside(now.takeIf { contentHash(it) != read?.second })
    }

    /**
     * Finds an unused conflict copy path for [path].
     *
     * Candidate names are built from the current timestamp. If the first candidate is already present,
     * numeric suffixes are appended to the timestamp until a path with no current file stamp is found.
     *
     * @param path the original note path that needs a conflict copy name
     * @return an available conflict copy path, or `null` when the folder cannot be reached while checking
     * candidate names
     */
    private suspend fun freeCopyName(path: String): String? {
        val stamp = timestamp()
        var name = conflictCopyName(path, stamp)
        var counter = 2
        while (true) {
            val taken = reach { Present(folder.stamp(name)) } ?: return null
            if (taken.stamp == null) return name
            name = conflictCopyName(path, "$stamp-${counter++}")
        }
    }

    /**
     * Writes all pending local changes to the folder and clears each change after it is successfully
     * persisted.
     *
     * Processing stops at the first write failure, leaving that change and any remaining changes pending
     * for a later attempt.
     *
     * @return `true` when all pending changes were written successfully, or `false` when any write failed
     */
    private suspend fun flushUnsaved(): Boolean {
        for ((path, content) in unsaved.toList()) {
            if (!writeLocked(path, content)) return false
            unsaved.remove(path)
        }
        return true
    }

    /**
     * Runs [block] and records whether the folder was reachable.
     *
     * On success, clears the current problem message and returns the block result. Cancellation is rethrown.
     * Other exceptions are converted to a problem message and reported as an unreachable folder.
     *
     * @param block the suspending operation to perform while checking reachability
     * @return the result of [block], or `null` when the operation fails with a non-cancellation exception
     */
    private suspend fun <T> reach(block: suspend () -> T): T? = try {
        block().also { problem = null }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        problem = e.message ?: e::class.simpleName ?: "The folder cannot be reached."
        null
    }

    /**
     * Records that a note path is known to be present in the folder state tracked by the storage.
     *
     * The stamp is the file state observed for the path when it was listed or remembered. A null stamp
     * represents a present entry whose concrete folder stamp is not currently available.
     */
    private class Present(val stamp: FileStamp?)

    /**
     * Describes the completed result of checking whether a note was changed outside this storage.
     *
     * A non-null text value represents the current external content when an outside change was detected.
     * A null text value means the check completed and no outside content change was found.
     */
    private class Outside(val text: String?)

    private companion object {
        /** How many notes are read at once: enough to hide each call's wait, not so many as to queue. */
        const val PARALLEL_READS = 8

        /** What keeping a text costs on top of its characters, so that many tiny ones still add up. */
        const val TEXT_OVERHEAD = 128L
    }
}
