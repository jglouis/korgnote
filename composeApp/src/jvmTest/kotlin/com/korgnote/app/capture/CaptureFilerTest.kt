package com.korgnote.app.capture

import com.korgnote.app.data.NoteTemplateSettings
import com.korgnote.common.org.capture.HeadingRef
import com.korgnote.common.org.capture.SharedContent
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureFilerTest {

    private val zone = UtcOffset(hours = 1).asTimeZone()
    private val capturedAt = LocalDateTime(2026, 10, 31, 9, 30).toInstant(zone)
    private val filedAt = LocalDateTime(2026, 11, 2, 18, 0).toInstant(zone)

    private fun filer(queue: CaptureQueue) = CaptureFiler(
        queue,
        newNotesFolder = { "Org/Agenda" },
        noteTemplate = { NoteTemplateSettings(author = "Me", extraHeader = "") },
        zone = { zone },
        now = { filedAt },
        newId = { "id-1" },
    )

    private fun request(id: String, text: String, keyword: String? = null, entry: String? = null) =
        CaptureRequest(id = id, at = capturedAt.toEpochMilliseconds(), shared = SharedContent(text = text), keyword = keyword, entry = entry)

    @Test
    fun aFirstCaptureMakesTheInboxHeadedAsANewNote() = runBlocking {
        val queue = CaptureQueue(InMemoryCaptureQueueFolder())
        val store = DocumentStore(InMemoryReplicaStorage())
        queue.add(request("a", "Call the plumber", keyword = "TODO"))

        assertEquals(setOf("Org/Agenda/inbox.org"), filer(queue).fileWaiting(store))
        assertEquals(
            """
            :PROPERTIES:
            :ID:       id-1
            :END:
            #+TITLE: Inbox
            #+AUTHOR: Me
            #+DATE: [2026-11-02 Mon]

            * TODO Call the plumber

            """.trimIndent(),
            store.read("Org/Agenda/inbox.org"),
        )
        assertTrue(queue.waiting().isEmpty())
        assertTrue(store.isDirty("Org/Agenda/inbox.org"))
    }

    @Test
    fun capturesAreFiledInTheOrderTakenAsTheSheetLeftThem() = runBlocking {
        val queue = CaptureQueue(InMemoryCaptureQueueFolder())
        val store = DocumentStore(InMemoryReplicaStorage(mapOf("reading.org" to "#+TITLE: Reading\n* To read\n** Old book\n* Read\n")))
        val under = HeadingRef("reading.org", title = "To read")
        queue.add(request("b", "Second", entry = "* TODO Second, as edited\n").copy(at = capturedAt.toEpochMilliseconds() + 1, under = under))
        queue.add(request("a", "First", entry = "* First\n").copy(under = under))

        filer(queue).fileWaiting(store)
        assertEquals("#+TITLE: Reading\n* To read\n** Old book\n** First\n** TODO Second, as edited\n* Read\n", store.read("reading.org"))
    }

    @Test
    fun theKeywordsOfferedAreTheDestinationsOwnOrANewNotes() = runBlocking {
        val store = DocumentStore(InMemoryReplicaStorage(mapOf("work.org" to "#+TODO: NEXT WAIT | DONE\n* x\n", "plain.org" to "* y\n")))
        val filer = CaptureFiler(CaptureQueue(InMemoryCaptureQueueFolder()), { "" }, { NoteTemplateSettings(extraHeader = "#+TODO: TODO EVENT | DONE") })
        assertEquals(listOf("NEXT", "WAIT"), filer.keywordsFor(store, "work.org").todo.toList())
        assertEquals(listOf("TODO"), filer.keywordsFor(store, "plain.org").todo.toList())
        assertEquals(listOf("TODO", "EVENT"), filer.keywordsFor(store, "new.org").todo.toList())
    }

    @Test
    fun aPlaceGoesIntoTheHeadingChosenOrIntoTheInboxWhenThatHeadingIsGone() = runBlocking {
        val queue = CaptureQueue(InMemoryCaptureQueueFolder())
        val store = DocumentStore(InMemoryReplicaStorage(mapOf("trips.org" to "* Paris\nBook the hotel.\n")))
        val place = SharedContent(text = "https://maps.app.goo.gl/x", subject = "Eiffel Tower")
        queue.add(CaptureRequest("a", capturedAt.toEpochMilliseconds(), place, into = HeadingRef("trips.org", title = "Paris")))
        queue.add(CaptureRequest("b", capturedAt.toEpochMilliseconds() + 1, place, into = HeadingRef("trips.org", title = "Rome")))

        assertEquals(setOf("trips.org", "Org/Agenda/inbox.org"), filer(queue).fileWaiting(store))
        assertEquals(
            "* Paris\n:PROPERTIES:\n:LOCATION: Eiffel Tower\n:END:\nBook the hotel.\n",
            store.read("trips.org"),
        )
        assertTrue(store.read("Org/Agenda/inbox.org")!!.endsWith("* Eiffel Tower\n:PROPERTIES:\n:LOCATION: Eiffel Tower\n:END:\n"))
        assertTrue(queue.waiting().isEmpty())
    }

    @Test
    fun aNewEntryGoesToTheNoteOrHeadingChosenAndANewNoteIsMadeForIt() = runBlocking {
        val queue = CaptureQueue(InMemoryCaptureQueueFolder())
        val store = DocumentStore(InMemoryReplicaStorage(mapOf("trips.org" to "* Paris\n** Louvre\n* Rome\n")))
        val text = SharedContent(text = "Pack the umbrella")
        val at = capturedAt.toEpochMilliseconds()
        queue.add(CaptureRequest("a", at, text, under = HeadingRef("trips.org", title = "Paris"), body = "And the boots"))
        queue.add(CaptureRequest("b", at + 1, text, keyword = "TODO", file = "Org/Agenda/packing.org", fileTitle = "Packing list"))

        filer(queue).fileWaiting(store)
        assertEquals(
            "* Paris\n** Louvre\n** Pack the umbrella\nAnd the boots\n* Rome\n",
            store.read("trips.org"),
        )
        val packing = store.read("Org/Agenda/packing.org")!!
        assertTrue(packing.contains("#+TITLE: Packing list\n"))
        assertTrue(packing.endsWith("* TODO Pack the umbrella\n"))
    }

    @Test
    fun aRequestTheSwiftExtensionWroteIsReadBack() {
        val written = """{"id":"F00D","at":1793435400000,"template":"Note","shared":{"url":"https://orgmode.org","subject":"Org"},"note":"read later"}"""
        val request = CaptureRequest.decode(written)
        // A template, as an extension built before there were none still writes, is read past.
        assertEquals(CaptureRequest("F00D", 1793435400000, SharedContent(subject = "Org", url = "https://orgmode.org"), note = "read later"), request)
        assertEquals(null, CaptureRequest.decode("{\"id\":"))
    }
}
