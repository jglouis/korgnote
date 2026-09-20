package com.korgnote.common

import com.korgnote.common.org.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OrgModeTodoDoneTests {
    @Test
    fun parsesTodoAndDoneOnHeadings() {
        val text = """
            * TODO Write parser
            Some content under todo
            
            * DONE Ship feature
            Completed details
        """.trimIndent()
        val doc = text.toOrgDocument()
        // Two headings at the same level stay siblings; each owns the body beneath it.
        assertEquals(2, doc.children.size)
        val sec1 = assertIs<OrgSection>(doc.children[0]); assertEquals("Write parser", sec1.title); assertEquals("TODO", sec1.todo); assertEquals("Some content under todo", sec1.bodyText())
        val sec2 = assertIs<OrgSection>(doc.children[1]); assertEquals("Ship feature", sec2.title); assertEquals("DONE", sec2.todo); assertEquals("Completed details", sec2.bodyText())
    }

    @Test
    fun sectionParserExtractsTodoAndTitle() {
        // Directly test parseSection behavior by providing only a heading line followed by a non-heading to stop.
        val text = """
            * TODO Title one
            Body
            ** DONE Sub heading
            Sub body
        """.trimIndent()
        val doc = text.toOrgDocument()
        // The deeper heading nests under the first one rather than following it as a sibling.
        assertEquals(1, doc.children.size)
        val sec1 = assertIs<OrgSection>(doc.children[0]); assertEquals("Title one", sec1.title); assertEquals("TODO", sec1.todo); assertEquals("Body", sec1.bodyText())
        val sec2 = sec1.subsections.single(); assertEquals("Sub heading", sec2.title); assertEquals("DONE", sec2.todo); assertEquals("Sub body", sec2.bodyText())
    }

    @Test
    fun todoTokensInListItemsAreNotSpecial() {
        val text = """
            - TODO just a list item
            - DONE another item
        """.trimIndent()
        val doc = text.toOrgDocument()
        assertEquals(1, doc.children.size)
        val list = assertIs<OrgList>(doc.children[0])
        assertEquals('-', list.bulletType)
        assertEquals("TODO just a list item", (assertIs<OrgRaw>(list.children[0])).content)
        assertEquals("DONE another item", (assertIs<OrgRaw>(list.children[1])).content)
    }
}
