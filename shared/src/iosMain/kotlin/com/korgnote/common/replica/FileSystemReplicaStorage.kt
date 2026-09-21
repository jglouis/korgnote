package com.korgnote.common.replica

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.Json
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * The replica on iOS, in the app container, with the same layout as every other platform.
 *
 * `NSString.writeToFile(atomically = true)` gives the same crash-safety as the JVM implementation's
 * write-then-rename: a killed app leaves the old note or the new one, never half of either.
 */
@OptIn(ExperimentalForeignApi::class)
class FileSystemReplicaStorage(private val root: String) : ReplicaStorage {

    private val manager = NSFileManager.defaultManager
    private val notesDir = "$root/notes"
    private val baseDir = "$root/.korgnote/base"
    private val metaFile = "$root/.korgnote/meta.json"
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private fun pathIn(dir: String, path: String): String {
        requireSafeReplicaPath(path)
        return "$dir/$path"
    }

    private fun readText(path: String): String? =
        NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)

    private fun writeText(path: String, content: String) {
        val parent = path.substringBeforeLast('/')
        manager.createDirectoryAtPath(parent, withIntermediateDirectories = true, attributes = null, error = null)
        (content as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    override suspend fun list(): List<String> {
        val enumerator = manager.enumeratorAtPath(notesDir) ?: return emptyList()
        val found = mutableListOf<String>()
        while (true) {
            val next = enumerator.nextObject() as? String ?: break
            if (next.endsWith(".org")) found.add(next)
        }
        return found.sorted()
    }

    override suspend fun read(path: String): String? = readText(pathIn(notesDir, path))

    override suspend fun write(path: String, content: String) = writeText(pathIn(notesDir, path), content)

    override suspend fun delete(path: String) {
        manager.removeItemAtPath(pathIn(notesDir, path), error = null)
    }

    override suspend fun readBase(path: String): String? = readText(pathIn(baseDir, path))

    override suspend fun writeBase(path: String, content: String) = writeText(pathIn(baseDir, path), content)

    override suspend fun deleteBase(path: String) {
        manager.removeItemAtPath(pathIn(baseDir, path), error = null)
    }

    override suspend fun readMeta(): ReplicaMeta {
        val text = readText(metaFile) ?: return ReplicaMeta()
        // Metadata can be rebuilt by the next sync; the notes cannot. Never fail the open.
        return runCatching { json.decodeFromString<ReplicaMeta>(text) }.getOrElse { ReplicaMeta() }
    }

    override suspend fun writeMeta(meta: ReplicaMeta) = writeText(metaFile, json.encodeToString(meta))
}

/** The app container directory the replica lives in on iOS. */
@OptIn(ExperimentalForeignApi::class)
fun defaultReplicaRoot(): String {
    val paths = NSSearchPathForDirectoriesInDomains(
        NSApplicationSupportDirectory.toULong(),
        NSUserDomainMask.toULong(),
        true,
    )
    val support = paths.firstOrNull() as? String ?: NSURL.fileURLWithPath(".").path.orEmpty()
    return "$support/KorgNote"
}
