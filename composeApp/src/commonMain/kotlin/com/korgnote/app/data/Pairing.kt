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
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.data_or
import korgnote.composeapp.generated.resources.data_pairing_server_said
import korgnote.composeapp.generated.resources.data_pairing_unreachable
import korgnote.composeapp.generated.resources.data_pairing_unreachable_unnamed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.getString

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

/**
 * The paired server's addresses with its token; or, unpaired, the server on this computer, which
 * wants none — where there is one: on a phone, nothing, rather than a request in the clear to an
 * address that will not answer it.
 */
fun endpointsFor(settings: SettingsRepository, localServer: Boolean = usesLocalServer): List<Endpoint> {
    val paired = settings.getPairedServer()
    return when {
        paired != null -> paired.baseUrls().map { Endpoint(it, paired.token) }
        localServer -> listOf(Endpoint(buildLocalBaseUrl(settings.getServerHost(), settings.getServerPort()), null))
        else -> emptyList()
    }
}

/** `http://host:port` for the server on this computer, from the host and port in Settings. */
fun buildLocalBaseUrl(host: String, port: Int): String {
    val normalized = if (host.startsWith("http://") || host.startsWith("https://")) host else "http://$host"
    return "$normalized:$port"
}

/** An IPv6 address goes in brackets in a URL; anything else as it is. */
internal fun hostForUrl(address: String): String = if (':' in address && !address.startsWith("[")) "[$address]" else address

/**
 * The certificate an HTTP client accepts over TLS: the paired server's for the app's own client, the
 * one a QR code names for a pairing's. Every engine that speaks TLS asks this, so nothing else is
 * ever trusted.
 */
class CertificatePins(private val fingerprint: () -> String?) {
    fun accepted(): Set<String> = setOfNotNull(fingerprint())
}

/**
 * A pairing link another app opened this one with — the camera, having read the QR code — waiting
 * for Settings to pair with it.
 */
object PairingLinks {
    val pending = MutableStateFlow<String?>(null)

    /** Keeps [uri] for Settings when it is a pairing link; whether it was one. */
    fun offer(uri: String?): Boolean {
        if (uri == null || PairingLink.parse(uri) == null) return false
        pending.value = uri
        return true
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
 *
 * Each pairing gets an HTTP client of its own from [newClient], trusting the link's certificate
 * alone. The app's client will not do: it trusts the certificate paired before, and keeps
 * connections open that were checked against that one — a request it sent could go over one of
 * them, and the link's certificate would never be checked at all.
 */
class DevicePairing(
    private val newClient: (CertificatePins) -> HttpClient,
    private val settings: SettingsRepository,
) {
    suspend fun pair(link: PairingLink, deviceName: String): PairingResult {
        val previous = settings.getPairedServer()?.takeIf { it.serverId == link.serverId }
        val request = PairRequest(link.code, deviceName, previous?.deviceId, previous?.token)
        val client = newClient(CertificatePins { link.fingerprint })
        return client.use {
            for (address in link.addresses) {
                val response = try {
                    client.post("https://${hostForUrl(address)}:${link.port}/api/pair") {
                        contentType(ContentType.Application.Json)
                        setBody(request)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
                if (response.status != HttpStatusCode.OK) {
                    return@use PairingResult.Failed(
                        response.bodyAsText().ifBlank { getString(Res.string.data_pairing_server_said, response.status.toString()) },
                    )
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
                return@use PairingResult.Paired(server)
            }
            // The platform's own words are no help here: an address with nothing at it and a server
            // showing another certificate both end the connection, and neither is worth a stack.
            val addresses = link.addresses.joinToString(" ${getString(Res.string.data_or)} ")
            PairingResult.Failed(
                if (link.serverName.isBlank()) getString(Res.string.data_pairing_unreachable_unnamed, addresses)
                else getString(Res.string.data_pairing_unreachable, link.serverName, addresses),
            )
        }
    }
}
