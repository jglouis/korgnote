package com.korgnote.common

import com.korgnote.common.org.OrgNode
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.toOrgDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Nesting (T4): a heading owns its body and its sub-headings.
 *
 * The point of the shape is that a heading's [OrgNode.range] covers its whole subtree, so promoting,
 * moving or deleting a heading with everything under it is a single text replacement.
 */
class OrgNestingTests {

    private val document = """
        #+TITLE: Nesting

        * TODO Parent                      :work:
        SCHEDULED: <2026-09-21 Mon>
        Parent body.

        ** DONE Child
        Child body.

        *** Grandchild
        - [ ] an item

        ** Second child
        More.

        * Another top level
        Tail.
    """.trimIndent() + "\n"

    private val tree = document.toOrgDocument()

    private fun sectionTitled(title: String): OrgSection =
        tree.walk().filterIsInstance<OrgSection>().first { it.title == title }

    @Test
    fun headingsNestByLevel() {
        val top = tree.children.filterIsInstance<OrgSection>()
        assertEquals(listOf("Parent", "Another top level"), top.map { it.title })

        val parent = sectionTitled("Parent")
        assertEquals(listOf("Child", "Second child"), parent.subsections.map { it.title })
        assertEquals(listOf("Grandchild"), sectionTitled("Child").subsections.map { it.title })
    }

    @Test
    fun aHeadingOwnsItsOwnBody() {
        val parent = sectionTitled("Parent")
        assertEquals("Parent body.", parent.bodyText())
        // Its planning line belongs to it, not to the document.
        assertTrue(parent.children.any { it is com.korgnote.common.org.OrgPlanning })
    }

    /** The property the whole change exists for. */
    @Test
    fun aHeadingsRangeCoversItsWholeSubtree() {
        val parent = sectionTitled("Parent")
        val source = document.substring(parent.range)

        assertTrue(source.startsWith("* TODO Parent"))
        assertTrue(source.contains("*** Grandchild"), "a subtree must include its deepest heading")
        assertTrue(source.contains("** Second child"))
        assertTrue(!source.contains("Another top level"), "a subtree must stop at the next peer")
    }

    @Test
    fun deletingASubtreeIsOneTextReplacement() {
        val child = sectionTitled("Child")
        val without = document.removeRange(child.range)

        // The child and everything under it are gone; its siblings and parent are untouched.
        assertTrue(!without.contains("Child body."))
        assertTrue(!without.contains("Grandchild"))
        assertTrue(without.contains("* TODO Parent"))
        assertTrue(without.contains("** Second child"))
        assertTrue(without.contains("Another top level"))
    }

    @Test
    fun everyChildRangeSitsInsideItsParent() {
        fun check(node: OrgNode) {
            for (child in node.children) {
                if (node.range.isEmpty() || child.range.isEmpty()) continue
                assertTrue(
                    child.range.first >= node.range.first && child.range.last <= node.range.last,
                    "child ${child.range} escapes parent ${node.range}",
                )
                check(child)
            }
        }
        check(tree)
    }

    /**
     * I3, in the form that holds today: every line carrying content is attributed unambiguously —
     * the nodes whose range contains it form a single ancestor chain, so it has exactly one deepest
     * owner and no two unrelated nodes both claim it.
     *
     * Not the literal "exactly one leaf" of `docs/04-org-core.md`: a heading line is owned by its
     * section, which is not a leaf once it has children, and blank lines are owned by no node at
     * all. Both need nodes the grammar does not have yet — a heading-line node and whitespace nodes
     * — which is T5's work. This is the strongest form that is true now, and it is what a renderer
     * or highlighter actually needs.
     */
    @Test
    fun everyNonBlankLineHasExactlyOneDeepestOwner() {
        val nodes = tree.walk().filterNot { it.range.isEmpty() }

        var offset = 0
        for (line in document.split('\n')) {
            if (line.isNotBlank()) {
                val owners = nodes.filter { offset >= it.range.first && offset <= it.range.last }
                assertTrue(owners.isNotEmpty(), "line <$line> is owned by nothing")
                // Owners must nest: sorted innermost first, each one sits inside the next.
                val sorted = owners.sortedBy { it.range.last - it.range.first }
                for ((inner, outer) in sorted.zipWithNext()) {
                    assertTrue(
                        inner.range.first >= outer.range.first && inner.range.last <= outer.range.last,
                        "line <$line> is claimed by two unrelated nodes: ${inner.range} and ${outer.range}",
                    )
                }
            }
            offset += line.length + 1
        }
    }
}
