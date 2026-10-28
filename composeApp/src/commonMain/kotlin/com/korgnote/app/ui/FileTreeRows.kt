package com.korgnote.app.ui

/**
 * One line of the file tree as it is drawn: a node, how far it is indented, and — for a folder —
 * whether it is open.
 *
 * The tree is flattened into these before it is rendered, so the list can be a real `LazyColumn`
 * and compose only the rows on screen. Drawing it as a nested structure meant every row of every
 * open folder composed whether or not anyone could see it, and the drawer holds a row per note.
 */
data class TreeRow(
    val node: Node,
    val depth: Int,
    /** Meaningful for folders; always false for files, which have nothing to open. */
    val expanded: Boolean,
    /** For a conflict copy drawn under its note: that note's path. */
    val copyOf: String? = null,
)

/**
 * The rows to draw, top to bottom, skipping everything inside a closed folder.
 *
 * Folders below the root are closed unless [opened] names them, so an untouched tree shows the
 * notes at the top and a row per folder rather than every note at once. The root is open unless
 * [opened] names it, which is what closing it records. [expandAll] overrides the lot, which is what
 * a filter needs: the folders left in a filtered tree are the ones holding a match, and a match
 * hidden in a closed folder would look like a filter that does not work.
 *
 * [opened] holds **paths**, not nodes. The tree is rebuilt from scratch whenever the filter or the
 * replica changes, so anything keyed by node identity is thrown away on every keystroke — which is
 * what used to reset every folder and restart every animation while typing.
 */
fun Folder.visibleRows(
    opened: Set<String>,
    expandAll: Boolean = false,
    /**
     * Notes with conflict copies, as `NoteLibrary.conflicts` holds them. Each copy is drawn under
     * its note rather than wherever its name sorts, so the two read as one problem. A copy whose
     * note is not in the folder — filtered out, say — stays where it is.
     */
    conflicts: Map<String, List<String>> = emptyMap(),
): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()

    fun walk(folder: Folder, depth: Int) {
        // The root's entry in [opened] is a toggle like any other, flipped the other way round.
        val expanded = expandAll || (folder.path in opened) != (depth == 0)
        rows += TreeRow(folder, depth, expanded)
        if (!expanded) return
        val files = folder.children.filterIsInstance<File>().associateBy { it.path }
        val grouped = folder.children
            .filter { it is File && it.path in conflicts }
            .flatMap { conflicts.getValue(it.path) }
            .filter { it in files }
            .toSet()
        for (child in folder.children) {
            when (child) {
                is Folder -> walk(child, depth + 1)
                is File -> {
                    if (child.path in grouped) continue
                    rows += TreeRow(child, depth + 1, expanded = false)
                    for (copy in conflicts[child.path].orEmpty()) {
                        val node = files[copy] ?: continue
                        rows += TreeRow(node, depth + 2, expanded = false, copyOf = child.path)
                    }
                }
                else -> Unit
            }
        }
    }

    walk(this, depth = 0)
    return rows
}
