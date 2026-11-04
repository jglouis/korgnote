package com.korgnote.app.ui

import com.korgnote.common.org.edit.LinkKind
import com.korgnote.common.org.edit.linkKindOf
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkEditorSchemeTest {

    /** A host and its port has no scheme, however it reads: unchanged, it would be a link to a note. */
    @Test
    fun aHostWithAPortIsAWebAddress() {
        val written = withScheme("example.com:8080/x")
        assertEquals("https://example.com:8080/x", written)
        assertEquals(LinkKind.WEB, linkKindOf(written))
    }

    /** The port is the only thing that tells a host from a scheme with dots in it. */
    @Test
    fun aDottedSchemeIsLeftAloneAndAHostWithAPortIsNot() {
        assertEquals("org.foo.app:open", withScheme("org.foo.app:open"))
        assertEquals("com.example.notes:/page/2", withScheme("com.example.notes:/page/2"))
        assertEquals("https://example.com:8080", withScheme("example.com:8080"))
        assertEquals("https://example.com:8080?q=1", withScheme("example.com:8080?q=1"))
        assertEquals("https://example.com:8080#top", withScheme("example.com:8080#top"))
    }

    @Test
    fun anAddressWithASchemeOrNoHostIsLeftAlone() {
        assertEquals("https://example.com/x", withScheme(" https://example.com/x "))
        assertEquals("mailto:me@example.com", withScheme("mailto:me@example.com"))
        assertEquals("localhost:8080/x", withScheme("localhost:8080/x"))
        assertEquals("words", withScheme("words"))
        assertEquals("https://example.com/page", withScheme("example.com/page"))
    }
}
