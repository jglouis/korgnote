package com.korgnote.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.korgnote.app.data.InMemorySettingsRepository
import com.korgnote.app.data.NotesSource
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.data.WebDavAccount
import com.korgnote.app.data.WebDavSyncApi
import com.korgnote.app.di.WEBDAV
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.ReplicaStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The welcome screens' WebDAV step: offered where there is a replica for it, and checked before use. */
@OptIn(ExperimentalTestApi::class)
class WebDavOnboardingTest {

    private val settings = InMemorySettingsRepository(initialNotesSource = null)

    /**
     * A WebDAV folder in memory at `/notes/`, honouring If-Match and If-None-Match as a server safe
     * to sync with does, or ignoring them as rclone does when [honoursConditions] is false.
     */
    private fun fakeServer(honoursConditions: Boolean): HttpClient {
        val files = mutableMapOf<String, Pair<String, String>>()
        var version = 0
        return HttpClient(MockEngine { request ->
            val path = request.url.encodedPath.removePrefix("/notes/")
            val current = files[path]
            fun refused() = honoursConditions && (
                (request.headers[HttpHeaders.IfNoneMatch] == "*" && current != null) ||
                    request.headers[HttpHeaders.IfMatch]?.let { it != "\"${current?.first}\"" } == true
                )
            when (request.method.value) {
                "PROPFIND" -> {
                    val entries = if (path.isEmpty()) {
                        listOf("<d:response><d:href>/notes/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
                    } else {
                        listOfNotNull(current?.let { "<d:response><d:href>/notes/$path</d:href><d:propstat><d:prop><d:resourcetype/><d:getetag>\"${it.first}\"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" })
                    }
                    respond("<d:multistatus xmlns:d=\"DAV:\">${entries.joinToString("")}</d:multistatus>", HttpStatusCode.MultiStatus)
                }
                "GET" -> current?.let { respond(it.second, HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"${it.first}\"")) }
                    ?: respond("", HttpStatusCode.NotFound)
                "PUT" -> if (refused()) {
                    respond("", HttpStatusCode.PreconditionFailed)
                } else {
                    val etag = "v${++version}"
                    files[path] = etag to (request.body as TextContent).text
                    respond("", HttpStatusCode.Created, headersOf(HttpHeaders.ETag, "\"$etag\""))
                }
                "DELETE" -> when {
                    current == null -> respond("", HttpStatusCode.NotFound)
                    refused() -> respond("", HttpStatusCode.PreconditionFailed)
                    else -> {
                        files.remove(path)
                        respond("", HttpStatusCode.NoContent)
                    }
                }
                else -> respond("", HttpStatusCode.MethodNotAllowed)
            }
        }) { expectSuccess = false }
    }

    private fun startWith(client: HttpClient) {
        startKoin {
            modules(
                module {
                    single<SettingsRepository> { settings }
                    single<ReplicaStorage>(WEBDAV) { InMemoryReplicaStorage() }
                    single<WebDavSyncApi>(WEBDAV) { WebDavSyncApi(client, account = settings::getWebDavAccount) }
                },
            )
        }
    }

    @BeforeTest
    fun setUp() = startWith(fakeServer(honoursConditions = true))

    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun aServerThatPassesTheCheckIsUsedAndSaved() = runComposeUiTest {
        val chosen = mutableListOf<NotesSource>()
        setContent { Onboarding(current = null, onChosen = { chosen += it }, onCancel = null) }

        onNodeWithText("A WebDAV server (experimental)").performClick()
        onNodeWithText("Folder address").performTextInput("https://cloud.example.com/notes")
        onNodeWithText("User name").performTextInput("me")
        onNodeWithText("Password").performTextInput("pw")
        onNodeWithText("Check the server").performClick()
        waitUntil { onAllNodesWithTextExist("The server can be used. No notes there yet: new notes will go there.") }
        onNodeWithText("Use this server").performClick()

        assertEquals(listOf<NotesSource>(NotesSource.WebDav), chosen)
        assertEquals(WebDavAccount("https://cloud.example.com/notes", "me", "pw"), settings.getWebDavAccount())
    }

    @Test
    fun anAddressWithoutHttpsCannotBeChecked() = runComposeUiTest {
        setContent { Onboarding(current = null, onChosen = {}, onCancel = null) }

        onNodeWithText("A WebDAV server (experimental)").performClick()
        onNodeWithText("Folder address").performTextInput("http://cloud.example.com/notes")

        onNodeWithText("The address must start with https://", substring = true).assertExists()
        onNodeWithText("Check the server").assertIsNotEnabled()
    }

    @Test
    fun aServerThatIgnoresConditionsIsRefusedWithWhy() = runComposeUiTest {
        stopKoin()
        startWith(fakeServer(honoursConditions = false))
        setContent { Onboarding(current = null, onChosen = {}, onCancel = null) }

        onNodeWithText("A WebDAV server (experimental)").performClick()
        onNodeWithText("Folder address").performTextInput("https://cloud.example.com/notes")
        onNodeWithText("Check the server").performClick()
        waitUntil { onAllNodesWithTextExist("ignores If-None-Match", substring = true) }

        onNodeWithText("Use this server").assertDoesNotExist()
        assertEquals(null, settings.getWebDavAccount())
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTextExist(text: String, substring: Boolean = false) =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
}
