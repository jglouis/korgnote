package com.korgnote.common

import com.korgnote.common.org.edit.FileKeywords
import com.korgnote.common.org.edit.fileKeywordsOf
import com.korgnote.common.org.edit.withFileKeywords
import com.korgnote.common.org.search.fileTagsOf
import kotlin.test.Test
import kotlin.test.assertEquals

class FileKeywordsTest {

    private val note = """
        :PROPERTIES:
        :ID:       abc
        :END:
        #+TITLE: Agenda
        #+DATE: [2024-03-22 Fri]
        #+TODO: TODO | DONE

        * TODO Something
        #+TITLE: not the file's
    """.trimIndent() + "\n"

    @Test
    fun theKeywordsAreReadFromAboveTheFirstHeading() {
        assertEquals(FileKeywords(title = "Agenda", date = "[2024-03-22 Fri]"), fileKeywordsOf(note))
        assertEquals(listOf("a", "b"), fileKeywordsOf("#+filetags: :a:b:\n").tags)
    }

    @Test
    fun changedInPlaceAddedAfterTheOthersAndRemovedWhenBlank() {
        val edited = withFileKeywords(
            note,
            FileKeywords(title = "Agenda 2026", author = "Me", date = "", tags = listOf("work", "ecam")),
        )
        assertEquals(
            """
            :PROPERTIES:
            :ID:       abc
            :END:
            #+TITLE: Agenda 2026
            #+AUTHOR: Me
            #+filetags: :work:ecam:
            #+TODO: TODO | DONE

            * TODO Something
            #+TITLE: not the file's
            """.trimIndent() + "\n",
            edited,
        )
    }

    @Test
    fun anUnchangedNoteIsLeftByteForByte() {
        val text = "#+title:   Spaced\n#+FILETAGS: :a:\n\n* H\n"
        assertEquals(text, withFileKeywords(text, fileKeywordsOf(text)))
        assertEquals("#+title: New\n#+FILETAGS: :a:\n\n* H\n", withFileKeywords(text, fileKeywordsOf(text).copy(title = "New")))
    }

    @Test
    fun aNoteWithNoneGetsThemAfterItsIdDrawerOrAtTheTop() {
        assertEquals(
            ":PROPERTIES:\n:ID: x\n:END:\n#+TITLE: T\n* H\n",
            withFileKeywords(":PROPERTIES:\n:ID: x\n:END:\n* H\n", FileKeywords(title = "T")),
        )
        assertEquals("#+TITLE: T\n* H\n", withFileKeywords("* H\n", FileKeywords(title = "T")))
        assertEquals("#+TITLE: T\ntext", withFileKeywords("text", FileKeywords(title = "T")))
    }

    /** Emacs reads an indented keyword, so the form shows it and edits it where it is. */
    @Test
    fun anIndentedKeywordIsReadAndEditedInPlace() {
        val text = "  #+TITLE: Garden\n* H\n"
        assertEquals("Garden", fileKeywordsOf(text).title)
        assertEquals("  #+TITLE: Shed\n* H\n", withFileKeywords(text, fileKeywordsOf(text).copy(title = "Shed")))
        assertEquals(listOf("a"), fileTagsOf("  #+FILETAGS: :a:\n* H\n"))
    }

    /** A blank `#+TITLE:` is blank: the heading under it is no title. */
    @Test
    fun aBlankKeywordIsBlank() {
        assertEquals("", fileKeywordsOf("#+TITLE:\n* Inbox\n").title)
    }

    /** A new line goes after the keywords there, indented or not, not above them. */
    @Test
    fun aNewKeywordGoesAfterAnIndentedOne() {
        assertEquals(
            "  #+STARTUP: logdone\n#+TITLE: Garden\n* H\n",
            withFileKeywords("  #+STARTUP: logdone\n* H\n", com.korgnote.common.org.edit.FileKeywords(title = "Garden")),
        )
    }

    /** Text in a block is the block's: Org reads the note's own keywords around it. */
    @Test
    fun aKeywordInABlockIsNotTheNotes() {
        val text = "#+BEGIN_SRC org\n  #+TITLE: inner\n#+END_SRC\n#+TITLE: real\n"

        assertEquals("real", fileKeywordsOf(text).title)
        assertEquals(
            "#+BEGIN_SRC org\n  #+TITLE: inner\n#+END_SRC\n#+TITLE: new\n",
            withFileKeywords(text, FileKeywords(title = "new")),
        )
    }

    @Test
    fun aNewKeywordIsNotPutInABlock() {
        val text = "#+BEGIN_SRC org\n#+NAME: inner\n#+END_SRC\n* Heading\n"

        assertEquals("#+TITLE: new\n$text", withFileKeywords(text, FileKeywords(title = "new")))
    }
}
