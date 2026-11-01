package com.korgnote.app.viewmodel

import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** Reloading the notes after a sync, which usually brings one of them. */
class NoteLibraryTest {

    @Test
    fun aReloadParsesOnlyTheNotesThatChanged() = runTest {
        val store = DocumentStore(InMemoryReplicaStorage(mapOf("a.org" to "* A\n", "b.org" to "* B\n")))
        val library = NoteLibrary(store)
        val here = StandardTestDispatcher(testScheduler)
        library.reload(parseOn = here)
        val a = library["a.org"]
        val b = library["b.org"]

        store.save("b.org", "* B, edited\n")
        library.reload(parseOn = here)

        assertSame(a, library["a.org"])
        assertNotSame(b, library["b.org"])
        assertEquals("* B, edited\n", library["b.org"]?.text)
    }

    /** A note's `#+TODO:` is its own: one that declares none has Org's `TODO` and `DONE`. */
    @Test
    fun aNoteThatDeclaresNoKeywordsHasOrgsOwn() = runTest {
        val store = DocumentStore(
            InMemoryReplicaStorage(
                mapOf("a.org" to "#+TODO: EVENT | FINISHED\n* FINISHED Party\n", "b.org" to "* FINISHED Party\n* DONE Dishes\n"),
            ),
        )
        val library = NoteLibrary(store)
        library.reload(parseOn = StandardTestDispatcher(testScheduler))

        assertEquals("FINISHED", assertIs<OrgSection>(library["a.org"]?.children?.get(1)).todo)
        assertEquals(null, library.headingOf("b.org").todo)
        assertEquals("FINISHED Party", library.headingOf("b.org").title)
        assertEquals("DONE", assertIs<OrgSection>(library["b.org"]?.children?.get(1)).todo)
    }

    /** Declaring a keyword in one note, by a save, changes nothing in the others. */
    @Test
    fun aSaveThatDeclaresAKeywordLeavesTheOtherNotesAlone() = runTest {
        val store = DocumentStore(InMemoryReplicaStorage(mapOf("a.org" to "* A\n", "b.org" to "* SHIPPED Release\n")))
        val library = NoteLibrary(store)
        library.reload(parseOn = StandardTestDispatcher(testScheduler))
        val before = library["b.org"]

        library.put("a.org", "#+TODO: NEXT | SHIPPED\n* A\n".toOrgDocument(library.fallbackKeywords))

        assertSame(before, library["b.org"])
        assertEquals("SHIPPED Release", library.headingOf("b.org").title)
    }

    private fun NoteLibrary.headingOf(path: String): OrgSection = assertIs<OrgSection>(get(path)?.children?.first())
}
