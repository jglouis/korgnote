package com.korgnote.app.viewmodel

import com.korgnote.app.data.InMemorySettingsRepository
import com.korgnote.app.data.NotesSource
import com.korgnote.app.data.PairedServer
import com.korgnote.app.data.chosenNotesSource
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Where the notes are: what is stored, what a device that predates the choice gets, and switching. */
class NotesSourceTest {

    private val paired = PairedServer(
        serverId = "s",
        serverName = "laptop",
        addresses = listOf("192.168.1.2"),
        port = 8443,
        fingerprint = "ab",
        deviceId = "d",
        token = "t",
    )

    @Test
    fun aSourceIsStoredAsTextAndReadBack() {
        assertEquals(NotesSource.Server, NotesSource.decode(NotesSource.Server.encode()))
        val folder = NotesSource.Folder("content://com.android.externalstorage.documents/tree/primary%3ASync")
        assertEquals(folder, NotesSource.decode(folder.encode()))
        assertNull(NotesSource.decode(null))
        assertNull(NotesSource.decode("folder:"))
    }

    @Test
    fun aNewDeviceHasAChoiceToMake() {
        assertNull(InMemorySettingsRepository(initialNotesSource = null).chosenNotesSource(offersFolder = true))
    }

    @Test
    fun aDevicePairedBeforeThereWasAChoiceKeepsTheServer() {
        val settings = InMemorySettingsRepository(initialPairedServer = paired, initialNotesSource = null)
        assertEquals(NotesSource.Server, settings.chosenNotesSource(offersFolder = true))
    }

    @Test
    fun withNoFolderOnOfferTheServerIsAllThereIs() {
        val settings = InMemorySettingsRepository(initialNotesSource = null)
        assertEquals(NotesSource.Server, settings.chosenNotesSource(offersFolder = false))
    }

    @Test
    fun aReplicaWithNotesMeansTheServerWasInUse() = runTest {
        val replica = DocumentStore(InMemoryReplicaStorage(mapOf("inbox.org" to "* Inbox\n")))
        val choice = NotesSourceChoice(InMemorySettingsRepository(initialNotesSource = null), replica, offersFolder = true)
        assertFalse(choice.settled)

        choice.settle()

        assertTrue(choice.settled)
        assertEquals(NotesSource.Server, choice.current)
    }

    @Test
    fun anEmptyReplicaLeavesTheChoiceToTheUser() = runTest {
        val choice = NotesSourceChoice(
            InMemorySettingsRepository(initialNotesSource = null),
            DocumentStore(InMemoryReplicaStorage()),
            offersFolder = true,
        )

        choice.settle()

        assertTrue(choice.settled)
        assertNull(choice.current)
    }

    @Test
    fun choosingAnotherSourceStopsTheOneInUse() {
        val settings = InMemorySettingsRepository()
        val choice = NotesSourceChoice(settings, DocumentStore(InMemoryReplicaStorage()), offersFolder = true)
        val sync = StoppableSync()
        choice.active = sync

        choice.choose(NotesSource.Server)
        assertFalse(sync.stopped)

        choice.choose(NotesSource.Folder("tree"))
        assertTrue(sync.stopped)
        assertEquals(NotesSource.Folder("tree"), settings.getNotesSource())
    }

    private class StoppableSync : NotesSync {
        var stopped = false
        override val unreachable: String? = null
        override val retrying = false
        override val syncing = false
        override val lastSynced: Instant? = null
        override suspend fun load() {}
        override suspend fun retry() {}
        override fun watch() {}
        override fun stop() {
            stopped = true
        }
        override suspend fun push(writeFirst: suspend () -> Unit) = writeFirst()
        override suspend fun pushQuietly() {}
    }
}
