package com.korgnote.common.sync

/**
 * Builds the file name used for a conflict copy of [path].
 *
 * The generated name inserts `.sync-conflict-[timestamp]` before the final file extension when one
 * is present, or appends it to the full path when there is no extension.
 *
 * @param path original file path or file name.
 * @param timestamp timestamp string to include in the conflict copy name.
 * @return conflict copy path derived from [path] and [timestamp].
 */
fun conflictCopyName(path: String, timestamp: String): String {
    val extension = path.substringAfterLast('.', "")
    val stem = if (extension.isEmpty()) path else path.dropLast(extension.length + 1)
    return if (extension.isEmpty()) "$stem.sync-conflict-$timestamp"
    else "$stem.sync-conflict-$timestamp.$extension"
}

/**
 * Matches file names that are sync-conflict copies rather than ordinary notes.
 *
 * The expression recognizes names ending in `.sync-conflict-YYYYMMDD-HHMMSS`,
 * optionally followed by a dash and an alphanumeric suffix, and preserves an
 * optional final extension. Capture group 1 is the original base path before the
 * conflict marker; capture group 2 is the final extension, when present.
 */
private val conflictCopy = Regex("""^(.*)\.sync-conflict-\d{8}-\d{6}(?:-[A-Za-z0-9]+)?(\.[^./]+)?$""")

/**
 * Returns the original file path encoded by a conflict-copy path.
 *
 * @param path The path to inspect.
 * @return The original path if [path] matches the conflict-copy naming pattern, or null otherwise.
 */
fun conflictCopyOriginal(path: String): String? =
    conflictCopy.matchEntire(path)?.let { it.groupValues[1] + it.groupValues[2] }

/**
 * Finds conflict-copy paths whose original path is also present in [paths].
 *
 * @param paths The file paths to inspect.
 * @return A map from each original path to the matching conflict-copy paths found in [paths],
 * sorted in descending order. Paths that are not conflict copies, or whose original path is not
 * present in [paths], are omitted.
 */
fun conflictsIn(paths: Collection<String>): Map<String, List<String>> {
    val present = paths.toSet()
    return paths
        .mapNotNull { path -> conflictCopyOriginal(path)?.takeIf { it in present }?.let { it to path } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, copies) -> copies.sortedDescending() }
}
