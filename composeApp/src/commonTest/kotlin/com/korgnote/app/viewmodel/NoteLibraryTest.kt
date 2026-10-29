package com.korgnote.app.viewmodel

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
