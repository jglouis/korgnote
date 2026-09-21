package com.korgnote.common.replica

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

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
 * Writes go to a temporary file and are then moved into place, so a crash or a pulled battery
 * leaves either the old content or the new one — never half a note.
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
        if (!temp.renameTo(target)) {
            // Some filesystems refuse a rename onto an existing file.
            target.delete()
            check(temp.renameTo(target)) { "could not move ${temp.path} onto ${target.path}" }
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
