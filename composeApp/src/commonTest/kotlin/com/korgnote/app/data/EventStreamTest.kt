package com.korgnote.app.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * The change stream as the client reads it: events, and the server's heartbeats, whose absence is
 * how a connection that died without a word is told from one with nothing to say.
 *
 * On real time, a fraction of a second at most: the body is fed by hand through the mock engine.
 */
class EventStreamTest {

    private val body = ByteChannel(autoFlush = true)

    private val api = KtorSyncApi(
        HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream")) }) {
            install(HttpTimeout)
        },
        endpoints = { listOf(Endpoint("https://192.168.1.20:8443", "t0ken")) },
        silenceLimit = 200.milliseconds,
    )

    @Test
    fun eventsAreDeliveredBetweenHeartbeats() = runTest {
        body.writeStringUtf8(": connected\n\n: ping\n\n")
        body.writeStringUtf8("event: changed\ndata: {\"generation\":2,\"path\":\"inbox.org\",\"etag\":\"e\"}\n\n")
        body.writeStringUtf8("event: deleted\ndata: {\"generation\":3,\"path\":\"old.org\"}\n\n")
        body.close()
        val seen = mutableListOf<Pair<String, Boolean>>()

        withContext(Dispatchers.Default) { api.events { path, deleted -> seen += path to deleted } }

        assertEquals(listOf("inbox.org" to false, "old.org" to true), seen)
    }

    @Test
    fun aStreamThatStopsBeatingIsTakenForDead() = runTest {
        body.writeStringUtf8(": connected\n\n: ping\n\n")

        withContext(Dispatchers.Default) {
            assertFailsWith<StreamLostException> { api.events { _, _ -> } }
        }
    }

    /**
     * Not taken for an address the computer is not at: a new stream at its other address would carry
     * on as if nothing had been missed, and what changed while this one was dead would never come in.
     */
    @Test
    fun aLostStreamIsReportedRatherThanReopenedAtAnotherAddress() = runTest {
        var requests = 0
        val twoAddresses = KtorSyncApi(
            HttpClient(MockEngine {
                requests++
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }) { install(HttpTimeout) },
            silenceLimit = 200.milliseconds,
            endpoints = { listOf(Endpoint("https://192.168.1.20:8443", "t0ken"), Endpoint("https://10.0.0.5:8443", "t0ken")) },
        )
        body.writeStringUtf8(": connected\n\n: ping\n\n")

        withContext(Dispatchers.Default) {
            assertFailsWith<StreamLostException> { twoAddresses.events { _, _ -> } }
        }

        assertEquals(1, requests)
    }

    @Test
    fun aServerThatSendsNoHeartbeatsIsWaitedOnAsBefore() = runTest {
        // An older server: silence from it says nothing about the connection.
        body.writeStringUtf8(": connected\n\n")

        val ended = withContext(Dispatchers.Default) {
            withTimeoutOrNull(600.milliseconds) { api.events { _, _ -> } }
        }

        assertNull(ended, "still reading, well past the silence limit")
    }
}
