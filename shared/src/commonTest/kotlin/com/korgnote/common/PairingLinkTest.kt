package com.korgnote.common

import com.korgnote.common.pairing.PairingLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingLinkTest {

    private val link = PairingLink(
        serverId = "2f0c-11",
        serverName = "Jean's MacBook & co",
        addresses = listOf("192.168.1.20", "10.0.0.5"),
        port = 8443,
        fingerprint = "0123456789abcdef".repeat(4),
        code = "c0de",
    )

    @Test
    fun aLinkReadsBackAsItWasWritten() {
        assertEquals(link, PairingLink.parse(link.toUri()))
    }

    @Test
    fun aPastedLinkMayHaveSpaceAround() {
        assertEquals(link, PairingLink.parse("  " + link.toUri() + "\n"))
    }

    @Test
    fun namesAreEncodedSoTheyCannotBreakTheLink() {
        val uri = link.toUri()
        assertEquals(true, uri.contains("name=Jean%27s%20MacBook%20%26%20co"), uri)
    }

    @Test
    fun anythingMissingOrMalformedIsNotALink() {
        assertNull(PairingLink.parse("https://example.com"))
        assertNull(PairingLink.parse(link.toUri().replace("&c=c0de", "")))
        assertNull(PairingLink.parse(link.toUri().replace("&p=8443", "&p=0")))
        assertNull(PairingLink.parse(link.toUri().replace(link.fingerprint, "abc")))
        assertNull(PairingLink.parse(link.toUri().replace("a=192.168.1.20,10.0.0.5", "a=")))
    }
}
