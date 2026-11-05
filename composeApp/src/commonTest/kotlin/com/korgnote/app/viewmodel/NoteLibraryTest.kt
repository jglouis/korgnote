package com.korgnote.app.viewmodel

import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** Reloading the notes after a sync, which usually brings one of them. */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteLibraryTest {

    @Test
    fun aReloadParsesOnlyTheNotesThatChanged() = runTest {
        val store = DocumentStore(InMemoryReplicaStorage(mapOf("a.org" to "* A\n", "b.org" to "* B\n")))
        val library = NoteLibrary(store)
        val here = StandardTestDispatcher(testScheduler)
        library.reload(parseOn = here)
        val a = library.document("a.org")
        val b = library.document("b.org")

        store.save("b.org", "* B, edited\n")
        library.reload(parseOn = here)

        assertSame(a, library.document("a.org"))
        assertNotSame(b, library.document("b.org"))
        assertEquals("* B, edited\n", library.text("b.org"))
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

        assertEquals("FINISHED", assertIs<OrgSection>(library.document("a.org")?.children?.get(1)).todo)
        assertEquals(null, library.headingOf("b.org").todo)
        assertEquals("FINISHED Party", library.headingOf("b.org").title)
        assertEquals("DONE", assertIs<OrgSection>(library.document("b.org")?.children?.get(1)).todo)
    }

    /** Declaring a keyword in one note, by a save, changes nothing in the others. */
    @Test
    fun aSaveThatDeclaresAKeywordLeavesTheOtherNotesAlone() = runTest {
        val store = DocumentStore(InMemoryReplicaStorage(mapOf("a.org" to "* A\n", "b.org" to "* SHIPPED Release\n")))
        val library = NoteLibrary(store)
        library.reload(parseOn = StandardTestDispatcher(testScheduler))
        val before = library.document("b.org")

        library.put("a.org", "#+TODO: NEXT | SHIPPED\n* A\n".toOrgDocument())

        assertSame(before, library.document("b.org"))
        assertEquals("SHIPPED Release", library.headingOf("b.org").title)
    }

    @Test
    fun aReloadOfOneNoteReadsOnlyThatNote() = runTest {
        val storage = StallingReplicaStorage(InMemoryReplicaStorage(mapOf("a.org" to "* A\n", "b.org" to "* B\n")))
        val store = DocumentStore(storage)
        val library = NoteLibrary(store)
        val here = StandardTestDispatcher(testScheduler)
        library.reload(parseOn = here)
        val a = library.document("a.org")
        store.save("b.org", "* B, edited\n")
        store.save("c.org", "* C\n")
        storage.read.clear()

        library.reload(parseOn = here, only = setOf("b.org"))

        assertEquals(setOf("b.org", "c.org"), storage.read.toSet(), "the one asked for, and one not read yet")
        assertSame(a, library.document("a.org"))
        assertEquals("* B, edited\n", library.text("b.org"))
        assertEquals("* C\n", library.text("c.org"))
    }

    @Test
    fun aReloadOfOneNoteDropsANoteGoneFromTheReplica() = runTest {
        val store = DocumentStore(InMemoryReplicaStorage(mapOf("a.org" to "* A\n", "b.org" to "* B\n")))
        val library = NoteLibrary(store)
        val here = StandardTestDispatcher(testScheduler)
        library.reload(parseOn = here)
        store.removeLocally("b.org")

        library.reload(parseOn = here, only = setOf("b.org"))

        assertEquals(setOf("a.org"), library.paths)
    }

    /** A tick lands while a reload is reading: the text read just before it must not undo it. */
    @Test
    fun aNoteChangedDuringAReloadKeepsTheChange() = runTest {
        val storage = StallingReplicaStorage(InMemoryReplicaStorage(mapOf("a.org" to "- [ ] one\n")))
        val library = NoteLibrary(DocumentStore(storage))
        val here = StandardTestDispatcher(testScheduler)
        library.reload(parseOn = here)

        val stalled = storage.stallRead("a.org")
        launch { library.reload(parseOn = here) }
        runCurrent()
        library.put("a.org", "- [X] one\n".toOrgDocument())
        stalled.complete(Unit)
        advanceUntilIdle()

        assertEquals("- [X] one\n", library.text("a.org"))
    }

    @Test
    fun aChangeDuringAReloadDoesNotOutliveTheReloadAfterIt() = runTest {
        val storage = StallingReplicaStorage(InMemoryReplicaStorage(mapOf("a.org" to "- [ ] one\n")))
        val store = DocumentStore(storage)
        val library = NoteLibrary(store)
        val here = StandardTestDispatcher(testScheduler)
        library.reload(parseOn = here)

        val stalled = storage.stallRead("a.org")
        launch { library.reload(parseOn = here) }
        runCurrent()
        library.put("a.org", "- [X] one\n".toOrgDocument())
        stalled.complete(Unit)
        advanceUntilIdle()

        store.save("a.org", "- [X] one, changed in Emacs\n")
        library.reload(parseOn = here)

        assertEquals("- [X] one, changed in Emacs\n", library.text("a.org"))
    }

    /** The reload that started first holds the older text, and must not be the one that lands last. */
    @Test
    fun overlappingReloadsLeaveTheNewestText() = runTest {
        val storage = StallingReplicaStorage(InMemoryReplicaStorage(mapOf("a.org" to "* Old\n")))
        val store = DocumentStore(storage)
        val library = NoteLibrary(store)
        val here = StandardTestDispatcher(testScheduler)
        library.reload(parseOn = here)

        val stalled = storage.stallRead("a.org")
        launch { library.reload(parseOn = here) }
        runCurrent()
        store.save("a.org", "* New\n")
        launch { library.reload(parseOn = here) }
        runCurrent()
        stalled.complete(Unit)
        advanceUntilIdle()

        assertEquals("* New\n", library.text("a.org"))
    }

    private suspend fun NoteLibrary.headingOf(path: String): OrgSection = assertIs<OrgSection>(document(path)?.children?.first())
}
