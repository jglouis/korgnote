package com.korgnote.server

import com.korgnote.common.pairing.PairRequest
import com.korgnote.common.pairing.PairResponse
import com.korgnote.common.pairing.PinnedTrustManager
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pairing as a phone does it, over a real TLS connection to the server's own certificate: the
 * client trusts that certificate by fingerprint and nothing else, so a server showing any other is
 * refused before a byte of the request is sent.
 */
class PairingOverTlsTest {

    private val stateDir = Files.createTempDirectory("korgnote-tls").toFile()
    private val identity = ServerIdentity.loadOrCreate(stateDir)
    private val devices = DeviceRegistry(file = null)
    private val httpPort = freePort()
    private val httpsPort = freePort()
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> = embeddedServer(
        Netty,
        configure = {
            connector {
                host = "127.0.0.1"
                port = httpPort
            }
            sslConnector(
                keyStore = identity.keyStore,
                keyAlias = ServerIdentity.KEY_ALIAS,
                keyStorePassword = { identity.keyStorePassword.toCharArray() },
                privateKeyPassword = { identity.keyStorePassword.toCharArray() },
            ) {
                host = "127.0.0.1"
                port = httpsPort
            }
        },
    ) {
        module(ServerAccess(devices, securePort = httpsPort, pairing = PairingDetails(identity.serverId, "Laptop", identity.fingerprint, httpsPort, emptyList())))
    }.start(wait = false)

    @AfterTest
    fun stop() {
        server.stop(0, 0)
        stateDir.deleteRecursively()
    }

    private fun clientPinning(fingerprint: String) = HttpClient(Java) {
        engine { config { sslContext(PinnedTrustManager.sslContext { setOf(fingerprint) }) } }
        install(ContentNegotiation) { json() }
        expectSuccess = false
    }

    @Test
    fun aDevicePairsAndSyncsOverTls() = runBlocking {
        val client = clientPinning(identity.fingerprint)
        val response = client.post("https://127.0.0.1:$httpsPort/api/pair") {
            contentType(ContentType.Application.Json)
            setBody(PairRequest(devices.newPairingCode(), "Phone"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val paired = response.body<PairResponse>()
        assertEquals(identity.serverId, paired.serverId)

        val manifest = client.get("https://127.0.0.1:$httpsPort/api/v1/manifest") {
            header(HttpHeaders.Authorization, "Bearer ${paired.token}")
        }
        assertEquals(HttpStatusCode.OK, manifest.status)
    }

    @Test
    fun aServerWithAnotherCertificateIsRefused() {
        val client = clientPinning("00".repeat(32))
        assertFailsWith<Exception> {
            runBlocking { client.get("https://127.0.0.1:$httpsPort/api/v1/manifest") }
        }
    }

    @Test
    fun thePlainPortServesThisComputerWithoutAToken() = runBlocking {
        val client = HttpClient(Java) { expectSuccess = false }
        assertEquals(HttpStatusCode.OK, client.get("http://127.0.0.1:$httpPort/api/v1/manifest").status)
        assertTrue(client.get("http://127.0.0.1:$httpPort/devices").status == HttpStatusCode.OK)
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }
}
