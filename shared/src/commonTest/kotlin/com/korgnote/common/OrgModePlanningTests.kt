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
        // The planning lines and the body now belong to the heading they were written under,
        // which is what lets the agenda read a task's dates off the task itself.
        assertEquals(1, doc.children.size)
        val sec = assertIs<OrgSection>(doc.children[0]); assertEquals("Task A", sec.title)
        assertEquals(3, sec.children.size)
        val s1 = assertIs<OrgPlanning>(sec.children[0]); assertEquals("SCHEDULED", s1.planType); assertEquals("<2025-09-10 Wed 12:00>", s1.timestamp)
        val raw = assertIs<OrgRaw>(sec.children[1]); assertEquals("Some text", raw.content)
        val d1 = assertIs<OrgPlanning>(sec.children[2]); assertEquals("DEADLINE", d1.planType); assertEquals("<2025-09-15 Mon>", d1.timestamp)
    }

    @Test
    fun toOrgStringEmitsPlanningLines() {
        val nodes: List<OrgNode> = listOf(
            OrgSection(title = "X", todo = null),
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
