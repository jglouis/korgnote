package com.korgnote.common

import com.korgnote.common.sync.ChunkChoice
import com.korgnote.common.sync.ConflictChunk
import com.korgnote.common.sync.conflictChunks
import com.korgnote.common.sync.resolveConflict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConflictChunksTest {

    private val note = """
        #+TITLE: Notes
        * TODO First
        body one
        * Second
        body two
        * Third
        body three

    """.trimIndent()

    // Changed the first heading, and the third's body; the note and the copy differ twice.
    private val copy = note
        .replace("* TODO First", "* DONE First")
        .replace("body three", "body three, longer\nand a new line")

    @Test
    fun eachRunOfChangedLinesIsAChunkUnderItsHeading() {
        assertEquals(
            listOf(
                ConflictChunk(
                    heading = "* TODO First",
                    before = listOf("#+TITLE: Notes"),
                    note = listOf("* TODO First"),
                    copy = listOf("* DONE First"),
                    after = listOf("body one", "* Second"),
                ),
                ConflictChunk(
                    heading = "* Third",
                    before = listOf("body two", "* Third"),
                    note = listOf("body three"),
                    copy = listOf("body three, longer", "and a new line"),
                    after = listOf(""),
                ),
            ),
            conflictChunks(note, copy),
        )
    }

    @Test
    fun identicalVersionsHaveNoChunks() {
        assertEquals(emptyList(), conflictChunks(note, note))
        assertEquals(note, resolveConflict(note, note, emptyList()))
    }

    @Test
    fun everyChunkOneWayGivesThatVersionBack() {
        assertEquals(note, resolveConflict(note, copy, listOf(ChunkChoice.Note, ChunkChoice.Note)))
        assertEquals(copy, resolveConflict(note, copy, listOf(ChunkChoice.Copy, ChunkChoice.Copy)))
    }

    @Test
    fun chunksAreSettledEachOnItsOwn() {
        assertEquals(
            note.replace("* TODO First", "* DONE First"),
            resolveConflict(note, copy, listOf(ChunkChoice.Copy, ChunkChoice.Note)),
        )
    }

    @Test
    fun bothKeepsTheNotesLinesThenTheCopys() {
        assertEquals(
            note.replace("body three", "body three\nbody three, longer\nand a new line"),
            resolveConflict(note, copy, listOf(ChunkChoice.Note, ChunkChoice.Both)),
        )
    }

    @Test
    fun anEditedChunkTakesWhatWasWritten() {
        assertEquals(
            note.replace("* TODO First", "* WAITING First\n:LOGBOOK:\n:END:"),
            resolveConflict(note, copy, listOf(ChunkChoice.Edited("* WAITING First\n:LOGBOOK:\n:END:"), ChunkChoice.Note)),
        )
    }

    @Test
    fun anEmptyEditDropsTheChunk() {
        assertEquals(
            note.replace("body three\n", ""),
            resolveConflict(note, copy, listOf(ChunkChoice.Note, ChunkChoice.Edited(""))),
        )
    }

    @Test
    fun linesOnlyOneSideHasAreAChunkWithNothingOnTheOther() {
        val longer = "$note* Fourth\n"
        val chunk = conflictChunks(note, longer).single()
        assertEquals(emptyList(), chunk.note)
        assertEquals(listOf("* Fourth"), chunk.copy)
        assertEquals(longer, resolveConflict(note, longer, listOf(ChunkChoice.Copy)))
        assertEquals(note, resolveConflict(note, longer, listOf(ChunkChoice.Note)))
    }

    @Test
    fun aChunkAboveTheFirstHeadingHasNoHeading() {
        val retitled = note.replace("#+TITLE: Notes", "#+TITLE: Other")
        assertEquals(null, conflictChunks(note, retitled).single().heading)
    }

    @Test
    fun aRemovedHeadingStillNamesItsChunk() {
        val withoutSecond = note.replace("* Second\nbody two\n", "")
        assertEquals("* Second", conflictChunks(note, withoutSecond).single().heading)
    }

    @Test
    fun contextReachingBackAcrossAHeadingDoesNotChangeTheHeading() {
        // The change is the first line under * Ideas; its context starts in * Work.
        val first = "* Work\n** TODO Call Alice\n\n* Ideas\nSome idea.\n"
        val second = first.replace("Some idea.", "Some idea, edited.")
        assertEquals("* Ideas", conflictChunks(first, second).single().heading)
    }

    @Test
    fun theChoicesMustMatchTheChunks() {
        assertFailsWith<IllegalArgumentException> { resolveConflict(note, copy, listOf(ChunkChoice.Note)) }
    }
}
