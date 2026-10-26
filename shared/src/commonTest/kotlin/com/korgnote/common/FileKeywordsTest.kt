package com.korgnote.common

import com.korgnote.common.org.edit.FileKeywords
import com.korgnote.common.org.edit.fileKeywordsOf
import com.korgnote.common.org.edit.withFileKeywords
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
}
