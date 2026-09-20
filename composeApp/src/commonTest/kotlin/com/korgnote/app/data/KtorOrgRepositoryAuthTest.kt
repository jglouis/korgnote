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
 * The client half of T2: the configured token has to actually reach the wire, on every request.
 */
class KtorOrgRepositoryAuthTest {

    private val sentAuthorization = mutableListOf<String?>()

    private fun repositoryWith(token: String): KtorOrgRepository {
        val engine = MockEngine { request ->
            sentAuthorization.add(request.headers[HttpHeaders.Authorization])
            respond(
                content = "{}",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        return KtorOrgRepository(client, { "http://example.test:8080" }, { token })
    }

    @Test
    fun aConfiguredTokenIsSentAsABearerHeader() = runTest {
        repositoryWith("s3cret").fetchAllContents()
        assertEquals("Bearer s3cret", sentAuthorization.single())
    }

    @Test
    fun noHeaderIsSentWhenNoTokenIsConfigured() = runTest {
        repositoryWith("").fetchAllContents()
        assertNull(sentAuthorization.single(), "an empty token must not become 'Bearer '")
    }

    @Test
    fun whitespaceIsNotAToken() = runTest {
        repositoryWith("   ").fetchAllContents()
        assertNull(sentAuthorization.single())
    }

    @Test
    fun everyRequestCarriesTheToken() = runTest {
        val repository = repositoryWith("s3cret")
        repository.fetchAllContents()
        repository.fetchRawContent("notes.org")
        repository.saveContent("notes.org", "* hi\n")

        assertEquals(3, sentAuthorization.size)
        assertEquals(List<String?>(3) { "Bearer s3cret" }, sentAuthorization.toList())
    }
}
