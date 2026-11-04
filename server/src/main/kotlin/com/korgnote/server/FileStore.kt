package com.korgnote.server

import com.korgnote.common.pairing.sha256Hex
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.time.LocalDate
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/** A file as it is on disk right now. [etag] is a hash of the content, not of its timestamps. */
data class FileMeta(
    val path: String,
    val size: Long,
    val mtimeMillis: Long,
    val etag: String,
)

/** A file's text and the version it is, from one read. */
data class FileContent(val meta: FileMeta, val text: String)

sealed interface WriteResult {
    data class Written(val meta: FileMeta) : WriteResult

    /** The file on disk is not the version the writer based its edit on. */
    data class Conflict(val current: FileMeta) : WriteResult

    data object NotFound : WriteResult

    /** The path is not a note of the library. Asking again would not help. */
    data object Invalid : WriteResult

    data object TooLarge : WriteResult

    /** The disk refused. [cause] is for the log: its message says where the notes are on this computer. */
    data class Failed(val cause: Exception) : WriteResult

    data object ReadOnly : WriteResult
}

/**
 * Whether a folder, relative to the notes directory, holds notes of the library. A dot-folder does
 * not: `.korgnote-trash` holds what was deleted, `.stversions` Syncthing's old versions, `.git` git's
 * own business. Served as notes, those would reach every phone as more copies of the same headings.
 */
internal fun isLibraryFolder(relativePath: String): Boolean = relativePath.split('/').none { it.startsWith('.') }

/**
 * Whether [relativePath], `/`-separated from the notes directory, is a note of the library: an `.org`
 * file in no dot-folder. This one rule is what is listed, what can be read and written, and what is
 * watched.
 */
internal fun isLibraryPath(relativePath: String): Boolean =
    relativePath.endsWith(".org") && isLibraryFolder(relativePath.substringBeforeLast('/', ""))

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
    /** True when the notes directory was not there at startup, so there is nowhere to write to. */
    val readOnly: Boolean = false,
) {
    private data class HashEntry(val size: Long, val mtimeMillis: Long, val etag: String)

    private val hashes = ConcurrentHashMap<String, HashEntry>()

    /**
     * Held from a write's check of the version to its move into place, so that of two devices pushing
     * from the same version only one wins and the other is told to merge. An editor outside the
     * server does not take it: a save landing in that instant is still overwritten, which nothing on
     * a plain filesystem can prevent.
     */
    private val writing = Any()

    /**
     * Every note currently on disk, in path order.
     *
     * What has gone by the time the walk reaches it is simply not there, and this store's own writes
     * leave temporary files that do so. Anything else that stops a read fails the whole list: left out
     * of a complete manifest, a note is taken for deleted and goes from every phone.
     */
    fun list(): List<FileMeta> {
        if (!root.exists() || !root.isDirectory()) return emptyList()
        val found = mutableListOf<FileMeta>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                try {
                    if (file.isRegularFile()) {
                        val path = relativePathOf(file)
                        // Through [resolve], so that what is listed can also be fetched: a link to a
                        // file outside the root cannot, and a phone would keep trying.
                        if (resolve(path) != null) found += metaOf(path, file)
                    }
                } catch (_: NoSuchFileException) {
                    // Gone since the folder was read.
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                if (exc is NoSuchFileException) FileVisitResult.CONTINUE else throw exc

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult =
                if (exc == null || exc is NoSuchFileException) FileVisitResult.CONTINUE else throw exc
        })
        return found.sortedBy { it.path }
    }

    /**
     * [path]'s text and the version that text is, from a single read: read apart, an edit landing in
     * between pairs the old etag with the new text, and the client's next push is a false conflict.
     */
    fun readWithMeta(path: String): FileContent? {
        val file = resolve(path) ?: return null
        if (!file.isRegularFile()) return null
        return runCatching {
            val mtime = file.getLastModifiedTime().toMillis()
            val bytes = Files.readAllBytes(file)
            FileContent(FileMeta(path, bytes.size.toLong(), mtime, hashOf(bytes)), bytes.decodeToString())
        }.getOrNull()
    }

    fun meta(path: String): FileMeta? {
        val file = resolve(path) ?: return null
        if (!file.isRegularFile()) return null
        return metaOf(path, file)
    }

    /**
     * The sync API's write: creates or replaces [path] according to the conditional headers.
     *
     * [ifMatch] is "I edited the version with this hash" — a mismatch is a [WriteResult.Conflict],
     * not an error, because the two versions can still be merged. [mustNotExist] is `If-None-Match:
     * *`, the capture case: create it, or tell me it is already there.
     *
     * The write goes to a temporary file in the same directory and is then moved into place, so a
     * crash or a dying connection cannot leave a half-written note.
     */
    fun put(
        path: String,
        content: String,
        ifMatch: String? = null,
        mustNotExist: Boolean = false,
    ): WriteResult {
        if (readOnly) return WriteResult.ReadOnly
        val file = resolve(path) ?: return WriteResult.Invalid

        val bytes = content.encodeToByteArray()
        if (bytes.size > MAX_BYTES) return WriteResult.TooLarge

        return synchronized(writing) {
            val exists = file.isRegularFile()
            if (mustNotExist && exists) return WriteResult.Conflict(metaOf(path, file))
            if (ifMatch != null) {
                if (!exists) return WriteResult.NotFound
                val current = metaOf(path, file)
                if (current.etag != ifMatch) return WriteResult.Conflict(current)
            }

            try {
                Files.createDirectories(file.parent)
                val temporary = Files.createTempFile(file.parent, ".korgnote", ".tmp")
                try {
                    Files.write(temporary, bytes)
                    keepPermissions(temporary, replacing = file.takeIf { exists })
                    if (mustNotExist) {
                        // Not an atomic move, which replaces whatever it finds: an editor may have
                        // made this file since the check, and a new note must never replace one.
                        Files.move(temporary, file)
                    } else {
                        Files.move(
                            temporary,
                            file,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE,
                        )
                    }
                } catch (appeared: FileAlreadyExistsException) {
                    Files.deleteIfExists(temporary)
                    return WriteResult.Conflict(metaOf(path, file))
                } catch (failure: Exception) {
                    Files.deleteIfExists(temporary)
                    throw failure
                }
                hashes.remove(path)
                WriteResult.Written(metaOf(path, file))
            } catch (failure: Exception) {
                WriteResult.Failed(failure)
            }
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
        val file = resolve(path) ?: return WriteResult.Invalid
        return synchronized(writing) {
            if (!file.isRegularFile()) return WriteResult.NotFound

            val current = metaOf(path, file)
            if (ifMatch != null && current.etag != ifMatch) return WriteResult.Conflict(current)

            try {
                val day = LocalDate.now().toString()
                val target = root.resolve(".korgnote-trash").resolve(day).resolve(path)
                Files.createDirectories(target.parent)
                Files.move(file, uniqueTrashPath(target), StandardCopyOption.ATOMIC_MOVE)
                hashes.remove(path)
                WriteResult.Written(current)
            } catch (failure: Exception) {
                WriteResult.Failed(failure)
            }
        }
    }

    /**
     * Gives a freshly written temporary file the permissions the note should have.
     *
     * `createTempFile` makes files readable by their owner only, and the atomic move carries that
     * onto the note — so every save used to turn a `rw-r--r--` note into `rw-------`, and every
     * note created from the app was born that way. A replaced note keeps its own mode; a new one
     * gets the usual `rw-r--r--`. Skipped where the filesystem has no POSIX permissions.
     */
    private fun keepPermissions(temporary: Path, replacing: Path?) {
        runCatching {
            val permissions = replacing?.let { Files.getPosixFilePermissions(it) }
                ?: PosixFilePermissions.fromString("rw-r--r--")
            Files.setPosixFilePermissions(temporary, permissions)
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
            hashOf(Files.readAllBytes(file)).also {
                // Where timestamps are coarse, an edit moments from now could keep this size and this
                // time: TODO to DONE does. So a file only just changed is hashed again next time, as
                // a stale etag here would pass `If-Match` and let a push overwrite that edit.
                if (System.currentTimeMillis() - mtime >= TIMESTAMP_GRAIN_MILLIS) hashes[path] = HashEntry(size, mtime, it)
            }
        }
        return FileMeta(path, size, mtime, etag)
    }

    private fun hashOf(content: ByteArray): String = sha256Hex(content).take(ETAG_HEX_DIGITS)

    private fun relativePathOf(file: Path): String =
        root.relativize(file).joinToString("/") { it.toString() }

    /**
     * Turns a request path into a file inside [root], or null when it is not one.
     *
     * Rejects absolute paths, empty or `.`/`..` segments, anything that [isLibraryPath] says is not a
     * note, and anything that still resolves outside the root once symlinks are followed. The old
     * code was protected only by the accident of checking its startup map first; creating files will
     * remove that accident.
     */
    private fun resolve(path: String): Path? {
        if (path.isBlank()) return null
        val segments = path.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." || it.contains('\\') }) return null
        if (!isLibraryPath(path)) return null

        val candidate = runCatching { root.resolve(path).normalize() }.getOrNull() ?: return null
        val rootReal = runCatching { root.toRealPath() }.getOrElse { root.normalize() }
        if (!candidate.normalize().startsWith(root.normalize())) return null
        // Through links, the file — or, for a new one, the nearest folder it would be made in — must
        // still be inside the root: a folder linked in from elsewhere is not part of the library.
        val existing = generateSequence(candidate) { it.parent }.firstOrNull { it.exists() } ?: return null
        val existingReal = runCatching { existing.toRealPath() }.getOrNull() ?: return null
        if (!existingReal.startsWith(rootReal)) return null
        return candidate
    }

    private companion object {
        const val ETAG_HEX_DIGITS = 32

        /** FAT keeps two seconds, ext3 and HFS+ one; anything finer is safe under this too. */
        const val TIMESTAMP_GRAIN_MILLIS = 2_000L
        const val MAX_BYTES = 32 * 1024 * 1024
    }
}
