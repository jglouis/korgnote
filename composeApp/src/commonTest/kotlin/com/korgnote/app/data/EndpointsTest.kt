package com.korgnote.app.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Where the app sends its requests: the paired computer over TLS, the server on its own computer, or nowhere. */
class EndpointsTest {

    private val paired = PairedServer("s", "Laptop", listOf("192.168.1.20", "10.0.0.5"), 8443, "ab".repeat(32), "d", "t0ken")

    @Test
    fun aPairedDeviceUsesTheComputersAddressesOverTls() {
        val settings = InMemorySettingsRepository(initialPairedServer = paired)
        assertEquals(
            listOf(Endpoint("https://192.168.1.20:8443", "t0ken"), Endpoint("https://10.0.0.5:8443", "t0ken")),
            endpointsFor(settings, localServer = false),
        )
    }

    @Test
    fun theDesktopUnpairedUsesTheServerOnItsOwnComputer() {
        val settings = InMemorySettingsRepository(initialHost = "localhost", initialPort = 8080)
        assertEquals(listOf(Endpoint("http://localhost:8080", null)), endpointsFor(settings, localServer = true))
    }

    @Test
    fun aPhoneUnpairedHasNowhereToGo() {
        val settings = InMemorySettingsRepository(initialHost = "192.168.1.20", initialPort = 8080)
        assertTrue(endpointsFor(settings, localServer = false).isEmpty(), "no request in the clear to an old address")
    }

    @Test
    fun withNowhereToGoTheAppSaysToPair() = runTest {
        var sent = 0
        val api = KtorSyncApi(HttpClient(MockEngine { sent++; respondOk() })) { emptyList() }
        val error = assertFailsWith<NotPairedException> { api.manifest() }
        assertEquals("This device is not paired with a computer yet. Pair it in Settings.", error.message)
        assertEquals(0, sent)
    }
}
