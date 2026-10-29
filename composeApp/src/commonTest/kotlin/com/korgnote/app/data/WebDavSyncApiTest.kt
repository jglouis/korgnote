package com.korgnote.app.data

import com.korgnote.common.replica.PushResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The WebDAV client against answers written out by hand, in the shapes real servers give: the
 * tests against real ones are in `WebDavServersTest`, and only run where those servers are.
 */
class WebDavSyncApiTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun apiWith(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): WebDavSyncApi {
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        val account = WebDavAccount("https://cloud.example.com/remote.php/dav/files/me/My Notes", "me", "pw")
        return WebDavSyncApi(HttpClient(engine) { expectSuccess = false }, { account })
    }

    private fun MockRequestHandleScope.multistatus(vararg responses: String) = respond(
        content = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">${responses.joinToString("")}</d:multistatus>""",
        status = HttpStatusCode.MultiStatus,
        headers = headersOf(HttpHeaders.ContentType, "application/xml; charset=utf-8"),
    )

    private fun file(href: String, etag: String?) =
        """<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype/>""" +
            (etag?.let { "<d:getetag>&quot;$it&quot;</d:getetag>" } ?: "") +
            """</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

    private fun folder(href: String) =
        """<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype>""" +
            """</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

    private val base = "/remote.php/dav/files/me/My%20Notes/"

    @Test
    fun anyNamespacePrefixAndEntitiesAreRead() {
        val tree = parseDavXml(
            """<?xml version="1.0"?><!-- hi --><D:multistatus xmlns:D="DAV:"><D:response xmlns:lp1="DAV:">""" +
                """<D:href>/a%20b.org</D:href><lp1:getetag>"x&amp;y"</lp1:getetag><D:resourcetype/></D:response></D:multistatus>"""
        )
        val response = tree.child("multistatus")!!.child("response")!!
        assertEquals("/a%20b.org", response.child("href")?.text)
        assertEquals("\"x&y\"", response.child("getetag")?.text)
        assertEquals(emptyList(), response.child("resourcetype")!!.children)
    }

    @Test
    fun hrefsAreComparedDecodedWhetherPathsOrUrls() {
        val basePath = "/remote.php/dav/files/me/My Notes/"
        assertEquals("", relativePath("/remote.php/dav/files/me/My%20Notes/", basePath))
        assertEquals("café.org", relativePath("/remote.php/dav/files/me/My%20Notes/caf%C3%A9.org", basePath))
        assertEquals("daily", relativePath("https://cloud.example.com/remote.php/dav/files/me/My%20Notes/daily/", basePath))
        assertNull(relativePath("/remote.php/dav/files/me/Other/x.org", basePath))
    }

    @Test
    fun etagsLoseTheirQuotesAndGetThemBack() {
        assertEquals("abc", canonicalEtag("\"abc\""))
        assertEquals("W/abc", canonicalEtag("W/\"abc\""))
        assertEquals("\"abc\"", quoted("abc"))
        assertEquals("W/\"abc\"", quoted("W/abc"))
    }

    @Test
    fun theManifestWalksVisibleFoldersAndListsOnlyNotes() = runTest {
        val api = apiWith { request ->
            when (request.url.encodedPath) {
                base -> multistatus(
                    folder(base), file("${base}inbox.org", "1"), file("${base}picture.png", "2"),
                    folder("${base}daily/"), folder("${base}.git/"),
                )
                "${base}daily/" -> multistatus(folder("${base}daily/"), file("${base}daily/2026-10-29.org", "3"))
                else -> error("listed ${request.url.encodedPath}")
            }
        }
        val manifest = api.manifest()
        assertTrue(manifest.complete)
        assertEquals(mapOf("inbox.org" to "1", "daily/2026-10-29.org" to "3"), manifest.entries.associate { it.path to it.etag })
        assertTrue(requests.all { it.headers["Depth"] == "1" })
    }

    @Test
    fun aNoteListedWithoutAnEtagFailsTheManifestRatherThanLookDeleted() = runTest {
        val api = apiWith { multistatus(folder(base), file("${base}inbox.org", etag = null)) }
        assertFailsWith<WebDavException> { api.manifest() }
    }

    @Test
    fun aFolderThatCannotBeListedFailsTheManifest() = runTest {
        val api = apiWith { request ->
            if (request.url.encodedPath == base) multistatus(folder(base), folder("${base}daily/"))
            else respond("", HttpStatusCode.InternalServerError)
        }
        assertFailsWith<WebDavException> { api.manifest() }
    }

    @Test
    fun aRefusedPasswordSaysSo() = runTest {
        val api = apiWith { respond("", HttpStatusCode.Unauthorized) }
        val refused = assertFailsWith<WebDavRefusedException> { api.manifest() }
        assertTrue("password" in refused.message.orEmpty())
        assertEquals("Basic bWU6cHc=", requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun aServerThatWantsAnotherWayToSignInSaysWhichNotThatThePasswordIsWrong() = runTest {
        val api = apiWith {
            respond("", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, """Digest realm="notes", nonce="abc", qop="auth""""))
        }
        val refused = assertFailsWith<WebDavRefusedException> { api.manifest() }
        assertTrue("Digest" in refused.message.orEmpty() && "refused" !in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun theWaysToSignInAreReadFromEveryShapeOfChallenge() {
        val wrongPassword = "The WebDAV server refused the user name or password. Check them in Settings."
        // Basic among others, in one header or several: the password is what was refused.
        assertEquals(wrongPassword, refusal(listOf("""Digest realm="a", nonce="n", Basic realm="a"""")))
        assertEquals(wrongPassword, refusal(listOf("Negotiate", """Basic realm="a"""")))
        // No challenge at all says nothing better.
        assertEquals(wrongPassword, refusal(emptyList()))
        // Without Basic, the ways asked for are named, once each; a quoted comma is no scheme.
        assertTrue("Windows sign-in (NTLM or Kerberos)" in refusal(listOf("Negotiate", "NTLM")))
        val digest = refusal(listOf("""Digest realm="Acme, Inc", nonce="n", algorithm=MD5"""))
        assertTrue("with Digest," in digest && "Inc" !in digest, digest)
    }

    @Test
    fun aWriteIntoAMissingFolderMakesItAndTriesAgain() = runTest {
        val folders = mutableSetOf<String>()
        val api = apiWith { request ->
            when (request.method.value) {
                "MKCOL" -> {
                    folders += request.url.encodedPath
                    respond("", HttpStatusCode.Created)
                }
                "PUT" -> if ("${base}a/" in folders && "${base}a/b/" in folders) {
                    respond("", HttpStatusCode.Created, headersOf(HttpHeaders.ETag, "\"new\""))
                } else {
                    respond("", HttpStatusCode.NotFound)
                }
                else -> error("unexpected ${request.method.value}")
            }
        }
        assertEquals(PushResult.Pushed("new"), api.push("a/b/c.org", "* C\n", ifMatch = null, mustNotExist = true))
        assertEquals("*", requests.last().headers[HttpHeaders.IfNoneMatch])
    }

    @Test
    fun answersAreAskedForUncompressedSoTheirEtagsAreTheListedOnes() = runTest {
        val api = apiWith { respond("* Note\n", HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"e1\"")) }
        api.fetch("note.org")
        assertEquals("identity", requests.single().headers[HttpHeaders.AcceptEncoding])
    }

    @Test
    fun aWriteOverAnotherVersionIsAConflict() = runTest {
        val api = apiWith { respond("", HttpStatusCode.PreconditionFailed) }
        assertEquals(PushResult.Conflict(null), api.push("a.org", "x", ifMatch = "old"))
        assertEquals("\"old\"", requests.single().headers[HttpHeaders.IfMatch])
    }
}
