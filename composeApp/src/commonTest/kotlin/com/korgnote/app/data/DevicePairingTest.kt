package com.korgnote.app.data

import com.korgnote.common.pairing.PairingLink
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
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Pairing trusts the certificate its link names and nothing else — not the one paired before — and
 * does it with a client of its own, so that no connection checked against another certificate can
 * carry the code.
 */
class DevicePairingTest {

    private val link = PairingLink("server-2", "Laptop", listOf("10.0.0.5", "192.168.1.20"), 8443, "ab".repeat(32), "c0de")
    private val before = PairedServer("server-1", "Old", listOf("192.168.1.20"), 8443, "cd".repeat(32), "dev-1", "old-token")
    private val pinsGiven = mutableListOf<Set<String>>()
    private val hosts = mutableListOf<String>()

    private fun pairing(settings: SettingsRepository, unreachable: Set<String> = emptySet(), status: HttpStatusCode = HttpStatusCode.OK) =
        DevicePairing({ pins ->
            pinsGiven += pins.accepted()
            HttpClient(MockEngine { request ->
                hosts += request.url.host
                if (request.url.host in unreachable) throw RuntimeException("no route")
                respond(
                    """{"deviceId":"dev-2","token":"new-token","serverId":"server-2","serverName":"Laptop"}""",
                    status,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }) { install(ContentNegotiation) { json() } }
        }, settings)

    @Test
    fun theLinksCertificateAloneIsTrusted() = runTest {
        val settings = InMemorySettingsRepository(initialPairedServer = before)
        pairing(settings).pair(link, "Phone")
        assertEquals(listOf(setOf(link.fingerprint)), pinsGiven, "not the one paired before")
    }

    @Test
    fun theAddressThatAnswersIsTriedFirstFromThenOn() = runTest {
        val settings = InMemorySettingsRepository()
        val result = pairing(settings, unreachable = setOf("10.0.0.5")).pair(link, "Phone")

        assertIs<PairingResult.Paired>(result)
        assertEquals(listOf("10.0.0.5", "192.168.1.20"), hosts)
        val saved = settings.getPairedServer()!!
        assertEquals(listOf("192.168.1.20", "10.0.0.5"), saved.addresses)
        assertEquals(link.fingerprint, saved.fingerprint)
        assertEquals("new-token", saved.token)
    }

    @Test
    fun aRefusedCodeChangesNothing() = runTest {
        val settings = InMemorySettingsRepository(initialPairedServer = before)
        val result = pairing(settings, status = HttpStatusCode.Forbidden).pair(link, "Phone")

        assertIs<PairingResult.Failed>(result)
        assertEquals(before, settings.getPairedServer())
    }

    @Test
    fun noAnswerAnywhereSaysWhereItLooked() = runTest {
        val settings = InMemorySettingsRepository()
        val result = pairing(settings, unreachable = setOf("10.0.0.5", "192.168.1.20")).pair(link, "Phone")

        assertIs<PairingResult.Failed>(result)
        assertEquals(true, result.reason.startsWith("Could not reach Laptop at 10.0.0.5 or 192.168.1.20, "), result.reason)
        assertNull(settings.getPairedServer())
    }
}
