package com.korgnote.common.fs

/**
 * Paths for notes the app creates or renames.
 *
 * The server accepts only relative `.org` paths with no empty, `.` or `..` segment; checking the
 * same here means a bad name is refused in the dialog that typed it, not by a sync that fails later
 * and keeps failing.
 */
object NotePaths {
    private const val EXTENSION = ".org"

    /**
     * A file name for a note titled [title]: lower-case words joined by `_`, plus `.org` — the
     * convention the existing notes follow. Null when the title has nothing usable in it.
     */
    fun fileNameFor(title: String): String? {
        val slug = title.trim().lowercase()
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString("_")
        return slug.takeIf { it.isNotEmpty() }?.plus(EXTENSION)
    }

    /** [name] inside [folder]; a blank folder is the top of the notes directory. */
    fun join(folder: String, name: String): String {
        val cleanFolder = folder.trim().trim('/')
        return if (cleanFolder.isEmpty()) name else "$cleanFolder/$name"
    }

    /**
     * [input] as a note path, or null when it cannot be one.
     *
     * Forgiving about what people type — surrounding spaces and slashes, a missing `.org` — and
     * strict about what the server would refuse: an empty, `.` or `..` segment, a backslash, or
     * another extension.
     */
    fun normalize(input: String): String? {
        var path = input.trim().trim('/')
        if (path.isEmpty()) return null
        val segments = path.split('/').map { it.trim() }
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.contains('\\') }) return null
        path = segments.joinToString("/")
        val last = segments.last()
        if (!path.endsWith(EXTENSION)) {
            if (last.contains('.')) return null
            path += EXTENSION
        }
        if (last == EXTENSION) return null
        return path
    }

    /** The folder part of [path], empty for a note at the top. */
    fun folderOf(path: String): String = path.substringBeforeLast('/', "")
}
