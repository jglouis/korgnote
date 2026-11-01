package com.korgnote.server

import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A pairing code is only made for an address a phone can use. A server in a container started
 * without `KORGNOTE_ADVERTISED_ADDRESSES` knows none, and used to put an empty address list in its
 * QR code, which no phone could pair with.
 */
class PairingAddressTest {

    private var codes = 0
    private val devices = DeviceRegistry(file = null, newCode = { "code-${++codes}" })

    /** As in a container started without addresses: none configured, none seen on its interfaces. */
    private val nowhere = PairingDetails("server-1", "Laptop", "ab".repeat(32), 8443, emptyList(), interfaces = { emptyList() })

    private fun ApplicationTestBuilder.asThisComputer(details: PairingDetails = nowhere) =
        application { module(ServerAccess(devices, securePort = 8443, pairing = details)) }

    @Test
    fun theDevicesPageSaysWhenItKnowsNoAddress() = testApplication {
        asThisComputer()
        val page = client.get("/devices").bodyAsText()
        assertTrue(page.contains("class=warning"), page)
        assertTrue(page.contains("does not know an address"), page)
        assertTrue(page.contains("KORGNOTE_ADVERTISED_ADDRESSES"), page)
    }

    @Test
    fun noAddressMeansNoCode() = testApplication {
        asThisComputer()
        val response = client.submitForm("/devices/pair", parameters { append("addresses", "") })
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val page = response.bodyAsText()
        assertFalse(page.contains("<svg"), "no QR code")
        assertFalse(page.contains("korgnote://pair"), "no link")
        assertTrue(page.contains("does not know an address"), page)
        assertEquals(0, codes, "no code was spent")
    }

    @Test
    fun anAddressTypedOnThePageMakesTheCode() = testApplication {
        asThisComputer()
        val page = client.submitForm("/devices/pair", parameters { append("addresses", "192.168.1.20") }).bodyAsText()
        assertTrue(page.contains("<svg"))
        assertTrue(page.contains("a=192.168.1.20&amp;"), page)
        // Remembered for the next code, until the server restarts.
        assertFalse(client.get("/devices").bodyAsText().contains("class=warning"))
    }

    @Test
    fun whatIsNotAnAddressIsTurnedDownAndKeptToCorrect() = testApplication {
        asThisComputer()
        val response = client.submitForm("/devices/pair", parameters { append("addresses", "https://192.168.1.20:8443") })
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val page = response.bodyAsText()
        assertFalse(page.contains("<svg"))
        assertTrue(page.contains("is not an address"), page)
        assertTrue(page.contains("value=\"https://192.168.1.20:8443\""), "left in the box to correct")
        assertEquals(0, codes)
    }

    @Test
    fun theScriptIsToldToo() = testApplication {
        asThisComputer()
        val response = client.post("/api/local/pairing?format=link")
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("does not know an address"))
        assertEquals(0, codes)

        val given = client.post("/api/local/pairing?addresses=192.168.1.30&format=link")
        assertEquals(HttpStatusCode.OK, given.status)
        assertTrue(given.bodyAsText().contains("&a=192.168.1.30&"))
    }

    @Test
    fun addressesAreAddressesOrHostNames() {
        for (ok in listOf("192.168.1.20", "10.0.0.5", "fe80::1", "fe80::1%en0", "2001:db8::42", "laptop.local", "jglm3max")) {
            assertTrue(isPairingAddress(ok), ok)
        }
        for (bad in listOf("", "http://192.168.1.20", "192.168.1.20:8443", "999.1.1.1", "192.168.1", "two words", "-x")) {
            assertFalse(isPairingAddress(bad), bad)
        }
    }
}
