package com.korgnote.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Transport hygiene (T2): what the server will answer, and to whom.
 */
class TransportSecurityTest {

    private val token = "s3cret-token"

    // --- Where it listens ---

    @Test
    fun withoutAnyConfigurationItListensOnLoopbackOnly() {
        val security = ServerSecurity.fromEnvironment { null }
        assertEquals(ServerSecurity.LOOPBACK, security.bindHost)
        assertFalse(security.reachableFromNetwork)
        assertFalse(security.requiresToken)
    }

    @Test
    fun aTokenOpensItToTheNetwork() {
        val security = ServerSecurity.fromEnvironment {
            if (it == ServerSecurity.TOKEN_VARIABLE) token else null
        }
        assertEquals(ServerSecurity.ALL_INTERFACES, security.bindHost)
        assertTrue(security.requiresToken)
    }

    /** The escape hatch that keeps testing against a real device convenient. */
    @Test
    fun allowLanOpensItWithoutAToken() {
        val security = ServerSecurity.fromEnvironment {
            if (it == ServerSecurity.ALLOW_LAN_VARIABLE) "1" else null
        }
        assertEquals(ServerSecurity.ALL_INTERFACES, security.bindHost)
        assertFalse(security.requiresToken)
        assertTrue(security.describe().contains("NO authentication"), "the risk must be stated")
    }

    @Test
    fun blankOrAbsentValuesAreNotConfiguration() {
        val security = ServerSecurity.fromEnvironment { if (it == ServerSecurity.TOKEN_VARIABLE) "   " else "no" }
        assertFalse(security.requiresToken)
        assertEquals(ServerSecurity.LOOPBACK, security.bindHost)
    }

    // --- The container's mandatory token (T23) ---

    @Test
    fun aMandatoryTokenThatIsMissingRefusesToStart() {
        val security = ServerSecurity.fromEnvironment {
            if (it == ServerSecurity.REQUIRE_TOKEN_VARIABLE) "1" else null
        }
        assertNotNull(security.startupError())
    }

    /** Opting into the unauthenticated LAN hole does not satisfy a mandatory token. */
    @Test
    fun allowLanDoesNotSatisfyAMandatoryToken() {
        val security = ServerSecurity.fromEnvironment {
            when (it) {
                ServerSecurity.REQUIRE_TOKEN_VARIABLE, ServerSecurity.ALLOW_LAN_VARIABLE -> "1"
                else -> null
            }
        }
        assertNotNull(security.startupError())
    }

    @Test
    fun aMandatoryTokenThatIsSetStarts() {
        val security = ServerSecurity.fromEnvironment {
            when (it) {
                ServerSecurity.REQUIRE_TOKEN_VARIABLE -> "1"
                ServerSecurity.TOKEN_VARIABLE -> token
                else -> null
            }
        }
        assertNull(security.startupError())
        assertEquals(ServerSecurity.ALL_INTERFACES, security.bindHost)
    }

    @Test
    fun withoutTheMandateNoTokenIsFine() {
        assertNull(ServerSecurity.fromEnvironment { null }.startupError())
    }

    // --- Compression ---

    @Test
    fun responsesAreGzippedWhenTheClientAsksForIt() = testApplication {
        application { module(ServerSecurity.open()) }

        val response = client.get("/api/v1/bundle") {
            header(HttpHeaders.AcceptEncoding, "gzip")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
    }

    @Test
    fun aClientThatDoesNotAskForCompressionGetsPlainText() = testApplication {
        application { module(ServerSecurity.open()) }

        val response = client.get("/api/v1/bundle") {
            header(HttpHeaders.AcceptEncoding, "identity")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentEncoding] == null)
    }

    // --- Who it answers ---

    @Test
    fun anUnauthenticatedApiRequestIsRefusedWhenATokenIsSet() = testApplication {
        application { module(ServerSecurity(token = token, allowLanWithoutToken = false)) }

        val response = client.get("/api/v1/manifest")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("Bearer"), "the refusal should say how to fix it")
    }

    @Test
    fun theWrongTokenIsAlsoRefused() = testApplication {
        application { module(ServerSecurity(token = token, allowLanWithoutToken = false)) }

        val response = client.get("/api/v1/manifest") {
            header(HttpHeaders.Authorization, "Bearer not-the-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun theRightTokenIsAccepted() = testApplication {
        application { module(ServerSecurity(token = token, allowLanWithoutToken = false)) }

        val response = client.get("/api/v1/manifest") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun writesAreProtectedTooNotJustReads() = testApplication {
        application { module(ServerSecurity(token = token, allowLanWithoutToken = false)) }

        val response = client.get("/api/v1/files/notes.org")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun withNoTokenConfiguredTheApiIsOpen() = testApplication {
        application { module(ServerSecurity.open()) }

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/manifest").status)
    }
}
