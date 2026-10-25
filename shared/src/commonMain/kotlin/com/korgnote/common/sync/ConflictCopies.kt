package com.korgnote.common.sync

/**
 * The name a conflicting copy is saved under, mirroring Syncthing's so it is already familiar:
 * `notes/idea.org` becomes `notes/idea.sync-conflict-20260920-101500.org`, in the same folder.
 */
fun conflictCopyName(path: String, timestamp: String): String {
    val extension = path.substringAfterLast('.', "")
    val stem = if (extension.isEmpty()) path else path.dropLast(extension.length + 1)
    return if (extension.isEmpty()) "$stem.sync-conflict-$timestamp"
    else "$stem.sync-conflict-$timestamp.$extension"
}

/**
 * Our copies, `stem.sync-conflict-yyyyMMdd-HHmmss.ext`, and Syncthing's, which add the device that
 * made the change (`…-HHmmss-ABCDEF1.ext`). The same suffix carries the counter added when two
 * copies of one note land in the same second.
 */
private val conflictCopy = Regex("""^(.*)\.sync-conflict-\d{8}-\d{6}(?:-[A-Za-z0-9]+)?(\.[^./]+)?$""")

/** The note [path] is a conflict copy of, or null when it is not a conflict copy. */
fun conflictCopyOriginal(path: String): String? =
    conflictCopy.matchEntire(path)?.let { it.groupValues[1] + it.groupValues[2] }

/**
 * Every note in [paths] that has conflict copies beside it, with those copies newest first.
 *
 * This *is* a note's conflict state: the copy stands until someone deals with it, whichever device
 * — or Syncthing — made it, and deleting it is what resolves the conflict. A copy whose note is gone
 * has nothing left to conflict with and is just a note.
 */
fun conflictsIn(paths: Collection<String>): Map<String, List<String>> {
    val present = paths.toSet()
    return paths
        .mapNotNull { path -> conflictCopyOriginal(path)?.takeIf { it in present }?.let { it to path } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, copies) -> copies.sortedDescending() }
}
