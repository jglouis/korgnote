package com.korgnote.common.replica

import com.korgnote.common.sync.conflictCopyName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The notes as files in a folder the user chose, with no server: what other apps sync — Syncthing,
 * Dropbox, Nextcloud — or nothing at all.
 *
 * There is no base or metadata to keep, since there is nothing to sync with: they read as empty and
 * writing them does nothing, so nothing of KorgNote's ever lands in the user's folder. The folder
 * itself is the only copy, which the rest of the app does not have to know: it reads and writes
 * through [DocumentStore] as it does the replica.
 *
 * The folder is slow to reach, so what it holds is listed once and each text read once, then kept
 * by its [FileStamp]; [rescan] lists again, and a file whose stamp moved is read again when asked
 * for. Others write to the folder too, and nothing says when: a save over a file that changed since
 * it was read goes to a conflict copy beside it, as Syncthing would make, rather than over that
 * change. The app already shows such copies, and settles them.
 *
 * The folder can go away — its access taken back, the card it is on taken out — and this never
 * throws for it: listing and reading fall back on what was last seen, and a save that cannot be
 * written is kept here and written by the next [rescan] that reaches the folder. [problem] says
 * what went wrong meanwhile.
 */
class FolderNoteStorage(
    private val folder: NoteFolder,
    /** The stamp a conflict copy is named with, as in [conflictCopyName]. */
    private val timestamp: () -> String,
) : ReplicaStorage {

    private val mutex = Mutex()

    /** Each file's stamp, as last listed or written; null until the folder is first listed. */
    private var known: Map<String, FileStamp>? = null

    /** Texts read or written, with the stamp the file had then. */
    private val texts = mutableMapOf<String, Pair<FileStamp, String>>()

    /** Saves the folder refused, by path, to write when it can be reached again. */
    private val unsaved = mutableMapOf<String, String>()

    /** Notes whose save went to a conflict copy since [takeConflicts] last asked. */
    private val conflicts = mutableListOf<String>()

    /** Why the folder could not be reached on the last attempt, or null when it could. */
    var problem: String? = null
        private set

    /**
     * Lists the folder again, and writes any save it refused before. True when what it holds
     * changed since it was last listed: an edit made by another app, or a note added or removed.
     */
    suspend fun rescan(): Boolean = mutex.withLock {
        val before = known
        if (!flushUnsaved()) return@withLock false
        val now = reach { folder.list().associate { it.path to it.stamp } } ?: return@withLock false
        known = now
        texts.keys.retainAll(now.keys)
        before != now
    }

    /** The notes whose save went to a conflict copy since this was last asked, each once. */
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
        texts[path] = stamp to text
        text
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

    /** What the folder was last seen to hold, listing it the first time. */
    private suspend fun entries(): Map<String, FileStamp> {
        known?.let { return it }
        val listed = reach { folder.list().associate { it.path to it.stamp } } ?: return emptyMap()
        known = listed
        return listed
    }

    /**
     * Writes [content] to [path], or to a conflict copy beside it when [path] changed since it was
     * read. False when the folder could not be reached.
     */
    private suspend fun writeLocked(path: String, content: String): Boolean {
        val current = reach { Present(folder.stamp(path)) } ?: return false
        val target = if (changedOutside(path, current.stamp)) {
            conflicts += path
            freeCopyName(path) ?: return false
        } else {
            path
        }
        val stamp = reach { folder.write(target, content) } ?: return false
        known = (known ?: emptyMap()) + (target to stamp)
        texts[target] = stamp to content
        return true
    }

    /**
     * Whether [path], which now has [current], holds something other than what was read from it. A
     * stamp alone is not enough: a sync app may write a file back unchanged, which moves its stamp.
     */
    private suspend fun changedOutside(path: String, current: FileStamp?): Boolean {
        if (current == null) return false
        val read = texts[path]
        if (read != null && read.first == current) return false
        if (read == null && known?.get(path) == current) return false
        val now = reach { folder.read(path) } ?: return false
        return read?.second != now
    }

    /** A conflict copy name for [path] that the folder has no file under; null when it cannot say. */
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

    /** Writes what the folder refused before, oldest first. False when it still cannot be reached. */
    private suspend fun flushUnsaved(): Boolean {
        for ((path, content) in unsaved.toList()) {
            if (!writeLocked(path, content)) return false
            unsaved.remove(path)
        }
        return true
    }

    /** [block]'s result, or null — with [problem] saying why — when the folder could not be reached. */
    private suspend fun <T> reach(block: suspend () -> T): T? = try {
        block().also { problem = null }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        problem = e.message ?: e::class.simpleName ?: "The folder cannot be reached."
        null
    }

    /** A value that may itself be null, told apart from [reach] failing. */
    private class Present(val stamp: FileStamp?)
}
