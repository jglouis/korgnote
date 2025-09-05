package com.korgnote.common

import com.korgnote.common.org.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OrgModePlanningTests {
    @Test
    fun parsesScheduledAndDeadlineLines() {
        val text = """
            * TODO Task A
            SCHEDULED: <2025-09-10 Wed 12:00>
            Some text
            DEADLINE: <2025-09-15 Mon>
        """.trimIndent()
        val doc = text.toOrgDocument()
        // Expected: section, planning, raw, planning
        assertEquals(4, doc.children.size)
        val sec = assertIs<OrgSection>(doc.children[0]); assertEquals("Task A", sec.title)
        val s1 = assertIs<OrgPlanning>(doc.children[1]); assertEquals("SCHEDULED", s1.planType); assertEquals("<2025-09-10 Wed 12:00>", s1.timestamp)
        val raw = assertIs<OrgRaw>(doc.children[2]); assertEquals("Some text", raw.content)
        val d1 = assertIs<OrgPlanning>(doc.children[3]); assertEquals("DEADLINE", d1.planType); assertEquals("<2025-09-15 Mon>", d1.timestamp)
    }

    @Test
    fun toOrgStringEmitsPlanningLines() {
        val nodes: List<OrgNode> = listOf(
            OrgSection(title = "X", content = "", todo = null),
            OrgPlanning(planType = "SCHEDULED", timestamp = "<2025-09-10 Wed>"),
            OrgPlanning(planType = "DEADLINE", timestamp = "<2025-09-15 Mon>")
        )
        val doc = OrgDocument(children = nodes, metadata = emptyList())
        val s = doc.toOrgString()
        val lines = s.split('\n')
        // Expect 3 lines: heading, scheduled, deadline
        assertEquals(3, lines.size)
        assertEquals("* X", lines[0])
        assertEquals("SCHEDULED: <2025-09-10 Wed>", lines[1])
        assertEquals("DEADLINE: <2025-09-15 Mon>", lines[2])
    }
}
