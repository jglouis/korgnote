package com.korgnote.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** What reading mode shows for entities, `_{…}`/`^{…}` scripts and `<<targets>>`. */
class OrgEntitiesTest {
    private fun read(text: String) = buildOrgInlineString(text).text

    @Test
    fun entitiesReadAsTheirCharacters() {
        assertEquals("α → β, 5 × 3 ≤ 20°", read("\\alpha \\to \\beta, 5 \\times 3 \\leq 20\\deg"))
        assertEquals("α-test", read("\\alpha{}-test"))
    }

    @Test
    fun whatIsNoEntityStaysAsTyped() {
        // A longer word, an unknown name, a line break's backslashes.
        assertEquals("\\alphabet \\foo a\\\\b", read("\\alphabet \\foo a\\\\b"))
        // Verbatim is literal.
        assertEquals("\\alpha", read("=\\alpha="))
    }

    @Test
    fun editingShowsEntitiesAsTyped() {
        assertEquals("\\alpha", buildOrgInlineString("\\alpha", viewMode = false).text)
    }

    @Test
    fun bracedScriptsLoseTheirMarkup() {
        assertEquals("x2 and Hij", read("x^{2} and H_{ij}"))
        // The bare forms are left alone, so snake_case stays a word.
        assertEquals("snake_case x^2", read("snake_case x^2"))
        // After a space it is no script.
        assertEquals("a _{b}", read("a _{b}"))
    }

    @Test
    fun targetsReadAsTheirWords() {
        assertEquals("See here and Term.", read("See <<here>> and <<<Term>>>."))
        assertEquals("a <<b>>> c", read("a <<b>>> c"))
    }

    @Test
    fun emphasisStillWorks() {
        assertEquals("bold under", read("*bold* _under_"))
    }
}
