package com.korgnote.app.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a server lists is kept only when it is a note this device can keep: a server that listed a
 * script would have it written into the replica beside the notes, where a link could open it; one
 * that said a folder was deleted would have the folder taken off the device; and a name too long to
 * write here, or with a control character in it, would stop every sync at it. All made up.
 */
class KtorSyncApiPathsTest {

    private val longName = "n".repeat(240) + ".org"
    private val listed = listOf("a.org", "sub/b.org", "evil.bat", "x.terminal", "Projects", ".hidden/c.org", longName, "nul\u0000.org", "tab\t.org")

    private fun api(body: String): KtorSyncApi {
        val engine = MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        return KtorSyncApi(HttpClient(engine) { install(ContentNegotiation) { json() } }) { listOf(Endpoint("https://192.168.1.20:8443", "t")) }
    }

    private fun json(text: String) = "\"" + text.replace("\u0000", "\\u0000").replace("\t", "\\t") + "\""

    @Test
    fun aManifestKeepsOnlyNotes() = runTest {
        val entries = listed.joinToString(",") { """{"path":${json(it)},"size":1,"mtime":1,"etag":"e"}""" }
        val deleted = listed.joinToString(",") { json(it) }
        val manifest = api("""{"generation":1,"complete":true,"entries":[$entries],"deleted":[$deleted]}""").manifest()
        assertEquals(listOf("a.org", "sub/b.org"), manifest.entries.map { it.path })
        assertEquals(listOf("a.org", "sub/b.org"), manifest.deleted)
    }

    @Test
    fun aBundleKeepsOnlyNotes() = runTest {
        val lines = listed.joinToString("\n") { """{"path":${json(it)},"etag":"e","text":"* x"}""" }
        assertEquals(listOf("a.org", "sub/b.org"), api(lines).bundle(null).map { it.path })
    }
}
