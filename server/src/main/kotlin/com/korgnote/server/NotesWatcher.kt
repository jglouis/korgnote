package com.korgnote.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Watches the notes directory so an edit made in Emacs — or delivered by Syncthing — becomes a
 * journal entry, and therefore an SSE event, within a second or two.
 *
 * Without this the server only knows about changes it made itself, which is exactly the blind spot
 * that made the old startup-snapshot server wrong about the world.
 */
class NotesWatcher(
    private val root: Path,
    private val store: FileStore,
    private val journal: ChangeJournal,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (!Files.isDirectory(root)) return
        job = scope.launch(Dispatchers.IO) { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run() {
        val watcher = FileSystems.getDefault().newWatchService()
        try {
            registerRecursively(root, watcher)
            while (currentlyActive()) {
                // Poll rather than block, so cancelling the server does not leave a thread parked.
                val key: WatchKey = withContext(Dispatchers.IO) {
                    watcher.poll(500, TimeUnit.MILLISECONDS)
                } ?: continue

                val directory = key.watchable() as? Path
                for (event in key.pollEvents()) {
                    val relative = event.context() as? Path ?: continue
                    val changed = directory?.resolve(relative) ?: continue

                    // A new directory has to be watched too, or notes created inside it are missed.
                    if (changed.isDirectory() && event.kind() == ENTRY_CREATE) {
                        runCatching { registerRecursively(changed, watcher) }
                        continue
                    }
                    if (changed.extension != "org") continue
                    if (changed.any { it.toString() == ".korgnote-trash" }) continue

                    val path = root.relativize(changed).joinToString("/") { it.toString() }
                    // Editors write through temporary files, so a single save can arrive as several
                    // events; the etag is read fresh here and identical repeats are harmless.
                    val meta = if (changed.isRegularFile()) store.meta(path) else null
                    journal.record(path, meta?.etag, deleted = meta == null)
                }
                if (!key.reset()) break
                // Coalesce the burst an editor produces when saving one file.
                delay(100)
            }
        } finally {
            runCatching { watcher.close() }
        }
    }

    private suspend fun currentlyActive(): Boolean {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        return kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive ?: true
    }

    private fun registerRecursively(directory: Path, watcher: WatchService) {
        Files.walk(directory).use { paths ->
            paths.filter { it.isDirectory() && it.none { part -> part.toString() == ".korgnote-trash" } }
                .forEach { it.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE) }
        }
    }
}
