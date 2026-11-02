package com.korgnote.app.capture

import com.korgnote.app.data.NoteTemplateSettings
import com.korgnote.common.org.capture.SharedContent
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapLinksTest {

    private val place = "https://www.google.com/maps/place/Eiffel+Tower,+Av.+Gustave+Eiffel,+75007+Paris,+France/data=!4m2"
    private val shared = SharedContent(subject = "Eiffel Tower", text = "https://maps.app.goo.gl/ne1k1Zy6Prd6t9QC8")
    private val asked = mutableListOf<String>()

    private fun links(location: String? = place, wait: Long = 0, fail: Boolean = false, timeout: Long = 3_000) = MapLinks(
        HttpClient(MockEngine { request ->
            asked += request.url.toString()
            delay(wait)
            if (fail) error("offline")
            respond("", HttpStatusCode.Found, location?.let { headersOf(HttpHeaders.Location, it) } ?: headersOf())
        }) { followRedirects = false },
        timeout,
    )

    @Test
    fun aShortLinkIsLookedUpForWhereItLeads() = runBlocking {
        assertEquals(shared.copy(expanded = place), links().expanded(shared))
        assertEquals(listOf("https://maps.app.goo.gl/ne1k1Zy6Prd6t9QC8"), asked)
    }

    @Test
    fun nothingElseGoesAnywhere() = runBlocking {
        val links = links()
        for (other in listOf(
            SharedContent(text = "https://orgmode.org", subject = "Org"),
            SharedContent(text = "https://www.google.com/maps/place/Tour+Eiffel/@48.85,2.29,17z"),
            SharedContent(text = "Call back after five"),
            shared.copy(expanded = place),
        )) {
            assertEquals(other, links.expanded(other))
        }
        assertTrue(asked.isEmpty())
    }

    @Test
    fun withoutAnAnswerInTimeThePlaceIsAsShared() = runBlocking {
        assertEquals(shared, links(fail = true).expanded(shared))
        assertEquals(shared, links(wait = 5_000, timeout = 50).expanded(shared))
        assertEquals(shared, links(location = null).expanded(shared))
        // A redirect somewhere other than Google Maps' place page is not believed.
        assertEquals(shared, links(location = "https://example.com/maps/place/Elsewhere").expanded(shared))
    }

    @Test
    fun aCaptureQueuedByTheShareExtensionIsLookedUpWhenFiled() = runBlocking {
        val queue = CaptureQueue(InMemoryCaptureQueueFolder())
        val store = DocumentStore(InMemoryReplicaStorage(mapOf("inbox.org" to "#+TITLE: Inbox\n")))
        val filer = CaptureFiler(queue, { "" }, { NoteTemplateSettings() }, expand = links()::expanded)
        queue.add(CaptureRequest(id = "a", at = 1, shared = shared))

        filer.fileWaiting(store)
        assertEquals(
            "#+TITLE: Inbox\n* Eiffel Tower\n:PROPERTIES:\n:LOCATION: Av. Gustave Eiffel, 75007 Paris, France\n:END:\n",
            store.read("inbox.org"),
        )
    }
}
