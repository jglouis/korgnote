package com.korgnote.server

import com.korgnote.common.pairing.PairRequest
import com.korgnote.common.pairing.PairResponse
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Who the server answers, and on which port: paired devices over TLS, this computer on the plain one.
 *
 * The test host has one port, 80. Calling it the secure port makes requests arrive as a device's
 * would; calling another port secure makes them arrive as this computer's.
 */
class TransportSecurityTest {

    private var clock = 1_000_000L
    private var codes = 0
    private var tokens = 0
    private val devices = DeviceRegistry(file = null, now = { clock }, newToken = { "token-${++tokens}" }, newCode = { "code-${++codes}" })
    private val details = PairingDetails("server-1", "Laptop", "ab".repeat(32), 8443, listOf("192.168.1.20"))

    private fun ApplicationTestBuilder.asDevices() = application { module(ServerAccess(devices, securePort = 80, pairing = details)) }

    private fun ApplicationTestBuilder.asThisComputer() = application { module(ServerAccess(devices, securePort = 8443, pairing = details)) }

    private suspend fun ApplicationTestBuilder.pair(code: String, name: String = "Phone", replaces: String? = null, replacesToken: String? = null) =
        client.post("/api/pair") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(PairRequest(code, name, replaces, replacesToken)))
        }

    // --- Over TLS: paired devices only ---

    @Test
    fun anUnpairedDeviceIsRefused() = testApplication {
        asDevices()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/manifest").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/manifest") { header(HttpHeaders.Authorization, "Bearer guess") }.status)
    }

    @Test
    fun aCodeBuysATokenThatOpensTheApi() = testApplication {
        asDevices()
        val response = pair(devices.newPairingCode())
        assertEquals(HttpStatusCode.OK, response.status)
        val paired = Json.decodeFromString<PairResponse>(response.bodyAsText())
        assertEquals("server-1", paired.serverId)
        assertEquals("Laptop", paired.serverName)

        val manifest = client.get("/api/v1/manifest") { header(HttpHeaders.Authorization, "Bearer ${paired.token}") }
        assertEquals(HttpStatusCode.OK, manifest.status)
        assertEquals("Phone", devices.all().single().name)
        assertEquals(clock, devices.all().single().lastSeen)
    }

    @Test
    fun aCodeWorksOnce() = testApplication {
        asDevices()
        val code = devices.newPairingCode()
        assertEquals(HttpStatusCode.OK, pair(code).status)
        assertEquals(HttpStatusCode.Forbidden, pair(code).status)
        assertEquals(HttpStatusCode.Forbidden, pair("made-up").status)
    }

    @Test
    fun aCodeExpires() = testApplication {
        asDevices()
        val code = devices.newPairingCode()
        clock += DeviceRegistry.CODE_LIFETIME_MS + 1
        assertEquals(HttpStatusCode.Forbidden, pair(code).status)
    }

    @Test
    fun aRevokedDeviceIsRefusedAtOnce() = testApplication {
        asDevices()
        val paired = Json.decodeFromString<PairResponse>(pair(devices.newPairingCode()).bodyAsText())
        devices.revoke(paired.deviceId)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/manifest") { header(HttpHeaders.Authorization, "Bearer ${paired.token}") }.status)
    }

    @Test
    fun pairingAgainReplacesTheOldEntryWhenItCanProveItIsThatDevice() = testApplication {
        asDevices()
        val first = Json.decodeFromString<PairResponse>(pair(devices.newPairingCode()).bodyAsText())
        pair(devices.newPairingCode(), replaces = first.deviceId, replacesToken = "not-its-token")
        assertEquals(2, devices.all().size, "a wrong token replaces nothing")

        val third = Json.decodeFromString<PairResponse>(pair(devices.newPairingCode(), replaces = first.deviceId, replacesToken = first.token).bodyAsText())
        assertEquals(2, devices.all().size)
        assertFalse(devices.all().any { it.id == first.deviceId })
        assertTrue(devices.all().any { it.id == third.deviceId })
    }

    @Test
    fun overTlsThereIsNothingButTheApi() = testApplication {
        asDevices()
        assertEquals(HttpStatusCode.NotFound, client.get("/devices").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/local/pairing").status)
    }

    // --- The plain port: this computer only ---

    @Test
    fun thisComputerNeedsNoToken() = testApplication {
        asThisComputer()
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/manifest").status)
    }

    @Test
    fun aPageReachingItByAnotherNameIsRefused() = testApplication {
        asThisComputer()
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/manifest") { header(HttpHeaders.Host, "rebound.example:8080") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/manifest") { header(HttpHeaders.Host, "192.168.1.20:8080") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/manifest") { header(HttpHeaders.Host, "127.0.0.1:8080") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/manifest") { header(HttpHeaders.Host, "10.0.2.2:8080") }.status, "the emulator's way to its host")
    }

    @Test
    fun anotherSitesPageCannotChangeNotes() = testApplication {
        asThisComputer()
        val response = client.put("/api/v1/files/new.org") {
            header(HttpHeaders.Origin, "https://evil.example")
            setBody("* hi\n")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun theDevicesPageListsAndRevokes() = testApplication {
        asThisComputer()
        devices.pair(PairRequest(devices.newPairingCode(), "Pixel <b>"))
        val page = client.get("/devices").bodyAsText()
        assertTrue(page.contains("Pixel &lt;b&gt;"), "names are escaped")

        val id = devices.all().single().id
        client.post("/devices/$id/revoke") { header(HttpHeaders.Origin, "http://localhost") }
        assertTrue(devices.all().isEmpty())
    }

    @Test
    fun theDevicesPageShowsAPairingCode() = testApplication {
        asThisComputer()
        val page = client.submitForm("/devices/pair", parameters { append("addresses", "10.0.0.5, 192.168.1.20") }).bodyAsText()
        assertTrue(page.contains("<svg"))
        assertTrue(page.contains("korgnote://pair?v=1&amp;id=server-1"))
        assertTrue(page.contains("a=10.0.0.5,192.168.1.20"), page)
        assertEquals(listOf("10.0.0.5", "192.168.1.20"), details.lastAddresses)
    }

    @Test
    fun theScriptGetsTheCodeAsText() = testApplication {
        asThisComputer()
        val text = client.post("/api/local/pairing?addresses=192.168.1.30&format=link").bodyAsText().trim()
        assertTrue(text.startsWith("korgnote://pair?v=1&id=server-1&name=Laptop&a=192.168.1.30"), text)
        assertTrue(text.endsWith("&c=code-1"))
    }

    // --- Configuration and identity ---

    @Test
    fun theDefaultsServeThisComputerPlainAndTheNetworkOverTls() {
        val config = ServerConfig.fromEnvironment { null }
        assertEquals(ServerConfig.LOOPBACK, config.httpHost)
        assertEquals(8080, config.httpPort)
        assertEquals(ServerConfig.ALL_INTERFACES, config.httpsHost)
        assertEquals(8443, config.httpsPort)
        assertEquals(8443, config.advertisedPort)
        assertFalse(config.leftoverToken)
    }

    @Test
    fun anOldTokenIsIgnoredButMentioned() {
        val config = ServerConfig.fromEnvironment { if (it == ServerConfig.TOKEN_VARIABLE) "s3cret" else null }
        assertTrue(config.leftoverToken)
        assertTrue(config.describe().contains("no longer used"))
    }

    @Test
    fun theContainerTellsWhereItIsReached() {
        val config = ServerConfig.fromEnvironment {
            when (it) {
                ServerConfig.ADDRESSES_VARIABLE -> "192.168.1.20, 10.0.0.5"
                ServerConfig.ADVERTISED_PORT_VARIABLE -> "9443"
                else -> null
            }
        }
        assertEquals(listOf("192.168.1.20", "10.0.0.5"), config.advertisedAddresses)
        assertEquals(9443, config.advertisedPort)
    }

    @Test
    fun theIdentityIsMadeOnceAndKept() {
        val dir = Files.createTempDirectory("korgnote-state").toFile()
        try {
            val first = ServerIdentity.loadOrCreate(dir)
            val again = ServerIdentity.loadOrCreate(dir)
            assertEquals(first.serverId, again.serverId)
            assertEquals(first.fingerprint, again.fingerprint)
            assertTrue(Regex("^[0-9a-f]{64}$").matches(first.fingerprint))
            val other = ServerIdentity.loadOrCreate(Files.createTempDirectory("korgnote-state").toFile())
            assertNotEquals(first.fingerprint, other.fingerprint)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun devicesAreKeptAcrossRestarts() {
        val dir = Files.createTempDirectory("korgnote-state").toFile()
        try {
            val file = java.io.File(dir, "devices.json")
            val registry = DeviceRegistry(file)
            val (device, token) = registry.pair(PairRequest(registry.newPairingCode(), "Phone"))!!
            assertFalse(file.readText().contains(token), "only its hash is written")

            val restarted = DeviceRegistry(file)
            assertEquals(device.id, restarted.authenticate(token)?.id)
        } finally {
            dir.deleteRecursively()
        }
    }
}
