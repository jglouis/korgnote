package com.korgnote.app.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The configured token has to reach the wire on every sync request, not just the first.
 */
class KtorSyncApiAuthTest {

    private val sent = mutableListOf<String?>()

    private fun apiWith(token: String): KtorSyncApi {
        val engine = MockEngine { request ->
            sent.add(request.headers[HttpHeaders.Authorization])
            respond(
                content = """{"generation":1,"complete":true,"entries":[],"deleted":[]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        return KtorSyncApi(client, { "http://example.test:8080" }, { token })
    }

    @Test
    fun aConfiguredTokenIsSentAsABearerHeader() = runTest {
        apiWith("s3cret").manifest()
        assertEquals("Bearer s3cret", sent.single())
    }

    @Test
    fun noHeaderIsSentWhenNoTokenIsConfigured() = runTest {
        apiWith("").manifest()
        assertNull(sent.single(), "an empty token must not become 'Bearer '")
    }

    @Test
    fun everyRequestCarriesTheToken() = runTest {
        val api = apiWith("s3cret")
        api.manifest()
        api.fetch("notes.org")
        api.push("notes.org", "* hi\n", ifMatch = null)

        assertEquals(List<String?>(3) { "Bearer s3cret" }, sent.toList())
    }
}
