package com.korgnote.common

import com.korgnote.common.pairing.PairingLink
import com.korgnote.common.pairing.PairingLinkCheck
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

    /** What cannot pair says why, so the phone can say what to do. */
    @Test
    fun aLinkThatCannotPairSaysWhy() {
        assertEquals(PairingLinkCheck.Valid(link), PairingLink.check(link.toUri()))
        assertEquals(PairingLinkCheck.NotALink, PairingLink.check("https://example.com/devices"))
        assertEquals(PairingLinkCheck.NotALink, PairingLink.check(""))
        // As a server in a container without KORGNOTE_ADVERTISED_ADDRESSES wrote them.
        assertEquals(
            PairingLinkCheck.NoAddress("KorgNote"),
            PairingLink.check(
                "korgnote://pair?v=1&id=b5d40f50&name=KorgNote&a=&p=8443&fp=" + "aa".repeat(32) + "&c=3e6e4847",
            ),
        )
        // A copy cut short, or with its fingerprint mangled, is incomplete, address or not.
        assertEquals(PairingLinkCheck.Incomplete, PairingLink.check(link.toUri().substringBefore("&c=")))
        assertEquals(PairingLinkCheck.Incomplete, PairingLink.check(link.toUri().replace(link.fingerprint, "abc")))
        assertEquals(PairingLinkCheck.Incomplete, PairingLink.check("korgnote://pair?v=1&a=&p=8443"))
    }
}
