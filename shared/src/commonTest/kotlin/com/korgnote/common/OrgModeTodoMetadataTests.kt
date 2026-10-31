package com.korgnote.common

import com.korgnote.common.org.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class OrgModeTodoMetadataTests {
    @Test
    fun parsesTodoConfigFromMetadataExamples() {
        val text = """
            #+TODO: TODO(t) | DONE(d)
            #+TODO: REPORT(r) BUG(b) KNOWNCAUSE(k) | FIXED(f)
            #+TODO: | CANCELED(c)
            
            ** TODO A regular todo
            ** REPORT File a report
            ** BUG Fix it
            ** KNOWNCAUSE Known reason
            ** FIXED Solved
            ** CANCELED No need
        """.trimIndent()
        val doc = text.toOrgDocument()
        // The first two lines are keywords, then another keyword, then sections (since they are **)
        // Build config from metadata
        val cfg = doc.todoConfig()
        assertTrue("TODO" in cfg.todo)
        assertTrue("REPORT" in cfg.todo)
        assertTrue("BUG" in cfg.todo)
        assertTrue("KNOWNCAUSE" in cfg.todo)
        assertTrue("DONE" in cfg.done)
        assertTrue("FIXED" in cfg.done)
        assertTrue("CANCELED" in cfg.done)

        // Verify that sections capture the token and classify using the config
        // Children: three OrgKeyword, then six OrgSection
        assertEquals(9, doc.children.size)
        // Start from index 3
        val s1 = assertIs<OrgSection>(doc.children[3]); assertEquals("A regular todo", s1.title); assertEquals("TODO", s1.todo); assertTrue(s1.isTodo(doc)); assertFalse(s1.isDone(doc))
        val s2 = assertIs<OrgSection>(doc.children[4]); assertEquals("File a report", s2.title); assertEquals("REPORT", s2.todo); assertTrue(s2.isTodo(doc)); assertFalse(s2.isDone(doc))
        val s3 = assertIs<OrgSection>(doc.children[5]); assertEquals("Fix it", s3.title); assertEquals("BUG", s3.todo); assertTrue(s3.isTodo(doc)); assertFalse(s3.isDone(doc))
        val s4 = assertIs<OrgSection>(doc.children[6]); assertEquals("Known reason", s4.title); assertEquals("KNOWNCAUSE", s4.todo); assertTrue(s4.isTodo(doc)); assertFalse(s4.isDone(doc))
        val s5 = assertIs<OrgSection>(doc.children[7]); assertEquals("Solved", s5.title); assertEquals("FIXED", s5.todo); assertFalse(s5.isTodo(doc)); assertTrue(s5.isDone(doc))
        val s6 = assertIs<OrgSection>(doc.children[8]); assertEquals("No need", s6.title); assertEquals("CANCELED", s6.todo); assertFalse(s6.isTodo(doc)); assertTrue(s6.isDone(doc))
    }

    @Test
    fun aLineWithoutABarMakesOnlyItsLastWordDone() {
        assertEquals(
            OrgTodoConfig(todo = setOf("TODO", "NEXT"), done = setOf("DONE")),
            parseTodoConfigFrom(listOf("#+TODO: TODO NEXT DONE")),
        )
        assertEquals(OrgTodoConfig(todo = emptySet(), done = setOf("DONE")), parseTodoConfigFrom(listOf("#+TODO: DONE")))
    }

    @Test
    fun theSeqAndTypSpellingsDeclareKeywordsToo() {
        val text = "#+SEQ_TODO: NEXT(n) | SHIPPED(s)\n#+TYP_TODO: FRED SARA | DONE\n* SHIPPED Release\n"
        assertEquals(
            OrgTodoConfig(todo = setOf("NEXT", "FRED", "SARA"), done = setOf("SHIPPED", "DONE")),
            todoKeywordsOf(text, OrgTodoConfig(setOf("TODO"), setOf("DONE"))),
        )
        val doc = text.toOrgDocument()
        assertTrue(assertIs<OrgSection>(doc.children.last()).isDone(doc))
    }

    @Test
    fun eachLineIsItsOwnSequence() {
        assertEquals(
            listOf(
                OrgTodoConfig(todo = setOf("TODO", "WAIT"), done = setOf("DONE", "CANCELED")),
                OrgTodoConfig(todo = setOf("EVENT"), done = setOf("FINISHED")),
            ),
            todoSequencesOf(
                listOf(
                    "#+TITLE: Not a sequence",
                    "#+TODO: TODO(t) WAIT(w@/!) | DONE(d!) CANCELED(c@/!)",
                    "#+todo: EVENT(e) | FINISHED(f)",
                    "#+TODO:",
                ),
            ),
        )
    }

    /** A bar is a word of its own, as in Org: `TODO|DONE` is one word, and no heading's keyword. */
    @Test
    fun onlyAWordOfItsOwnIsABar() {
        assertEquals(OrgTodoConfig(todo = setOf("TODO"), done = setOf("DONE")), parseTodoConfigFrom(listOf("#+TODO: TODO | DONE")))
        assertEquals(OrgTodoConfig(todo = emptySet(), done = emptySet()), parseTodoConfigFrom(listOf("#+TODO: TODO|DONE")))
    }
}
