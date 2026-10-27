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

/**
 * The path a link writes for a file chosen on this device: [covering] the other way round. [below]
 * says, for a mapping, the folders and file name of the file inside its [FileLinkMapping.to], or
 * null when the file is not in that folder. The closest folder wins, as the longest [from] does
 * the other way, so `~/Sync/photos/a.jpg` is written through a mapping for `photos` itself.
 */
fun List<FileLinkMapping>.linkPathFor(below: (FileLinkMapping) -> List<String>?): String? =
    filter { it.from.isNotBlank() && it.to.isNotBlank() }
        .mapNotNull { mapping -> below(mapping)?.takeIf { ".." !in it }?.let { mapping to it } }
        .minByOrNull { (_, names) -> names.size }
        ?.let { (mapping, names) -> joinPath(pathSegments(mapping.from) + names) }

/** [path]'s folders and file name below [folder], both plain paths; null when it is not inside it. */
fun pathBelow(folder: String, path: String): List<String>? {
    val inside = pathSegments(folder)
    val segments = pathSegments(path)
    return if (segments.size > inside.size && segments.subList(0, inside.size) == inside) segments.drop(inside.size) else null
}

/** [pathSegments] put back together: `~`, `Sync`, `a.pdf` is `~/Sync/a.pdf`. */
internal fun joinPath(segments: List<String>): String =
    if (segments.firstOrNull() == "/") "/" + segments.drop(1).joinToString("/") else segments.joinToString("/")

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
