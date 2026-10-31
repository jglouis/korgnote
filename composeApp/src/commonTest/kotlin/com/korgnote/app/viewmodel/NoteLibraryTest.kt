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

    /** A note that declares no keywords reads its headings with those the other notes declare. */
    @Test
    fun aNoteThatDeclaresNoKeywordsUsesTheOthers() = runTest {
        val store = DocumentStore(
            InMemoryReplicaStorage(mapOf("a.org" to "#+TODO: EVENT | FINISHED\n", "b.org" to "* FINISHED Party\n")),
        )
        val library = NoteLibrary(store)
        library.reload(parseOn = StandardTestDispatcher(testScheduler))

        assertEquals("FINISHED", library.headingOf("b.org").todo)
        assertEquals("Party", library.headingOf("b.org").title)
    }

    /** A keyword declared in one note, by a save, is a keyword in the notes that rely on the others. */
    @Test
    fun aSaveThatDeclaresAKeywordRereadsTheNotesThatRelyOnIt() = runTest {
        val store = DocumentStore(InMemoryReplicaStorage(mapOf("a.org" to "* A\n", "b.org" to "* SHIPPED Release\n")))
        val library = NoteLibrary(store)
        library.reload(parseOn = StandardTestDispatcher(testScheduler))
        assertEquals("SHIPPED Release", library.headingOf("b.org").title)

        library.put("a.org", "#+TODO: NEXT | SHIPPED\n* A\n".toOrgDocument(library.corpusKeywords))

        assertEquals("SHIPPED", library.headingOf("b.org").todo)
        assertEquals("Release", library.headingOf("b.org").title)
    }

    private fun NoteLibrary.headingOf(path: String): OrgSection = assertIs<OrgSection>(get(path)?.children?.first())
}
