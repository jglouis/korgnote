package com.korgnote.server

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** A file as it is on disk right now. [etag] is a hash of the content, not of its timestamps. */
data class FileMeta(
    val path: String,
    val size: Long,
    val mtimeMillis: Long,
    val etag: String,
)

sealed interface WriteResult {
    data class Written(val meta: FileMeta) : WriteResult

    /** The file on disk is not the version the writer based its edit on. */
    data class Conflict(val current: FileMeta) : WriteResult

    data object NotFound : WriteResult

    data class Rejected(val reason: String) : WriteResult

    data object ReadOnly : WriteResult
}

/**
 * Reads and writes the notes directory, answering from the disk every time it is asked.
 *
 * The previous implementation loaded every file once at startup and served that map forever, which
 * made it wrong about the world as soon as a file was edited in Emacs or delivered by Syncthing —
 * and a save could then overwrite an edit the server had never seen. Everything here is therefore
 * derived on demand; only *hashes* are cached, keyed on the size and timestamp they were computed
 * from, so an unchanged file is not re-read.
 */
class FileStore(
    /** The notes directory being served; the watcher needs it too. */
    val root: Path,
    /** True when the store is serving bundled sample files, which must not be written to. */
    val readOnly: Boolean = false,
) {
    private data class HashEntry(val size: Long, val mtimeMillis: Long, val etag: String)

    private val hashes = ConcurrentHashMap<String, HashEntry>()

    /** Every note currently on disk, in path order. */
    fun list(): List<FileMeta> {
        if (!root.exists() || !root.isDirectory()) return emptyList()
        return Files.walk(root).use { paths ->
            paths.filter {
                it.isRegularFile() && it.extension in ALLOWED_EXTENSIONS &&
                    // Deleted notes live under the root but are not part of the library.
                    !it.any { segment -> segment.toString() == TRASH_DIRECTORY }
            }
                .map { metaOf(relativePathOf(it), it) }
                .toList()
        }.filterNotNull().sortedBy { it.path }
    }

    fun read(path: String): String? {
        val file = resolve(path) ?: return null
        if (!file.isRegularFile()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun meta(path: String): FileMeta? {
        val file = resolve(path) ?: return null
        if (!file.isRegularFile()) return null
        return metaOf(path, file)
    }

    /**
     * Replaces [path]'s contents.
     *
     * When [expectedEtag] is given and the file on disk no longer matches it, nothing is written and
     * [WriteResult.Conflict] is returned: the file changed underneath the writer, and overwriting it
     * would discard an edit made elsewhere.
     *
     * The write goes to a temporary file in the same directory and is then moved into place, so a
     * crash or a dying connection cannot leave a half-written note.
     */
    fun write(path: String, content: String, expectedEtag: String?): WriteResult {
        if (readOnly) return WriteResult.ReadOnly
        val file = resolve(path) ?: return WriteResult.Rejected("invalid path")
        if (!file.isRegularFile()) return WriteResult.NotFound

        val bytes = content.encodeToByteArray()
        if (bytes.size > MAX_BYTES) return WriteResult.Rejected("file too large")

        val current = metaOf(path, file)
        if (expectedEtag != null && current.etag != expectedEtag) return WriteResult.Conflict(current)

        val temporary = Files.createTempFile(file.parent, ".korgnote", ".tmp")
        return try {
            Files.write(temporary, bytes)
            Files.move(
                temporary,
                file,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            hashes.remove(path)
            WriteResult.Written(metaOf(path, file))
        } catch (failure: Exception) {
            Files.deleteIfExists(temporary)
            WriteResult.Rejected(failure.message ?: "write failed")
        }
    }

    /**
     * The sync API's write: creates or replaces [path] according to the conditional headers.
     *
     * [ifMatch] is "I edited the version with this hash" — a mismatch is a [WriteResult.Conflict],
     * not an error, because the two versions can still be merged. [mustNotExist] is `If-None-Match:
     * *`, the capture case: create it, or tell me it is already there.
     */
    fun put(
        path: String,
        content: String,
        ifMatch: String? = null,
        mustNotExist: Boolean = false,
    ): WriteResult {
        if (readOnly) return WriteResult.ReadOnly
        val file = resolve(path) ?: return WriteResult.Rejected("invalid path")

        val bytes = content.encodeToByteArray()
        if (bytes.size > MAX_BYTES) return WriteResult.Rejected("file too large")

        val exists = file.isRegularFile()
        if (mustNotExist && exists) return WriteResult.Conflict(metaOf(path, file))
        if (ifMatch != null) {
            if (!exists) return WriteResult.NotFound
            val current = metaOf(path, file)
            if (current.etag != ifMatch) return WriteResult.Conflict(current)
        }

        return try {
            Files.createDirectories(file.parent)
            val temporary = Files.createTempFile(file.parent, ".korgnote", ".tmp")
            try {
                Files.write(temporary, bytes)
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (failure: Exception) {
                Files.deleteIfExists(temporary)
                throw failure
            }
            hashes.remove(path)
            WriteResult.Written(metaOf(path, file))
        } catch (failure: Exception) {
            WriteResult.Rejected(failure.message ?: "write failed")
        }
    }

    /**
     * Moves [path] into `.korgnote-trash/<date>/`, rather than unlinking it.
     *
     * Cheap insurance: a sync bug that deletes the wrong note is recoverable, and a few kilobytes of
     * text is not worth the risk of being right.
     */
    fun delete(path: String, ifMatch: String?): WriteResult {
        if (readOnly) return WriteResult.ReadOnly
        val file = resolve(path) ?: return WriteResult.Rejected("invalid path")
        if (!file.isRegularFile()) return WriteResult.NotFound

        val current = metaOf(path, file)
        if (ifMatch != null && current.etag != ifMatch) return WriteResult.Conflict(current)

        return try {
            val day = LocalDate.now().toString()
            val target = root.resolve(".korgnote-trash").resolve(day).resolve(path)
            Files.createDirectories(target.parent)
            Files.move(file, uniqueTrashPath(target), StandardCopyOption.ATOMIC_MOVE)
            hashes.remove(path)
            WriteResult.Written(current)
        } catch (failure: Exception) {
            WriteResult.Rejected(failure.message ?: "delete failed")
        }
    }

    /** Deleting the same path twice in a day must not overwrite the first copy. */
    private fun uniqueTrashPath(target: Path): Path {
        if (!target.exists()) return target
        var index = 2
        while (true) {
            val candidate = target.resolveSibling("${target.fileName}.$index")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun metaOf(path: String, file: Path): FileMeta {
        val size = file.fileSize()
        val mtime = file.getLastModifiedTime().toMillis()
        val cached = hashes[path]
        val etag = if (cached != null && cached.size == size && cached.mtimeMillis == mtime) {
            cached.etag
        } else {
            hashOf(file).also { hashes[path] = HashEntry(size, mtime, it) }
        }
        return FileMeta(path, size, mtime, etag)
    }

    private fun hashOf(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
        return digest.take(ETAG_BYTES).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun relativePathOf(file: Path): String =
        root.relativize(file).joinToString("/") { it.toString() }

    /**
     * Turns a request path into a file inside [root], or null when it is not one.
     *
     * Rejects absolute paths, empty or `.`/`..` segments, unexpected extensions, and anything that
     * still resolves outside the root once symlinks are followed. The old code was protected only
     * by the accident of checking its startup map first; creating files will remove that accident.
     */
    private fun resolve(path: String): Path? {
        if (path.isBlank()) return null
        val segments = path.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." || it.contains('\\') }) return null

        val candidate = runCatching { root.resolve(path).normalize() }.getOrNull() ?: return null
        if (candidate.extension !in ALLOWED_EXTENSIONS) return null

        val rootReal = runCatching { root.toRealPath() }.getOrElse { root.normalize() }
        if (!candidate.normalize().startsWith(root.normalize())) return null
        if (candidate.exists()) {
            val fileReal = runCatching { candidate.toRealPath() }.getOrNull() ?: return null
            if (!fileReal.startsWith(rootReal)) return null
        }
        return candidate
    }

    private companion object {
        const val TRASH_DIRECTORY = ".korgnote-trash"
        val ALLOWED_EXTENSIONS = setOf("org")
        const val ETAG_BYTES = 16
        const val MAX_BYTES = 32 * 1024 * 1024
    }
}
