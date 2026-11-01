package com.korgnote.common

import com.korgnote.common.org.edit.toggleableTimestamps
import com.korgnote.common.org.edit.toggledTimestamp
import com.korgnote.common.org.edit.withTimestampToggledAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Making a stamp active or inactive: only its brackets change, and a range changes whole. */
class TimestampToggleTests {

    @Test
    fun onlyTheBracketsChange() {
        assertEquals("[2026-09-23 Wed 10:00 +1w -2d]", toggledTimestamp("<2026-09-23 Wed 10:00 +1w -2d>"))
        assertEquals("<2026-09-23 Wed>", toggledTimestamp("[2026-09-23 Wed]"))
    }

    @Test
    fun aRangeChangesWhole() {
        assertEquals("[2026-12-19 Sat]--[2026-12-26 Sat]", toggledTimestamp("<2026-12-19 Sat>--<2026-12-26 Sat>"))
        assertEquals("<2026-12-19 Sat>--<2026-12-26 Sat>", toggledTimestamp("[2026-12-19 Sat]--[2026-12-26 Sat]"))
    }

    @Test
    fun whatIsNotAStampIsNotToggled() {
        assertNull(toggledTimestamp("[1/3]"))
        assertNull(toggledTimestamp("<2026-02-30 Mon>"))
        assertNull(toggledTimestamp("<2026-09-23 Wed> and more"))
    }

    @Test
    fun theStampAtAnOffsetIsToggledInPlace() {
        val text = "* Call\nMet [2026-09-23 Wed] and <2026-09-30 Wed>--<2026-10-01 Thu>\nafter"
        assertEquals(
            "* Call\nMet <2026-09-23 Wed> and <2026-09-30 Wed>--<2026-10-01 Thu>\nafter",
            text.withTimestampToggledAt(text.indexOf('[')),
        )
        assertEquals(
            "* Call\nMet [2026-09-23 Wed] and [2026-09-30 Wed]--[2026-10-01 Thu]\nafter",
            text.withTimestampToggledAt(text.indexOf('<')),
        )
        // Not where a stamp begins.
        assertNull(text.withTimestampToggledAt(text.indexOf('[') + 1))
        assertNull(text.withTimestampToggledAt(text.length))
    }

    @Test
    fun theTogglableStampsAreTheHeadingsAndTheTexts() {
        val element = """
            * Meeting <2026-09-23 Wed 10:00>
            SCHEDULED: <2026-09-23 Wed> CLOSED: [2026-09-24 Thu]
            :LOGBOOK:
            - State "DONE" from "TODO" [2026-09-24 Thu 10:00]
            :END:
            Notes taken [2026-09-23 Wed 10:30].
            #+BEGIN_SRC sh
            echo <2026-01-01 Thu>
            #+END_SRC
            - Away <2026-12-19 Sat>--<2026-12-26 Sat>
        """.trimIndent()
        val found = element.toggleableTimestamps().map { element.substring(it.range) }
        assertEquals(
            listOf("<2026-09-23 Wed 10:00>", "[2026-09-23 Wed 10:30]", "<2026-12-19 Sat>--<2026-12-26 Sat>"),
            found,
        )
    }

    @Test
    fun beforeTheFirstHeadingAFileLevelDrawerIsADrawer() {
        val preamble = ":PROPERTIES:\n:CREATED: [2026-09-01 Tue]\n:END:\n#+TITLE: Notes\nStarted [2026-09-02 Wed]"
        assertEquals(listOf("[2026-09-02 Wed]"), preamble.toggleableTimestamps().map { preamble.substring(it.range) })
    }
}
