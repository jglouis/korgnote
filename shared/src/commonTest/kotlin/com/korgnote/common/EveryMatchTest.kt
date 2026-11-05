package com.korgnote.common

import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.everyMatch
import kotlin.test.Test
import kotlin.test.assertEquals

class EveryMatchTest {

    private val text = """
        :PROPERTIES:
        :ID:       note-a
        :END:
        #+LINK: gh https://example.invalid/%s
        * Heading [[id:note-b][B]] and [[file:b.org::*Second]] <<here>>
          :id:   lower-case
        [[a link
        over two lines]] and [[]] and [not one]
        <<<radio>>> <2026-11-05 Thu> [2026-11-06 Fri 10:00] ñandú [[Élégant ✓]]
    """.trimIndent()

    private fun assertSameAsFindAll(regex: Regex, text: String) {
        assertEquals(
            regex.findAll(text).map { it.range to it.groupValues }.toList(),
            regex.everyMatch(text).map { it.range to it.groupValues }.toList(),
            regex.pattern,
        )
    }

    @Test
    fun itFindsWhatFindAllFinds() {
        for (regex in listOf(OrgSyntax.link, OrgSyntax.idProperty, OrgSyntax.target, OrgSyntax.timestamp)) {
            assertSameAsFindAll(regex, text)
        }
    }

    /** A group that did not take part reads as empty, as `groupValues` has it; the link's description is one. */
    @Test
    fun aGroupThatDidNotTakePartIsEmpty() {
        val links = OrgSyntax.link.everyMatch("[[a]] [[b][c]]").toList()
        assertEquals(listOf("a", "b"), links.map { it.groupValues[1] })
        assertEquals(listOf("", "c"), links.map { it.groupValues[2] })
    }

    @Test
    fun itCopesWithWhatFindAllCopesWith() {
        assertSameAsFindAll(Regex("x*"), "axxb")
        assertSameAsFindAll(Regex("^"), "")
        assertSameAsFindAll(Regex("(?m)^"), "a\nb\n")
        assertSameAsFindAll(Regex("z"), "no such letter")
        assertSameAsFindAll(Regex("(a)|(b)"), "ab")
    }
}
