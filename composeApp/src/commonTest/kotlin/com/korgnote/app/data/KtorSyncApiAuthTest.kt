package com.korgnote.app.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The paired token has to reach the wire on every sync request, and a paired computer that is not
 * at one of its addresses is looked for at the others.
 */
class KtorSyncApiAuthTest {

    private val sent = mutableListOf<String?>()
    private val hosts = mutableListOf<String>()

    private fun apiWith(vararg endpoints: Endpoint, unreachable: Set<String> = emptySet()): KtorSyncApi {
        val engine = MockEngine { request ->
            hosts.add(request.url.host)
            if (request.url.host in unreachable) throw RuntimeException("no route to ${request.url.host}")
            sent.add(request.headers[HttpHeaders.Authorization])
            respond(
                content = """{"generation":1,"complete":true,"entries":[],"deleted":[]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        return KtorSyncApi(client) { endpoints.toList() }
    }

    @Test
    fun thePairedTokenIsSentAsABearerHeader() = runTest {
        apiWith(Endpoint("https://192.168.1.20:8443", "s3cret")).manifest()
        assertEquals("Bearer s3cret", sent.single())
    }

    @Test
    fun theServerOnThisComputerGetsNoHeader() = runTest {
        apiWith(Endpoint("http://localhost:8080", null)).manifest()
        assertNull(sent.single())
    }

    @Test
    fun everyRequestCarriesTheToken() = runTest {
        val api = apiWith(Endpoint("https://192.168.1.20:8443", "s3cret"))
        api.manifest()
        api.fetch("notes.org")
        api.push("notes.org", "* hi\n", ifMatch = null)

        assertEquals(List<String?>(3) { "Bearer s3cret" }, sent.toList())
    }

    @Test
    fun anAddressThatCannotBeReachedGivesWayToTheNext() = runTest {
        val api = apiWith(
            Endpoint("https://10.0.0.5:8443", "s3cret"),
            Endpoint("https://192.168.1.20:8443", "s3cret"),
            unreachable = setOf("10.0.0.5"),
        )
        api.manifest()
        api.manifest()

        assertEquals(listOf("10.0.0.5", "192.168.1.20", "192.168.1.20"), hosts, "the one that answered is tried first after")
    }

    @Test
    fun aRefusalSaysTheDeviceIsNotPairedAndTriesNoOtherAddress() = runTest {
        val engine = MockEngine { request ->
            hosts.add(request.url.host)
            respond("This device is not paired, or was revoked.", HttpStatusCode.Unauthorized)
        }
        val api = KtorSyncApi(HttpClient(engine) { install(ContentNegotiation) { json() } }) {
            listOf(Endpoint("https://192.168.1.20:8443", "old"), Endpoint("https://10.0.0.5:8443", "old"))
        }
        val error = assertFailsWith<NotPairedException> { api.manifest() }
        assertEquals("This device is not paired, or was revoked.", error.message)
        assertEquals(listOf("192.168.1.20"), hosts)
    }

    @Test
    fun whenNoneAnswersTheLastProblemIsReported() = runTest {
        val api = apiWith(Endpoint("https://10.0.0.5:8443", "t"), unreachable = setOf("10.0.0.5"))
        val error = assertFailsWith<RuntimeException> { api.manifest() }
        assertEquals("no route to 10.0.0.5", error.message)
    }
}
