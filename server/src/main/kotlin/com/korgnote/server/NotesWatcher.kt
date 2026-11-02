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
 * How the watcher is doing, for the Devices page: a phone told "nothing changed" by a server that
 * stopped looking reports a good sync, so the only place to notice is here.
 *
 * Times are epoch milliseconds.
 */
data class WatcherStatus(
    val state: State,
    /** When [state] began. */
    val since: Long,
    val folders: Int = 0,
    val notes: Int = 0,
    /** The last change to a note the watcher journaled, by an event or a walk. */
    val lastChangeAt: Long? = null,
    /** The last walk of the whole tree that succeeded. */
    val lastCheckAt: Long? = null,
    /** How often the tree is walked; a [lastCheckAt] well past it means the watcher is stuck. */
    val checkEvery: Duration,
    /** Times the folders moved and the watches were built again. */
    val rebuilds: Int = 0,
    /** Changes that no event reported and only the periodic walk found. Should stay at zero. */
    val missed: Int = 0,
    val lastMissedAt: Long? = null,
    val lastMissedPaths: List<String> = emptyList(),
    val lastFailure: String? = null,
    val lastFailureAt: Long? = null,
) {
    enum class State { STARTING, WATCHING, FAILING, NOT_RUNNING }
}

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
 * What that walk finds is never healed quietly: it is logged as a warning and counted in [status],
 * so a watch that misses events is seen to, rather than hidden behind a one-minute delay.
 *
 * Before this, deleting or renaming any folder ended the loop, and the server saw no outside edit
 * again until it restarted, while every phone kept reporting good syncs.
 */
class NotesWatcher(
    private val root: Path,
    private val store: FileStore,
    private val journal: ChangeJournal,
    private val rescanEvery: Duration = 1.minutes,
    /**
     * How long an event may trail the change it reports. On macOS the JDK polls for changes every
     * few seconds, so the periodic check often finds an edit before its event does; that edit is
     * late, not missed.
     */
    private val lateEventGrace: Duration = 10.seconds,
    private val log: Logger = LoggerFactory.getLogger(NotesWatcher::class.java),
) {
    private var job: Job? = null

    /** What the journal last heard of each note: path to etag. Only the watcher's coroutine uses it. */
    private val known = HashMap<String, String>()

    /** The folders being watched, so a deleted one is recognised once it can no longer be looked at. */
    private val watchedDirectories = HashSet<Path>()

    /**
     * Changes the periodic check found before any event, with when: journaled at once, and counted
     * as missed only if no event for them follows within [lateEventGrace].
     */
    private val suspects = LinkedHashMap<String, TimeSource.Monotonic.ValueTimeMark>()

    /** Written by the watcher's coroutine only, read by the Devices page. */
    @Volatile
    var status = WatcherStatus(WatcherStatus.State.STARTING, since = now(), checkEvery = rescanEvery)
        private set

    fun start(scope: CoroutineScope) {
        if (!Files.isDirectory(root)) {
            update { copy(state = WatcherStatus.State.NOT_RUNNING, since = now(), lastFailure = "$root is not a folder", lastFailureAt = now()) }
            log.warn("Not watching $root: it is not a folder. Edits made outside the server will not reach the devices.")
            return
        }
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
        try {
            while (currentCoroutineContext().isActive) {
                val reason = try {
                    watchUntilTheFoldersMove(recovering = failures > 0).also { failures = 0 }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failures++
                    log.warn("Watching $root failed; watching it again and looking for what was missed", error)
                    update {
                        copy(
                            state = WatcherStatus.State.FAILING,
                            since = if (state == WatcherStatus.State.FAILING) since else now(),
                            lastFailure = error.toString(),
                            lastFailureAt = now(),
                        )
                    }
                    // A root that has gone away fails every time; don't spin on it.
                    delay(minOf(failures, 30).seconds)
                    null
                }
                // Whatever ended that round, changes may have gone unseen during it. After the
                // folders moved they are expected; after a failure, the warning above explains them.
                val found = rescan(journalChanges = true)
                if (!found.isNullOrEmpty()) {
                    log.info("${describe(found)} in $root after ${reason ?: "the failure"}")
                }
            }
        } finally {
            if (status.state != WatcherStatus.State.NOT_RUNNING) {
                update { copy(state = WatcherStatus.State.NOT_RUNNING, since = now()) }
            }
        }
    }

    /**
     * Watches with a fresh service until the folder structure changes, then returns why, so the
     * caller rescans and watches again. Plain edits are journaled here as they come.
     */
    private suspend fun watchUntilTheFoldersMove(recovering: Boolean): String {
        FileSystems.getDefault().newWatchService().use { watcher ->
            watchedDirectories.clear()
            registerRecursively(root, watcher)
            val wasStarting = status.state == WatcherStatus.State.STARTING
            update {
                copy(
                    state = WatcherStatus.State.WATCHING,
                    since = if (state == WatcherStatus.State.WATCHING) since else now(),
                    folders = watchedDirectories.size,
                )
            }
            when {
                wasStarting -> log.info(
                    "Watching $root: ${watchedDirectories.size} folders, ${known.size} notes; " +
                        "checking the whole folder every $rescanEvery as well",
                )
                recovering -> log.info("Watching $root again: ${watchedDirectories.size} folders")
                else -> log.debug("Watching {} folders in {} again", watchedDirectories.size, root)
            }
            var lastScan = TimeSource.Monotonic.markNow()
            while (currentCoroutineContext().isActive) {
                // Walked on its own clock, so a steady stream of events cannot put it off.
                if (lastScan.elapsedNow() >= rescanEvery) {
                    rescan(journalChanges = true)?.forEach { suspects.putIfAbsent(it, TimeSource.Monotonic.markNow()) }
                    lastScan = TimeSource.Monotonic.markNow()
                }
                judgeSuspects()
                // Poll rather than block, so cancelling the server does not leave a thread parked.
                val first = watcher.poll(500, TimeUnit.MILLISECONDS) ?: continue
                // Coalesce the burst an editor produces when saving one file, then take every key
                // that is ready, so a checkout touching many folders is one rebuild and not dozens.
                delay(100.milliseconds)
                val changed = LinkedHashSet<Path>()
                var foldersMoved: String? = null
                var key: WatchKey? = first
                while (key != null) {
                    collect(key, changed)?.let { foldersMoved = foldersMoved ?: it }
                    // An invalid key is a folder gone or moved; the rebuild deals with it.
                    if (!key.reset()) {
                        foldersMoved = foldersMoved ?: "${relativePathOf(key.watchable() as Path)}/ went away"
                    }
                    key = watcher.poll()
                }
                foldersMoved?.let { reason ->
                    // The late events these wait for went with the old service: give them the doubt.
                    suspects.clear()
                    update { copy(rebuilds = rebuilds + 1) }
                    log.info("Folders changed in $root ($reason); watching them again")
                    return reason
                }
                for (file in changed) recordIfChanged(file)
            }
        }
        throw CancellationException("stopped")
    }

    /**
     * Adds the notes [key] reports to [changed]; returns why the folders moved, when they did, and
     * the watches must be built again instead.
     */
    private fun collect(key: WatchKey, changed: MutableSet<Path>): String? {
        val directory = key.watchable() as? Path
        var foldersMoved: String? = null
        for (event in key.pollEvents()) {
            if (event.kind() == OVERFLOW) {
                foldersMoved = foldersMoved ?: "too many events at once"
                continue
            }
            val relative = event.context() as? Path ?: continue
            val path = directory?.resolve(relative) ?: continue
            if (excluded(path)) continue
            when {
                event.kind() == ENTRY_CREATE && path.isDirectory() ->
                    foldersMoved = foldersMoved ?: "${relativePathOf(path)}/ appeared"
                event.kind() == ENTRY_DELETE && path in watchedDirectories ->
                    foldersMoved = foldersMoved ?: "${relativePathOf(path)}/ went away"
                path.extension == "org" -> changed.add(path)
            }
        }
        return foldersMoved
    }

    /** Journals [file] if what is on disk now is not what the journal last heard of it. */
    private suspend fun recordIfChanged(file: Path) {
        val path = relativePathOf(file)
        // The event the periodic check went ahead of: late, but it came.
        suspects.remove(path)
        // Editors write through temporary files, so a single save can arrive as several events;
        // the etag is read fresh here, and a repeat of what is already known is dropped.
        val etag = store.meta(path)?.etag
        if (etag == known[path]) return
        if (etag == null) known.remove(path) else known[path] = etag
        if (!announced(path, etag)) journal.record(path, etag, deleted = etag == null)
        update { copy(notes = known.size, lastChangeAt = now()) }
    }

    /**
     * Walks the whole tree and, when [journalChanges], journals every note made, changed or deleted
     * since [known] was last right. Deletions come last, so a renamed folder's notes exist anew
     * before any client is told the old ones are gone.
     *
     * Returns the paths it journaled, or null when it could not walk the tree.
     */
    private suspend fun rescan(journalChanges: Boolean): List<String>? {
        // A notes folder that is not there — an unmounted drive, a volume not attached yet — lists as
        // empty, which is not every note deleted. Wait for it to come back.
        if (!Files.isDirectory(root)) return null
        val now = try {
            store.list().associate { it.path to it.etag }
        } catch (error: Exception) {
            // A folder vanishing mid-walk; the next rescan will see the tree as it settled.
            log.debug("Could not walk $root", error)
            return null
        }
        val found = mutableListOf<String>()
        if (journalChanges) {
            for ((path, etag) in now) {
                if (known[path] != etag && !announced(path, etag)) {
                    journal.record(path, etag)
                    found += path
                }
            }
            for (path in known.keys - now.keys) {
                if (announced(path, etag = null)) continue
                journal.record(path, etag = null, deleted = true)
                found += path
            }
        }
        known.clear()
        known.putAll(now)
        val checked = now()
        update {
            copy(notes = known.size, lastCheckAt = checked, lastChangeAt = if (found.isEmpty()) lastChangeAt else checked)
        }
        return found
    }

    /** Reports the suspects whose events are past [lateEventGrace]: those events are not coming. */
    private fun judgeSuspects() {
        if (suspects.isEmpty()) return
        val overdue = suspects.filterValues { it.elapsedNow() >= lateEventGrace }.keys.toList()
        if (overdue.isEmpty()) return
        overdue.forEach(suspects::remove)
        reportMissed(overdue)
    }

    /**
     * Whether the journal's latest word on [path] is already this version — [etag], or deleted when
     * null — as it is for a save made through the API, recorded there before its file event comes.
     */
    private fun announced(path: String, etag: String?): Boolean {
        val latest = journal.latest(path) ?: return false
        return if (etag == null) latest.deleted else !latest.deleted && latest.etag == etag
    }

    /**
     * The periodic walk found what nobody had announced, and no event for it followed: events are
     * being missed.
     */
    private fun reportMissed(paths: List<String>) {
        log.warn(
            "${describe(paths)} in $root that the watch did not report; found by the periodic check. " +
                "If this keeps happening, edits made on this computer reach the devices only once a minute.",
        )
        val at = now()
        update { copy(missed = missed + paths.size, lastMissedAt = at, lastMissedPaths = paths.take(SHOWN_PATHS)) }
    }

    private fun describe(paths: List<String>): String {
        val shown = paths.take(SHOWN_PATHS).joinToString(", ")
        val more = if (paths.size > SHOWN_PATHS) " and ${paths.size - SHOWN_PATHS} more" else ""
        val noun = if (paths.size == 1) "change" else "changes"
        return "${paths.size} $noun ($shown$more)"
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

    private inline fun update(change: WatcherStatus.() -> WatcherStatus) {
        status = status.change()
    }

    /**
     * The trash is not part of the library; `.git` holds no notes, and git makes and deletes folders
     * in it all the time, each of which would be a rebuild.
     */
    private fun excluded(path: Path): Boolean =
        root.relativize(path).any { it.toString() == TRASH_DIRECTORY || it.toString() == ".git" }

    private fun relativePathOf(file: Path): String =
        root.relativize(file).joinToString("/") { it.toString() }.ifEmpty { "." }

    private companion object {
        const val TRASH_DIRECTORY = ".korgnote-trash"
        const val SHOWN_PATHS = 5

        fun now() = System.currentTimeMillis()
    }
}
