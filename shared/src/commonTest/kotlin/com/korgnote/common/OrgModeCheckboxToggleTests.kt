package com.korgnote.common

import com.korgnote.common.org.toggleNthCheckbox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OrgModeCheckboxToggleTests {
    @Test
    fun togglesOnlyTheTargetedCheckboxLine() {
        val text = """
            * TODO Task A

            - [ ] First
            - [x] Second
            - [ ] Third
        """.trimIndent()

        val toggledFirst = text.toggleNthCheckbox(0, checked = true)
        assertEquals(
            """
            * TODO Task A

            - [x] First
            - [x] Second
            - [ ] Third
            """.trimIndent(),
            toggledFirst
        )

        val toggledSecond = text.toggleNthCheckbox(1, checked = false)
        assertEquals(
            """
            * TODO Task A

            - [ ] First
            - [ ] Second
            - [ ] Third
            """.trimIndent(),
            toggledSecond
        )

        val toggledThird = text.toggleNthCheckbox(2, checked = true)
        assertEquals(
            """
            * TODO Task A

            - [ ] First
            - [x] Second
            - [x] Third
            """.trimIndent(),
            toggledThird
        )
    }

    @Test
    fun preservesUnrelatedWhitespaceExactly() {
        // Trailing spaces on the heading line and a double blank line before the list must survive
        // untouched - only the toggled marker itself should change.
        val text = "* TODO Task A  \n\n\n- [ ] First\n- [ ] Second  \n"

        val result = text.toggleNthCheckbox(1, checked = true)

        assertEquals("* TODO Task A  \n\n\n- [ ] First\n- [x] Second  \n", result)
    }

    @Test
    fun returnsNullForOutOfRangeOrdinal() {
        val text = """
            * TODO Task A
            - [ ] Only one
        """.trimIndent()

        assertNull(text.toggleNthCheckbox(1, checked = true))
        assertNull(text.toggleNthCheckbox(-1, checked = true))
    }

    @Test
    fun plainBulletLinesDoNotConsumeACheckboxOrdinal() {
        // Only "- [ ]"/"- [x]" bullets count as checkboxes; a plain "- Not a checkbox" item in
        // between must be skipped when numbering, matching how the parser builds OrgCheckboxItem.
        val text = """
            - [ ] First
            - Not a checkbox
            - [ ] Second
        """.trimIndent()

        assertEquals(
            """
            - [ ] First
            - Not a checkbox
            - [x] Second
            """.trimIndent(),
            text.toggleNthCheckbox(1, checked = true)
        )
    }
}
