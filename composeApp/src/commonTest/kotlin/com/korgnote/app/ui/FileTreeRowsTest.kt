package com.korgnote.app.ui

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Flattening the file tree into the rows the drawer draws.
 *
 * Worth testing on its own because it is what replaced a recursive composable: the nesting used to
 * be expressed by composition, where nothing could look at it, and is now a list anyone can assert
 * against — including the rule that matters for the reader, that a closed folder hides its subtree
 * rather than merely hiding itself.
 */
class FileTreeRowsTest {

    private val tree = Folder(
        name = "Documents",
        path = "",
        children = listOf(
            Folder(
                name = "journal",
                path = "journal",
                children = listOf(
                    Folder("2025", "journal/2025", listOf(File("2025-01-01.org", "journal/2025/2025-01-01.org"))),
                    File("index.org", "journal/index.org"),
                ),
            ),
            File("inbox.org", "inbox.org"),
        ),
    )

    @Test
    fun everythingIsOpenUntilSomethingIsClosed() {
        val rows = tree.visibleRows(collapsed = emptySet())

        assertContentEquals(
            listOf("", "journal", "journal/2025", "journal/2025/2025-01-01.org", "journal/index.org", "inbox.org"),
            rows.map { it.node.path },
        )
        assertContentEquals(listOf(0, 1, 2, 3, 2, 1), rows.map { it.depth })
    }

    @Test
    fun aClosedFolderHidesItsWholeSubtree() {
        val rows = tree.visibleRows(collapsed = setOf("journal"))

        assertContentEquals(listOf("", "journal", "inbox.org"), rows.map { it.node.path })
        // The folder itself still shows, and says it is closed.
        assertEquals(false, rows.single { it.node.path == "journal" }.expanded)
    }

    @Test
    fun closingAFolderDeeperDownLeavesItsAncestorsAlone() {
        val rows = tree.visibleRows(collapsed = setOf("journal/2025"))

        assertContentEquals(
            listOf("", "journal", "journal/2025", "journal/index.org", "inbox.org"),
            rows.map { it.node.path },
        )
    }

    @Test
    fun closingTheRootLeavesOnlyTheRoot() {
        val rows = tree.visibleRows(collapsed = setOf(""))

        assertContentEquals(listOf(""), rows.map { it.node.path })
    }

    /** What the filter passes: a match inside a folder somebody closed is still a match. */
    @Test
    fun expandAllOverridesWhatWasClosed() {
        val rows = tree.visibleRows(collapsed = setOf("", "journal", "journal/2025"), expandAll = true)

        assertEquals(6, rows.size)
        assertTrue(rows.filter { it.node is Folder }.all { it.expanded })
    }

    /** Every row is a lazy-list key, so two rows sharing one would drop a note from the drawer. */
    @Test
    fun rowPathsAreUnique() {
        val paths = tree.visibleRows(collapsed = emptySet()).map { it.node.path }

        assertEquals(paths.size, paths.toSet().size)
    }

    @Test
    fun filesAreNeverReportedAsOpen() {
        val rows = tree.visibleRows(collapsed = emptySet())

        assertTrue(rows.filter { it.node is File }.none { it.expanded })
    }
}
