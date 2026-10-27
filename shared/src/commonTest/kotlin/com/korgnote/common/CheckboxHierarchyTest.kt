package com.korgnote.common

import com.korgnote.common.org.OrgCheckboxItem
import com.korgnote.common.org.edit.allNodes
import com.korgnote.common.org.edit.tickAsOrgDoes
import com.korgnote.common.org.toOrgDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ticking a box as Org's `C-c C-c` does: a box with boxes under it says what they say. */
class CheckboxHierarchyTest {

    private val list = """
        * Trip
        - [ ] Pack
          - [ ] Socks
          - [ ] Boots
            - [ ] Laces
            - [ ] Insoles
        - [ ] Book the train
        Trailing text.
    """.trimIndent() + "\n"

    /** [text] after a tap on the box whose text is [what]. */
    private fun tick(text: String, what: String): String {
        val document = text.toOrgDocument()
        val box = document.allNodes().filterIsInstance<OrgCheckboxItem>().single { it.text == what }
        return document.tickAsOrgDoes(box).text
    }

    @Test
    fun tickingSomeChildrenMakesTheParentPartial() {
        val ticked = tick(list, "Socks")
        assertEquals(list.replace("- [ ] Pack", "- [-] Pack").replace("- [ ] Socks", "- [X] Socks"), ticked)
    }

    @Test
    fun tickingTheLastChildTicksEveryBoxAbove() {
        var text = tick(list, "Socks")
        text = tick(text, "Laces")
        assertTrue(text.contains("  - [-] Boots\n"), "one of two under Boots")
        text = tick(text, "Insoles")
        assertEquals(
            list.replace("[ ] Pack", "[X] Pack").replace("[ ] Socks", "[X] Socks").replace("[ ] Boots", "[X] Boots")
                .replace("[ ] Laces", "[X] Laces").replace("[ ] Insoles", "[X] Insoles"),
            text,
        )
        val unticked = tick(text, "Laces")
        assertTrue(unticked.contains("- [-] Pack\n") && unticked.contains("  - [-] Boots\n"), "unticking goes back up too")
    }

    @Test
    fun tickingAParentTicksEverythingUnderItAndUntickingClearsIt() {
        val ticked = tick(tick(list, "Socks"), "Pack")
        assertEquals(list.replace("[ ]", "[X]").replace("[X] Book the train", "[ ] Book the train"), ticked)
        assertEquals(list, tick(ticked, "Pack"))
    }

    @Test
    fun aPartialParentIsTickedByATap() {
        val partial = tick(list, "Socks")
        assertTrue(tick(partial, "Pack").contains("- [X] Pack\n  - [X] Socks\n  - [X] Boots\n"))
    }

    @Test
    fun aBoxWithoutChildrenOnlyFlipsItself() {
        assertEquals(list.replace("- [ ] Book the train", "- [X] Book the train"), tick(list, "Book the train"))
    }

    @Test
    fun aPartialBoxIsReadAndWrittenAsADash() {
        val box = "- [-] Pack\n".toOrgDocument().allNodes().filterIsInstance<OrgCheckboxItem>().single()
        assertTrue(box.partial && !box.checked)
    }
}
