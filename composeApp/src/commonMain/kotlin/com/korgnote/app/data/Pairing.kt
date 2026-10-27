package com.korgnote.app.data

import com.korgnote.common.pairing.PairRequest
import com.korgnote.common.pairing.PairResponse
import com.korgnote.common.pairing.PairingLink
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The computer this device is paired with: where it may be, the certificate it must show, and the
 * token it gave this device. Saved in settings as JSON; null there until the first pairing.
 */
@Serializable
data class PairedServer(
    val serverId: String,
    val serverName: String,
    val addresses: List<String>,
    val port: Int,
    val fingerprint: String,
    val deviceId: String,
    val token: String,
) {
    /** One base URL per address, in the order the QR code gave them. */
    fun baseUrls(): List<String> = addresses.map { "https://${hostForUrl(it)}:$port" }

    fun encode(): String = Json.encodeToString(this)

    companion object {
        fun decode(stored: String?): PairedServer? =
            stored?.takeIf { it.isNotBlank() }?.let { runCatching { Json.decodeFromString<PairedServer>(it) }.getOrNull() }
    }
}

/** Where a request can go: a base URL, and the token to send there, if any. */
data class Endpoint(val baseUrl: String, val token: String?)

/** The paired server's addresses with its token; or, unpaired, the server on this computer, which wants none. */
fun endpointsFor(settings: SettingsRepository): List<Endpoint> {
    val paired = settings.getPairedServer()
    return paired?.baseUrls()?.map { Endpoint(it, paired.token) }
        ?: listOf(Endpoint(buildLocalBaseUrl(settings.getServerHost(), settings.getServerPort()), null))
}

/** `http://host:port` for the server on this computer, from the host and port in Settings. */
fun buildLocalBaseUrl(host: String, port: Int): String {
    val normalized = if (host.startsWith("http://") || host.startsWith("https://")) host else "http://$host"
    return "$normalized:$port"
}

/** An IPv6 address goes in brackets in a URL; anything else as it is. */
internal fun hostForUrl(address: String): String = if (':' in address && !address.startsWith("[")) "[$address]" else address

/**
 * The certificates the HTTP client accepts over TLS: the paired server's, and while pairing, the
 * one the QR code names. Every engine that speaks TLS asks this, so nothing else is ever trusted.
 */
class CertificatePins(private val paired: () -> String?) {
    @Volatile
    private var trying: String? = null

    fun accepted(): Set<String> = setOfNotNull(paired(), trying)

    suspend fun <T> whileTrying(fingerprint: String, block: suspend () -> T): T {
        trying = fingerprint
        try {
            return block()
        } finally {
            trying = null
        }
    }
}

/** How a pairing went. */
sealed interface PairingResult {
    data class Paired(val server: PairedServer) : PairingResult

    data class Failed(val reason: String) : PairingResult
}

/**
 * Pairs this device with the server a [PairingLink] describes: tries its addresses in turn, over TLS
 * to the certificate the link names and no other, and trades the link's one-time code for a token.
 * Pairing again with the same server gives up the old token, so the Devices page lists it once.
 */
class DevicePairing(
    private val client: HttpClient,
    private val pins: CertificatePins,
    private val settings: SettingsRepository,
) {
    suspend fun pair(link: PairingLink, deviceName: String): PairingResult {
        val previous = settings.getPairedServer()?.takeIf { it.serverId == link.serverId }
        val request = PairRequest(link.code, deviceName, previous?.deviceId, previous?.token)
        var lastProblem: String? = null
        return pins.whileTrying(link.fingerprint) {
            for (address in link.addresses) {
                val response = try {
                    client.post("https://${hostForUrl(address)}:${link.port}/api/pair") {
                        contentType(ContentType.Application.Json)
                        setBody(request)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastProblem = e.message
                    continue
                }
                if (response.status != HttpStatusCode.OK) {
                    return@whileTrying PairingResult.Failed(response.bodyAsText().ifBlank { "The server said ${response.status}." })
                }
                val answer = response.body<PairResponse>()
                val server = PairedServer(
                    serverId = answer.serverId,
                    serverName = answer.serverName.ifBlank { link.serverName },
                    // The one that answered first, so that it is tried first from now on.
                    addresses = listOf(address) + link.addresses.filter { it != address },
                    port = link.port,
                    fingerprint = link.fingerprint,
                    deviceId = answer.deviceId,
                    token = answer.token,
                )
                settings.setPairedServer(server)
                return@whileTrying PairingResult.Paired(server)
            }
            PairingResult.Failed(
                "Could not reach ${link.serverName.ifBlank { "the computer" }} at ${link.addresses.joinToString(" or ")}. " +
                    "Is this device on the same network?" + (lastProblem?.let { " ($it)" } ?: ""),
            )
        }
    }
}
