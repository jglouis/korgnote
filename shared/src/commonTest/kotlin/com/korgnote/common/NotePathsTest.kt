package com.korgnote.common

import com.korgnote.common.fs.NotePaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotePathsTest {

    @Test
    fun aTitleBecomesALowerCaseUnderscoredFileName() {
        assertEquals("grocery_list.org", NotePaths.fileNameFor("  Grocery list "))
        assertEquals("q3_plan_draft.org", NotePaths.fileNameFor("Q3 plan: draft!"))
        assertEquals("café_notes.org", NotePaths.fileNameFor("Café notes"), "letters are kept, not just ASCII")
        assertEquals("already-hyphenated.org", NotePaths.fileNameFor("already-hyphenated"))
        assertNull(NotePaths.fileNameFor(" ?! "))
    }

    @Test
    fun joiningAFolder() {
        assertEquals("inbox/a.org", NotePaths.join("inbox", "a.org"))
        assertEquals("inbox/a.org", NotePaths.join("/inbox/ ", "a.org"))
        assertEquals("a.org", NotePaths.join("", "a.org"))
    }

    @Test
    fun normalizingIsForgivingAboutTyping() {
        assertEquals("inbox/idea.org", NotePaths.normalize(" /inbox/idea "))
        assertEquals("inbox/idea.org", NotePaths.normalize("inbox / idea.org"))
        assertEquals("a.org", NotePaths.normalize("a.org"))
    }

    @Test
    fun normalizingRefusesWhatTheServerWould() {
        assertNull(NotePaths.normalize(""))
        assertNull(NotePaths.normalize("../escape.org"))
        assertNull(NotePaths.normalize("a//b.org"))
        assertNull(NotePaths.normalize("./a.org"))
        assertNull(NotePaths.normalize("a\\b.org"))
        assertNull(NotePaths.normalize("notes.txt"))
        assertNull(NotePaths.normalize(".org"))
    }
}
