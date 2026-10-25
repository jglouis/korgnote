package com.korgnote.app.files

/**
 * A folder as links name it, and where that folder is on this device.
 *
 * The notes are written on one machine, where a synced folder is `~/Sync`; on a phone the same
 * folder, synced too, is somewhere else. [from] is the start of the path as the links write it;
 * [to] is the folder here: a path on desktop, the folder chosen in the system's picker on Android.
 */
data class FileLinkMapping(val from: String, val to: String)

/**
 * The mapping whose [FileLinkMapping.from] begins [path], with the folders and file name of the
 * path below it; the longest wins, so `~/Sync/photos` beats `~/Sync`. Compared folder by folder:
 * `~/Sync` does not cover `~/Syncthing/x.pdf`. Null when no mapping covers the path, or when what
 * is below climbs back out of the folder.
 */
fun List<FileLinkMapping>.covering(path: String): Pair<FileLinkMapping, List<String>>? {
    val segments = pathSegments(path)
    val (mapping, from) = filter { it.from.isNotBlank() && it.to.isNotBlank() }
        .map { it to pathSegments(it.from) }
        .filter { (_, from) -> from.size <= segments.size && segments.subList(0, from.size) == from }
        .maxByOrNull { (_, from) -> from.size }
        ?: return null
    val below = segments.drop(from.size)
    return if (".." in below) null else mapping to below
}

/** `~/Sync/a.pdf` is `~`, `Sync`, `a.pdf`; `/home/a.pdf` is `/`, `home`, `a.pdf`. */
internal fun pathSegments(path: String): List<String> {
    val p = path.trim()
    val root = if (p.startsWith("/")) listOf("/") else emptyList()
    return root + p.split('/').filter { it.isNotEmpty() && it != "." }
}

/** Mappings as one stored string: a mapping per line, its two halves split by a tab. */
internal fun encodeFileLinkMappings(mappings: List<FileLinkMapping>): String =
    mappings.joinToString("\n") { "${it.from}\t${it.to}" }

internal fun decodeFileLinkMappings(stored: String?): List<FileLinkMapping> =
    stored.orEmpty().split('\n').mapNotNull { line ->
        val at = line.indexOf('\t')
        if (at < 0) null else FileLinkMapping(line.substring(0, at), line.substring(at + 1))
    }
