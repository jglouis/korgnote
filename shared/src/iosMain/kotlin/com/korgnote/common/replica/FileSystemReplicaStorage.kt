package com.korgnote.common.replica

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.precomposedStringWithCanonicalMapping
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/**
 * The replica on iOS, in the app container, with the same layout as every other platform.
 *
 * `NSString.writeToFile(atomically = true)` gives the same crash-safety as the JVM implementation's
 * write-then-rename: a killed app leaves the old note or the new one, never half of either. The files
 * are reached off the caller's thread, as on the JVM, since that is often the main one.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class FileSystemReplicaStorage(root: String) : ReplicaStorage {

    private val manager = NSFileManager.defaultManager
    private val notesDir = "$root/notes"
    private val baseDir = "$root/.korgnote/base"
    private val metaFile = "$root/.korgnote/meta.json"
    private val json = Json { ignoreUnknownKeys = true }
    private val decoded = DecodedMeta()

    private fun pathIn(dir: String, path: String): String {
        requireSafeReplicaPath(path)
        return "$dir/$path"
    }

    private fun readText(path: String): String? =
        NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)

    private fun writeText(path: String, content: String) {
        val parent = path.substringBeforeLast('/')
        manager.createDirectoryAtPath(parent, withIntermediateDirectories = true, attributes = null, error = null)
        // NSString.create, not a cast: a Kotlin String is not an NSString in Kotlin/Native, and
        // `content as NSString` compiles but throws at runtime.
        val written = NSString.create(string = content)
            .writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        check(written) { "could not write $path" }
    }

    /**
     * iOS hands a name back with its accents apart, however it was given, and takes both spellings for
     * one file; to a server on Linux they are two. So a file goes by the name it is on record under,
     * or with its accents together, as nearly every server has it. Sync would otherwise send the
     * other spelling as a new note, and then take the note for deleted when the server does not list it.
     */
    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        val enumerator = manager.enumeratorAtPath(notesDir) ?: return@withContext emptyList()
        val recorded = readMeta().files.keys.groupBy(::accentsTogether)
        val found = mutableListOf<String>()
        while (true) {
            val next = enumerator.nextObject() as? String ?: break
            if (!next.endsWith(".org")) continue
            val name = accentsTogether(next)
            found += recorded[name] ?: listOf(name)
        }
        found.sorted()
    }

    /** From each file's attributes, as [list] finds the files: no note is read. */
    override suspend fun stamps(): Map<String, FileStamp>? = withContext(Dispatchers.IO) {
        val enumerator = manager.enumeratorAtPath(notesDir) ?: return@withContext emptyMap()
        val recorded = readMeta().files.keys.groupBy(::accentsTogether)
        val found = mutableMapOf<String, FileStamp>()
        while (true) {
            val next = enumerator.nextObject() as? String ?: break
            if (!next.endsWith(".org")) continue
            val attributes = manager.attributesOfItemAtPath("$notesDir/$next", error = null) ?: continue
            val modified = attributes[NSFileModificationDate] as? NSDate ?: continue
            val size = attributes[NSFileSize] as? NSNumber ?: continue
            val stamp = FileStamp((modified.timeIntervalSince1970 * 1_000_000).toLong(), size.longLongValue)
            val name = accentsTogether(next)
            for (path in recorded[name] ?: listOf(name)) found[path] = stamp
        }
        found
    }

    private fun accentsTogether(path: String): String = NSString.create(string = path).precomposedStringWithCanonicalMapping

    /**
     * A server on Linux can hold both spellings as two notes, which here are one file. Whichever goes
     * first, the file stays for the other, which may hold an edit not yet sent.
     */
    private suspend fun isRecordedUnderAnotherName(path: String): Boolean {
        val name = accentsTogether(path)
        return readMeta().files.values.any { !it.deleted && it.path != path && accentsTogether(it.path) == name }
    }

    override suspend fun read(path: String): String? = withContext(Dispatchers.IO) { readText(pathIn(notesDir, path)) }

    override suspend fun write(path: String, content: String) = withContext(Dispatchers.IO) {
        writeText(pathIn(notesDir, path), content)
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            if (!isRecordedUnderAnotherName(path)) manager.removeItemAtPath(pathIn(notesDir, path), error = null)
        }
    }

    override suspend fun readBase(path: String): String? = withContext(Dispatchers.IO) { readText(pathIn(baseDir, path)) }

    override suspend fun writeBase(path: String, content: String) = withContext(Dispatchers.IO) {
        writeText(pathIn(baseDir, path), content)
    }

    override suspend fun deleteBase(path: String) {
        withContext(Dispatchers.IO) {
            if (!isRecordedUnderAnotherName(path)) manager.removeItemAtPath(pathIn(baseDir, path), error = null)
        }
    }

    override suspend fun readMeta(): ReplicaMeta = withContext(Dispatchers.IO) {
        val stamp = metaStamp() ?: return@withContext ReplicaMeta()
        decoded.read(stamp) {
            // Metadata can be rebuilt by the next sync; the notes cannot. Never fail the open.
            readText(metaFile)
                ?.let { text -> runCatching { json.decodeFromString<ReplicaMeta>(text) }.getOrNull() }
                ?.withBothSpellingsSettled()
        }
    }

    override suspend fun writeMeta(meta: ReplicaMeta) = withContext(Dispatchers.IO) {
        writeText(metaFile, json.encodeToString(meta))
        metaStamp()?.let { decoded.wrote(it, meta) }
        Unit
    }

    /**
     * What an earlier version left: both spellings on record, for one file, so one note listed twice.
     * Only a complete manifest says which the server has, and the next could be a long way off, so
     * generation 0 asks for one: it takes away the spelling the server does not have. Both stay until
     * then, as a spelling with no record would be fetched again, over the one file, if it is really there.
     * An edit not sent goes first: a delta sends it against the version it was made on, where a complete
     * manifest has a server with the one spelling refuse it as a new note. The next start settles it.
     * Here and not in [readMeta]'s callers, as it looks at every name.
     */
    private fun ReplicaMeta.withBothSpellingsSettled(): ReplicaMeta {
        val twice = files.keys.groupBy(::accentsTogether).values.filter { it.size > 1 }.flatten()
        return if (twice.isEmpty() || twice.any { files.getValue(it).dirty }) this else copy(generation = 0)
    }

    /** Null when there is no `meta.json`, or its attributes cannot be read. */
    private fun metaStamp(): MetaStamp? {
        val attributes = manager.attributesOfItemAtPath(metaFile, error = null) ?: return null
        val modified = attributes[NSFileModificationDate] as? NSDate ?: return null
        val size = attributes[NSFileSize] as? NSNumber ?: return null
        return MetaStamp((modified.timeIntervalSince1970 * 1_000_000).toLong(), size.longLongValue)
    }
}

/** The app container directory the replica lives in on iOS. */
fun defaultReplicaRoot(): String {
    val paths = NSSearchPathForDirectoriesInDomains(
        NSApplicationSupportDirectory,
        NSUserDomainMask,
        true,
    )
    val support = paths.firstOrNull() as? String ?: NSURL.fileURLWithPath(".").path.orEmpty()
    return "$support/KorgNote"
}
