package com.korgnote.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Watches the notes directory so an edit made in Emacs — or delivered by Syncthing — becomes a
 * journal entry, and therefore an SSE event, within a second or two.
 *
 * Without this the server only knows about changes it made itself, and a phone that syncs with
 * `?since=` is told nothing changed: it reports a good sync and never sees the edit. So the watcher
 * must not stop, and must not lose what it misses:
 *
 * - It keeps what it last saw of every note, path and etag. A plain file event is checked against
 *   it; anything that moves folders — a folder made, deleted, renamed or moved in, an overflow of
 *   events, a key that went invalid, an error — makes it build its watches again from scratch and
 *   walk the tree, journaling every difference. A renamed folder is then its old notes deleted and
 *   its new ones made, and a folder moved in with notes in it brings them.
 * - It rebuilds rather than mends: a watch follows its folder's inode, so after `mv sub sub2` the
 *   old one keeps reporting `sub/…` paths, and registering `sub2` hands back that same stale key.
 * - It walks the tree every [rescanEvery] as well, because a watch can miss events and say nothing:
 *   Docker Desktop's file sharing drops some made on the host, a note written straight into a folder
 *   just made among them.
 *
 * Before this, deleting or renaming any folder ended the loop, and the server saw no outside edit
 * again until it restarted, while every phone kept reporting good syncs.
 */
class NotesWatcher(
    private val root: Path,
    private val store: FileStore,
    private val journal: ChangeJournal,
    private val rescanEvery: Duration = 1.minutes,
    private val log: Logger = LoggerFactory.getLogger(NotesWatcher::class.java),
) {
    private var job: Job? = null

    /** What the journal last heard of each note: path to etag. Only the watcher's coroutine uses it. */
    private val known = HashMap<String, String>()

    /** The folders being watched, so a deleted one is recognised once it can no longer be looked at. */
    private val watchedDirectories = HashSet<Path>()

    fun start(scope: CoroutineScope) {
        if (!Files.isDirectory(root)) return
        job = scope.launch(Dispatchers.IO) { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run() {
        // The first walk is the baseline: a client's first sync takes the full manifest anyway.
        rescan(journalChanges = false)
        var failures = 0
        while (currentCoroutineContext().isActive) {
            try {
                watchUntilTheFoldersMove()
                failures = 0
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures++
                log.warn("Watching ${root} failed; watching it again and looking for what was missed", error)
                // A root that has gone away fails every time; don't spin on it.
                delay(minOf(failures, 30).seconds)
            }
            // Whatever ended that round, changes may have gone unseen during it.
            rescan(journalChanges = true)
        }
    }

    /**
     * Watches with a fresh service until the folder structure changes, then returns so the caller
     * rescans and watches again. Plain edits are journaled here as they come.
     */
    private suspend fun watchUntilTheFoldersMove() {
        FileSystems.getDefault().newWatchService().use { watcher ->
            watchedDirectories.clear()
            registerRecursively(root, watcher)
            var lastScan = TimeSource.Monotonic.markNow()
            while (currentCoroutineContext().isActive) {
                // Poll rather than block, so cancelling the server does not leave a thread parked.
                val first = watcher.poll(500, TimeUnit.MILLISECONDS)
                if (first == null) {
                    if (lastScan.elapsedNow() >= rescanEvery) {
                        rescan(journalChanges = true)
                        lastScan = TimeSource.Monotonic.markNow()
                    }
                    continue
                }
                // Coalesce the burst an editor produces when saving one file, then take every key
                // that is ready, so a checkout touching many folders is one rebuild and not dozens.
                delay(100.milliseconds)
                val changed = LinkedHashSet<Path>()
                var foldersMoved = false
                var key: WatchKey? = first
                while (key != null) {
                    if (collect(key, changed)) foldersMoved = true
                    // An invalid key is a folder gone or moved; the rebuild deals with it.
                    if (!key.reset()) foldersMoved = true
                    key = watcher.poll()
                }
                if (foldersMoved) return
                for (file in changed) recordIfChanged(file)
            }
        }
    }

    /** Adds the notes [key] reports to [changed]; true when its events moved folders instead. */
    private fun collect(key: WatchKey, changed: MutableSet<Path>): Boolean {
        val directory = key.watchable() as? Path
        var foldersMoved = false
        for (event in key.pollEvents()) {
            if (event.kind() == OVERFLOW) {
                foldersMoved = true
                continue
            }
            val relative = event.context() as? Path ?: continue
            val path = directory?.resolve(relative) ?: continue
            if (excluded(path)) continue
            when {
                event.kind() == ENTRY_CREATE && path.isDirectory() -> foldersMoved = true
                event.kind() == ENTRY_DELETE && path in watchedDirectories -> foldersMoved = true
                path.extension == "org" -> changed.add(path)
            }
        }
        return foldersMoved
    }

    /** Journals [file] if what is on disk now is not what the journal last heard of it. */
    private suspend fun recordIfChanged(file: Path) {
        val path = relativePathOf(file)
        // Editors write through temporary files, so a single save can arrive as several events;
        // the etag is read fresh here, and a repeat of what is already known is dropped.
        val etag = store.meta(path)?.etag
        if (etag == known[path]) return
        if (etag == null) known.remove(path) else known[path] = etag
        journal.record(path, etag, deleted = etag == null)
    }

    /**
     * Walks the whole tree and, when [journalChanges], journals every note made, changed or deleted
     * since [known] was last right. Deletions come last, so a renamed folder's notes exist anew
     * before any client is told the old ones are gone.
     */
    private suspend fun rescan(journalChanges: Boolean) {
        // A notes folder that is not there — an unmounted drive, a volume not attached yet — lists as
        // empty, which is not every note deleted. Wait for it to come back.
        if (!Files.isDirectory(root)) return
        val now = try {
            store.list().associate { it.path to it.etag }
        } catch (error: Exception) {
            // A folder vanishing mid-walk; the next rescan will see the tree as it settled.
            log.debug("Could not walk ${root}", error)
            return
        }
        if (journalChanges) {
            for ((path, etag) in now) {
                if (known[path] != etag) journal.record(path, etag)
            }
            for (path in known.keys - now.keys) journal.record(path, etag = null, deleted = true)
        }
        known.clear()
        known.putAll(now)
    }

    private fun registerRecursively(directory: Path, watcher: WatchService) {
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (excluded(dir)) return FileVisitResult.SKIP_SUBTREE
                dir.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
                watchedDirectories.add(dir)
                return FileVisitResult.CONTINUE
            }

            // A folder deleted while it is walked: the event for it brings another rebuild.
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                if (file == directory) throw exc else FileVisitResult.CONTINUE
        })
    }

    /**
     * The trash is not part of the library; `.git` holds no notes, and git makes and deletes folders
     * in it all the time, each of which would be a rebuild.
     */
    private fun excluded(path: Path): Boolean =
        root.relativize(path).any { it.toString() == TRASH_DIRECTORY || it.toString() == ".git" }

    private fun relativePathOf(file: Path): String =
        root.relativize(file).joinToString("/") { it.toString() }

    private companion object {
        const val TRASH_DIRECTORY = ".korgnote-trash"
    }
}
