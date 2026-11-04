package com.korgnote.common.replica

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * The replica as real files on disk, laid out as `docs/05-sync-protocol.md` describes:
 *
 * ```
 * <root>/notes/            the working copy the user edits
 * <root>/.korgnote/base/   the same tree as the server last had it
 * <root>/.korgnote/meta.json
 * ```
 *
 * `notes/` holds ordinary `.org` files in an ordinary directory, so Syncthing or Emacs can keep
 * working against the same folder during the migration.
 *
 * Writes go to a temporary file and are then moved into place, so an app that crashes or is killed
 * leaves either the old content or the new one, never half a note, and a read made meanwhile finds
 * one or the other. There is no `fsync` before the move yet, so a power cut or a pulled battery can
 * still lose a save the filesystem had not flushed, as on the server.
 */
class FileSystemReplicaStorage(root: File) : ReplicaStorage {

    private val notesDir = File(root, "notes")
    private val baseDir = File(root, ".korgnote/base")
    private val metaFile = File(root, ".korgnote/meta.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private fun fileFor(dir: File, path: String): File {
        requireSafeReplicaPath(path)
        return File(dir, path)
    }

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        if (!notesDir.isDirectory) return@withContext emptyList()
        notesDir.walkTopDown()
            .filter { it.isFile && it.extension == "org" }
            .map { it.relativeTo(notesDir).invariantSeparatorsPath }
            .sorted()
            .toList()
    }

    override suspend fun read(path: String): String? = withContext(Dispatchers.IO) {
        fileFor(notesDir, path).takeIf { it.isFile }?.readText()
    }

    override suspend fun write(path: String, content: String) = withContext(Dispatchers.IO) {
        writeAtomically(fileFor(notesDir, path), content)
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) { fileFor(notesDir, path).delete() }
    }

    override suspend fun readBase(path: String): String? = withContext(Dispatchers.IO) {
        fileFor(baseDir, path).takeIf { it.isFile }?.readText()
    }

    override suspend fun writeBase(path: String, content: String) = withContext(Dispatchers.IO) {
        writeAtomically(fileFor(baseDir, path), content)
    }

    override suspend fun deleteBase(path: String) {
        withContext(Dispatchers.IO) { fileFor(baseDir, path).delete() }
    }

    override suspend fun readMeta(): ReplicaMeta = withContext(Dispatchers.IO) {
        if (!metaFile.isFile) return@withContext ReplicaMeta()
        // A corrupt or half-written meta.json must not make the app unopenable: the replica's files
        // are the real data, and metadata can be rebuilt by the next sync.
        runCatching { json.decodeFromString<ReplicaMeta>(metaFile.readText()) }
            .getOrElse { ReplicaMeta() }
    }

    override suspend fun writeMeta(meta: ReplicaMeta) = withContext(Dispatchers.IO) {
        writeAtomically(metaFile, json.encodeToString(meta))
    }

    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(content)
        moveOver(temp, target)
    }
}

/**
 * Moves [temp] onto [target], which at no moment goes missing.
 *
 * On Android, Linux and macOS a rename replaces its target atomically, and this goes no further.
 * Windows refuses a rename onto a file that exists. Deleting the target first would leave a moment
 * with no file, in which an unlocked `readMeta` finds no metadata and every note looks unsynced, so
 * it is replaced in one move instead.
 */
internal fun moveOver(temp: File, target: File, rename: (File, File) -> Boolean = File::renameTo) {
    if (!rename(temp, target)) ReplacingMove.move(temp, target)
}

/** Apart from the rest, which Android 7 has to load: `java.nio.file` only arrives with Android 8. */
internal object ReplacingMove {
    fun move(
        temp: File,
        target: File,
        atomicMove: (File, File) -> Unit = { from, to -> Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) },
    ) {
        try {
            atomicMove(temp, target)
        } catch (_: AtomicMoveNotSupportedException) {
            // A filesystem with no atomic replace: not atomic, but the file still gets there.
            Files.move(temp.toPath(), target.toPath(), REPLACE_EXISTING)
        }
    }
}

/**
 * Where the replica lives on desktop, following each platform's convention so the notes end up
 * somewhere a user would expect to find them and a backup tool would already be watching.
 *
 * On Android this is not used: the app passes its own context-provided directory instead.
 */
fun defaultDesktopReplicaRoot(
    osName: String = System.getProperty("os.name").orEmpty(),
    home: String = System.getProperty("user.home").orEmpty(),
    env: (String) -> String? = System::getenv,
): File {
    val os = osName.lowercase()
    return when {
        os.contains("mac") -> File(home, "Library/Application Support/KorgNote")
        os.contains("win") -> File(env("APPDATA") ?: "$home\\AppData\\Roaming", "KorgNote")
        else -> File(env("XDG_DATA_HOME") ?: "$home/.local/share", "KorgNote")
    }
}
