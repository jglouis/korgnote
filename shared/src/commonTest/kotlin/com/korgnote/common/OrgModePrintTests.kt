package com.korgnote.common

import com.korgnote.common.org.*
import kotlin.test.Test
import kotlin.test.assertEquals

class OrgModePrintTests {
    @Test
    fun printsParagraphRoundTrip() {
        val input = """
            First line
            second line
        """.trimIndent()
        val doc = input.toOrgDocument()
        val printed = doc.toOrgString()
        // Single paragraph expected
        assertEquals("First line\nsecond line", printed)
    }

    @Test
    fun printsKeywords() {
        val input = """
            #+TITLE: Test
            #+AUTHOR: Me
        """.trimIndent()
        val doc = input.toOrgDocument()
        assertEquals(input, doc.toOrgString())
    }

    @Test
    fun printsSimpleListAndCheckboxes() {
        val input = """
            - [ ] Task one
            - [x] Task two
            - plain item
        """.trimIndent()
        val doc = input.toOrgDocument()
        val expected = """
            - [ ] Task one
            - [x] Task two
            - plain item
        """.trimIndent()
        assertEquals(expected, doc.toOrgString())
    }

    @Test
    fun printsSectionWithTodoTokenAndContent() {
        // Using ** will create OrgSection in current parser
        val input = """
            ** DONE Ship it
            Body line 1
            Body line 2
        """.trimIndent()
        val doc = input.toOrgDocument()
        val printed = doc.toOrgString()
        // Heading level is preserved in output
        val expected = """
            ** DONE Ship it
            Body line 1
            Body line 2
        """.trimIndent()
        assertEquals(expected, printed)
    }

    @Test
    fun printsTableSimplified() {
        val input = """
            | A | B |
            | 1 | 2 |
        """.trimIndent()
        val doc = input.toOrgDocument()
        val printed = doc.toOrgString()
        val expected = """
            | A | B |
            | 1 | 2 |
        """.trimIndent()
        assertEquals(expected, printed)
    }

    @Test
    fun printsTableAlignedWithRules() {
        val input = """
            | Name | Qty |
            |-
            | Apples | 10 |
        """.trimIndent()
        val expected = """
            | Name   | Qty |
            |--------+-----|
            | Apples |  10 |
        """.trimIndent()
        assertEquals(expected, input.toOrgDocument().toOrgString())
    }

    @Test
    fun printsHeadingWithLevelAndTags() {
        val input = "*** TODO Call mom :Family:Phone:\nBody"
        val doc = input.toOrgDocument()
        val sec = doc.children[0] as OrgSection
        assertEquals(3, sec.level)
        assertEquals("Call mom", sec.title)
        assertEquals(listOf("Family", "Phone"), sec.tags)
        assertEquals(input, doc.toOrgString())
    }

    @Test
    fun printsMixedContent() {
        val input = """
            #+TITLE: Mixed
            Intro line
            
            - a
            - [x] b
            
            ** TODO Task
            Work
        """.trimIndent()
        val doc = input.toOrgDocument()
        val expected = """
            #+TITLE: Mixed
            Intro line
            - a
            - [x] b
            ** TODO Task
            Work
        """.trimIndent()
        assertEquals(expected, doc.toOrgString())
    }
}
