package com.korgnote.common

import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgNode
import com.korgnote.common.org.OrgTable
import com.korgnote.common.org.toOrgDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The source-mapping invariants, run over the bundled sample documents rather than over cases
 * written to suit the parser.
 *
 * [OrgSourceRangeTests] covers the awkward edges and runs on every platform; this covers realistic
 * documents, which is where a hand-picked corpus tends to be too kind. The invariants are pure
 * string arithmetic, so proving them on one platform is enough.
 */
class OrgFixtureRoundTripTest {

    private val fixtures: List<File> =
        File("../server/src/main/resources/org").listFiles()
            ?.filter { it.isFile && it.extension == "org" }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun everyNode(node: OrgNode): List<OrgNode> = buildList {
        add(node)
        when (node) {
            is OrgDocument -> node.children.forEach { addAll(everyNode(it)) }
            is OrgTable -> node.rows.forEach { addAll(everyNode(it)) }
            else -> Unit
        }
    }

    @Test
    fun thereAreFixturesToTest() {
        assertTrue(fixtures.size >= 10, "expected the bundled samples, found ${fixtures.size}")
    }

    /** I1: parsing a real document keeps its bytes exactly. */
    @Test
    fun everyFixtureRoundTrips() {
        for (file in fixtures) {
            val text = file.readText()
            assertEquals(text, text.toOrgDocument().text, "source not preserved for ${file.name}")
        }
    }

    /** I2: in a real document too, a node's range selects that node's source. */
    @Test
    fun everyNodeInEveryFixturePointsAtItsOwnSource() {
        for (file in fixtures) {
            val text = file.readText()
            for (node in everyNode(text.toOrgDocument())) {
                if (node.range.isEmpty()) continue
                assertTrue(
                    node.range.first >= 0 && node.range.last < text.length,
                    "${node::class.simpleName} range ${node.range} out of bounds in ${file.name}",
                )
            }
        }
    }

    /** Top-level nodes tile the document in order, without overlapping. */
    @Test
    fun topLevelRangesDoNotOverlapInAnyFixture() {
        for (file in fixtures) {
            val text = file.readText()
            val ranges = text.toOrgDocument().children.map { it.range }.filterNot { it.isEmpty() }
            for ((earlier, later) in ranges.zipWithNext()) {
                assertTrue(
                    earlier.last < later.first,
                    "ranges $earlier and $later overlap in ${file.name}",
                )
            }
        }
    }
}
