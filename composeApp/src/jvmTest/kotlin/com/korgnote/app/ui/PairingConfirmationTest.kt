package com.korgnote.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.korgnote.app.data.DevicePairing
import com.korgnote.app.data.InMemorySettingsRepository
import com.korgnote.app.data.PairedServer
import com.korgnote.common.pairing.PairingLink
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A pairing link decides which computer gets the notes, and any web page or message can open one:
 * the app pairs from a link another app opened only once it is agreed to, and never puts another
 * server in place of the paired one without saying so. All made up: no real computer or token.
 */
@OptIn(ExperimentalTestApi::class)
class PairingConfirmationTest {

    private val yours = PairedServer("server-1", "Desk", listOf("192.168.1.20"), 8443, "cd".repeat(32), "dev-1", "your-token")
    private val other = PairingLink("server-2", "Not yours", listOf("203.0.113.9"), 8443, "ab".repeat(32), "c0de")
    private val requests = mutableListOf<String>()

    private fun pairing(settings: InMemorySettingsRepository) = DevicePairing({ _ ->
        HttpClient(MockEngine { request ->
            requests += request.url.host
            respond(
                """{"deviceId":"dev-2","token":"new-token","serverId":"server-2","serverName":"Not yours"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) { install(ContentNegotiation) { json() } }
    }, settings)

    private val inTheDialog = hasAnyAncestor(isDialog())

    @Test
    fun aLinkFromOutsideDoesNotReplaceThePairedServerUnlessAgreedTo() = runComposeUiTest {
        val settings = InMemorySettingsRepository(initialPairedServer = yours)
        setContent { PairingSettings(settings, pairing(settings), onChanged = {}, incomingLink = other.toUri()) }
        waitForIdle()

        onNodeWithText("Replace Desk with Not yours?").assertExists()
        assertTrue(requests.isEmpty(), "nothing is sent before it is agreed to")
        assertEquals(yours, settings.getPairedServer())

        onNode(hasText("Cancel") and inTheDialog).performClick()
        waitForIdle()
        onNodeWithText("Replace Desk with Not yours?").assertDoesNotExist()
        assertTrue(requests.isEmpty())
        assertEquals(yours, settings.getPairedServer(), "still paired with the computer it was")
    }

    @Test
    fun aLinkFromOutsidePairsOnceAgreedTo() = runComposeUiTest {
        val settings = InMemorySettingsRepository()
        setContent { PairingSettings(settings, pairing(settings), onChanged = {}, incomingLink = other.toUri()) }
        waitForIdle()

        onNodeWithText("Pair with Not yours?").assertExists()
        assertTrue(requests.isEmpty())

        onNode(hasText("Pair") and inTheDialog).performClick()
        waitUntil(timeoutMillis = 5_000) { settings.getPairedServer() != null }
        assertEquals("server-2", settings.getPairedServer()?.serverId)
        assertEquals(listOf("203.0.113.9"), requests)
    }

    @Test
    fun pastingAnotherServersLinkAsksBeforeReplacingThePairedOne() = runComposeUiTest {
        val settings = InMemorySettingsRepository(initialPairedServer = yours)
        setContent { PairingSettings(settings, pairing(settings), onChanged = {}) }
        onNode(hasSetTextAction()).performTextInput(other.toUri())
        onNode(hasText("Pair") and !inTheDialog).performClick()
        waitForIdle()

        onNodeWithText("Replace Desk with Not yours?").assertExists()
        assertTrue(requests.isEmpty())
        assertEquals(yours, settings.getPairedServer())
    }
}
