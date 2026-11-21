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
     * Converts a note title into a normalized file name.
     *
     * The title is trimmed, lowercased, and converted into a slug where letters, digits,
     * hyphens, and underscores are preserved. Other characters are treated as separators,
     * and non-empty parts are joined with underscores. The configured file extension is
     * appended when the resulting slug is not empty.
     *
     * @param title the title to convert into a file name.
     * @return the normalized file name with extension, or null if the title does not contain any valid file-name characters.
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

    /**
     * Joins a folder path and file name using a forward slash separator.
     *
     * The folder value is trimmed of surrounding whitespace and leading or trailing slashes
     * before joining. If the cleaned folder is empty, the name is returned unchanged.
     *
     * @param folder Folder path to prepend to the name.
     * @param name File name or path segment to append.
     * @return The joined path, or name when the cleaned folder is empty.
     */
    fun join(folder: String, name: String): String {
        val cleanFolder = folder.trim().trim('/')
        return if (cleanFolder.isEmpty()) name else "$cleanFolder/$name"
    }

    /**
     * Normalizes a note path by trimming surrounding whitespace and slashes, trimming each path segment,
     * rejecting unsafe or empty segments, and ensuring the required file extension is present.
     *
     * A valid path must contain at least one non-empty segment, use forward slashes as separators, and
     * must not contain "." or ".." segments, backslashes, or an extension-like final segment without the
     * required extension.
     *
     * @param input the raw path text to normalize.
     * @return the normalized path with the required extension, or null if the input is not a valid path.
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

    /**
     * Returns the folder portion of a slash-separated path.
     *
     * @param path the path whose containing folder should be extracted.
     * @return the substring before the last '/' character, or an empty string if the path has no folder component.
     */
    fun folderOf(path: String): String = path.substringBeforeLast('/', "")
}
