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

    /** Who may reach what is the point here, not what they find. */
    private val noNotes = FileStore(java.nio.file.Path.of("temp_no_notes_here"), readOnly = true)

    private fun ApplicationTestBuilder.asDevices() = application { module(noNotes, ServerAccess(devices, securePort = 80, pairing = details)) }

    private fun ApplicationTestBuilder.asThisComputer() = application { module(noNotes, ServerAccess(devices, securePort = 8443, pairing = details)) }

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

    /**
     * Routing ignores empty segments and decodes the rest, so these reach the same route as
     * `/api/local/pairing`. A paired device has no business making pairing codes, and with `addresses`
     * it would change where the next QR code sends a phone.
     */
    @Test
    fun overTlsTheLocalApiCannotBeReachedByAnotherSpelling() = testApplication {
        asDevices()
        val token = Json.decodeFromString<PairResponse>(pair(devices.newPairingCode()).bodyAsText()).token
        val spellings = listOf(
            "/api/local/pairing", "/api//local/pairing", "/api/%6Cocal/pairing", "/%61pi/local/pairing",
            "/api/v1/../local/pairing", "/api/v1/%2e%2e/local/pairing",
        )
        for (path in spellings) {
            val response = client.post("$path?addresses=6.6.6.6&format=link") { header(HttpHeaders.Authorization, "Bearer $token") }
            assertEquals(HttpStatusCode.NotFound, response.status, path)
        }
        assertEquals(emptyList(), details.lastAddresses)
    }

    /** Only what a phone uses is there at all, with or without a token. */
    @Test
    fun overTlsAPairedDeviceReachesTheSyncApiAndPairingOnly() = testApplication {
        asDevices()
        val token = Json.decodeFromString<PairResponse>(pair(devices.newPairingCode()).bodyAsText()).token
        val authorized: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/manifest", authorized).status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/bundle", authorized).status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/", authorized).status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/").status)
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
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/manifest") { header(HttpHeaders.Host, "10.0.2.2:8080") }.status, "the emulator pairs, as a phone does")
    }

    /** Anyone can register a name that starts like a loopback address and point it here. */
    @Test
    fun aNameThatOnlyStartsLikeALoopbackAddressIsRefused() = testApplication {
        asThisComputer()
        for (host in listOf("127.evil.example", "127.0.0.1.evil.example", "127.0.0.evil")) {
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/manifest") { header(HttpHeaders.Host, "$host:8080") }.status, host)
        }
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/manifest") { header(HttpHeaders.Host, "127.1.2.3:8080") }.status)
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

    /**
     * The browser decodes an attribute before it parses it as script, so no escaping keeps a name
     * safely inside inline script: `Jean's phone` was a syntax error there, and Revoke went ahead
     * unasked, and a device can pick a name that runs.
     */
    @Test
    fun aDeviceNameNeverReachesInlineScript() = testApplication {
        asThisComputer()
        val name = "Jean's \"phone\"'); fetch('/x'); ('"
        devices.pair(PairRequest(devices.newPairingCode(), name))

        val page = client.get("/devices").bodyAsText()

        assertFalse(page.contains("onsubmit"), page)
        val quoted = name.replace("\"", "&quot;")
        assertTrue(page.contains("data-name=\"$quoted\""), page)
        val script = page.substringAfter("<script>").substringBefore("</script>")
        assertTrue(script.contains("confirm("), "the confirmation is still asked")
        assertFalse(script.contains("Jean"), script)
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
    fun aSystemPropertyWinsOverTheEnvironment() {
        val environment = mapOf(ServerConfig.NOTES_DIR_VARIABLE to "from-env", ServerConfig.WEB_DIR_VARIABLE to "web-from-env")
        val properties = mapOf(ServerConfig.NOTES_DIR_PROPERTY to "from-property", ServerConfig.WEB_DIR_PROPERTY to "web-from-property")

        val both = ServerConfig.fromEnvironment(properties::get, environment::get)
        assertEquals("from-property", both.notesDir.path)
        assertEquals("web-from-property", both.webClientDir)

        val envOnly = ServerConfig.fromEnvironment({ null }, environment::get)
        assertEquals("from-env", envOnly.notesDir.path)
        assertEquals("web-from-env", envOnly.webClientDir)

        val neither = ServerConfig.fromEnvironment({ null }, { null })
        assertEquals("notes", neither.notesDir.path)
        assertEquals(null, neither.webClientDir)
    }

    @Test
    fun aSwitchIsSetByAnyOfItsWords() {
        for (word in listOf("1", "true", "YES", " yes ")) {
            val config = ServerConfig.fromEnvironment({ null }) {
                if (it == ServerConfig.REQUIRE_NOTES_DIR_VARIABLE || it == ServerConfig.DISABLE_WEB_VARIABLE) word else null
            }
            assertTrue(config.requireNotesDir, word)
            assertFalse(config.serveWebClient, word)
        }
        val off = ServerConfig.fromEnvironment({ null }) { "0" }
        assertFalse(off.requireNotesDir)
        assertTrue(off.serveWebClient)
    }

    @Test
    fun aHomeInThePathOfAFolderIsExpanded() {
        val home = System.getProperty("user.home")
        val config = ServerConfig.fromEnvironment({ null }) {
            when (it) {
                ServerConfig.NOTES_DIR_VARIABLE -> "~/my-notes"
                ServerConfig.STATE_DIR_VARIABLE -> "~/my-state"
                else -> null
            }
        }
        assertEquals("$home/my-notes", config.notesDir.path)
        assertEquals("$home/my-state", config.stateDir.path)
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
