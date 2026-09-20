package com.korgnote.common

import com.korgnote.common.org.OrgCheckboxItem
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgKeyword
import com.korgnote.common.org.OrgList
import com.korgnote.common.org.OrgNode
import com.korgnote.common.org.OrgPlanning
import com.korgnote.common.org.OrgRaw
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgTable
import com.korgnote.common.org.OrgTableRow
import com.korgnote.common.org.toOrgDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The model is only lossless if a document keeps its own source and every node knows where it came
 * from. These are the invariants that make editing a text replacement instead of a re-render — I1
 * and I2 in `docs/04-org-core.md`.
 */
class OrgSourceRangeTests {

    private val corpus = listOf(
        "",
        "\n",
        "\n\n\n",
        "plain text with no org at all",
        "* TODO A task\n",
        "* TODO A task",
        "#+TITLE: Notes\n#+TODO: TODO NEXT | DONE\n\n* TODO First\nSCHEDULED: <2026-09-20 Sun>\nBody line.\n",
        "* Parent\n** Child\n*** Grandchild\nbody\n",
        "* With a list\n- [ ] one\n- [x] two\n- plain item\n",
        "* With a table\n| a | b |\n|---+---|\n| 1 | 2 |\n",
        "Paragraph one.\nStill paragraph one.\n\nParagraph two.\n",
        "* Trailing whitespace   \nbody   \n\n\n",
        "  indented text that is not a heading\n* but this is\n",
        "* Tagged                    :work:home:\n",
        "#+BEGIN_SRC kotlin\nval x = 1\n#+END_SRC\n",
        """
        |#+TITLE: Everything at once
        |#+TODO: TODO NEXT | DONE
        |
        |Intro paragraph before any heading.
        |
        |* TODO Plan the week                        :work:
        |SCHEDULED: <2026-09-21 Mon 09:00>
        |DEADLINE: <2026-09-25 Fri>
        |Some body text.
        |Still the same paragraph.
        |
        |- [ ] unchecked
        |- [x] checked
        |
        |** DONE A child heading
        || col | col |
        ||-----+-----|
        ||  1  |  2  |
        |
        |* Another top-level heading
        |
        |Trailing paragraph.
        |
        """.trimMargin(),
    )

    private fun everyNode(node: OrgNode): List<OrgNode> = buildList {
        add(node)
        when (node) {
            is OrgDocument -> node.children.forEach { addAll(everyNode(it)) }
            is OrgTable -> node.rows.forEach { addAll(everyNode(it)) }
            // Cells deliberately carry no range yet; see parseTable.
            is OrgTableRow -> Unit
            else -> Unit
        }
    }

    /** I1: parsing keeps the source, exactly. */
    @Test
    fun aParsedDocumentKeepsItsSource() {
        for (text in corpus) {
            assertEquals(text, text.toOrgDocument().text, "source not preserved for:\n$text")
        }
    }

    /** I2: a node's range picks out that node's source. */
    @Test
    fun everyNodeRangeIsInBounds() {
        for (text in corpus) {
            val document = text.toOrgDocument()
            for (node in everyNode(document)) {
                if (node.range.isEmpty()) continue
                assertTrue(
                    node.range.first >= 0 && node.range.last < text.length,
                    "range ${node.range} out of bounds for ${node::class.simpleName} in:\n$text",
                )
            }
        }
    }

    /**
     * I2, the part that actually bites: the slice a range picks out has to be the node's own source,
     * not merely a legal offset pair. Checked against the text each node type carries.
     */
    @Test
    fun everyNodeRangeSelectsItsOwnText() {
        for (text in corpus) {
            val document = text.toOrgDocument()
            for (node in everyNode(document)) {
                if (node.range.isEmpty()) continue
                val slice = text.substring(node.range)
                val mustContain = when (node) {
                    is OrgSection -> node.title
                    is OrgKeyword -> node.content
                    is OrgPlanning -> node.timestamp
                    is OrgCheckboxItem -> node.text
                    is OrgRaw -> node.content.substringBefore('\n')
                    else -> null
                }
                if (mustContain.isNullOrBlank()) continue
                assertTrue(
                    slice.contains(mustContain),
                    "${node::class.simpleName} range ${node.range} selects <$slice>, " +
                        "which does not contain its own <$mustContain>, in:\n$text",
                )
            }
        }
    }

    /** A child cannot claim source outside the node that owns it. */
    @Test
    fun childRangesSitInsideTheirParent() {
        for (text in corpus) {
            val document = text.toOrgDocument()
            for (child in document.children) {
                if (child.range.isEmpty()) continue
                assertTrue(
                    child.range.first >= 0 && child.range.last <= text.lastIndex,
                    "child range ${child.range} escapes the document in:\n$text",
                )
                if (child is OrgTable) {
                    for (row in child.rows) {
                        if (row.range.isEmpty()) continue
                        assertTrue(
                            row.range.first >= child.range.first && row.range.last <= child.range.last,
                            "row range ${row.range} escapes table range ${child.range} in:\n$text",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun aNodesSourceContainsItsOwnContent() {
        val text = "#+TITLE: Notes\n\n* TODO First task\nBody line.\n\n* DONE Second\n"
        val document = text.toOrgDocument()

        val slices = document.children.map { text.substring(it.range) }
        assertTrue(slices.any { it.startsWith("#+TITLE:") }, "keyword slice missing in $slices")
        assertTrue(slices.any { it.startsWith("* TODO First task") }, "first heading slice missing in $slices")
        assertTrue(slices.any { it.startsWith("* DONE Second") }, "second heading slice missing in $slices")
        assertTrue(
            slices.first { it.startsWith("* TODO First task") }.contains("Body line."),
            "a section's range should cover its body",
        )
    }

    @Test
    fun topLevelNodesDoNotOverlap() {
        for (text in corpus) {
            val ranges = text.toOrgDocument().children.map { it.range }.filterNot { it.isEmpty() }
            for ((earlier, later) in ranges.zipWithNext()) {
                assertTrue(
                    earlier.last < later.first,
                    "ranges $earlier and $later overlap in:\n$text",
                )
            }
        }
    }

    @Test
    fun listItemsPointAtTheirOwnLines() {
        val text = "- [ ] first\n- [x] second\n- third\n"
        val list = text.toOrgDocument().children.single()
        val items = (list as OrgList).children

        assertEquals(listOf("- [ ] first\n", "- [x] second\n", "- third\n"), items.map { text.substring(it.range) })
    }

    @Test
    fun aHandBuiltNodeHasNoRange() {
        // Nothing can claim to point into a document it never came from.
        assertTrue(OrgRaw("built by hand").range.isEmpty())
        assertEquals("", OrgDocument(emptyList(), emptyList()).text)
    }
}
