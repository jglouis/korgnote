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
)

/**
 * The rows to draw, top to bottom, skipping everything inside a closed folder.
 *
 * Folders are open unless [collapsed] names them, so a tree that has never been touched shows
 * everything — and [expandAll] overrides the lot, which is what a filter needs: a match inside a
 * folder somebody closed last week is still a match, and hiding it would look like the filter was
 * broken.
 *
 * [collapsed] holds **paths**, not nodes. The tree is rebuilt from scratch whenever the filter or
 * the replica changes, so anything keyed by node identity is thrown away on every keystroke —
 * which is what used to re-open every folder and restart every animation while typing.
 */
fun Folder.visibleRows(collapsed: Set<String>, expandAll: Boolean = false): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()

    fun walk(folder: Folder, depth: Int) {
        val expanded = expandAll || folder.path !in collapsed
        rows += TreeRow(folder, depth, expanded)
        if (!expanded) return
        for (child in folder.children) {
            when (child) {
                is Folder -> walk(child, depth + 1)
                is File -> rows += TreeRow(child, depth + 1, expanded = false)
                else -> Unit
            }
        }
    }

    walk(this, depth = 0)
    return rows
}
