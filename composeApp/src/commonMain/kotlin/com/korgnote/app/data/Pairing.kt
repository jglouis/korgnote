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
import com.korgnote.common.pairing.PairingLinkCheck
import korgnote.composeapp.generated.resources.data_or
import korgnote.composeapp.generated.resources.data_pairing_code_refused
import korgnote.composeapp.generated.resources.data_pairing_no_answer
import korgnote.composeapp.generated.resources.data_pairing_not_listening
import korgnote.composeapp.generated.resources.data_pairing_server_error
import korgnote.composeapp.generated.resources.data_pairing_server_said
import korgnote.composeapp.generated.resources.data_pairing_wrong_certificate
import korgnote.composeapp.generated.resources.settings_the_computer
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

    /**
     * Keeps [uri] for Settings when it is a pairing link — also one that cannot pair, so that
     * Settings says why rather than the link doing nothing — and says whether it was one.
     */
    fun offer(uri: String?): Boolean {
        if (uri == null || PairingLink.check(uri) == PairingLinkCheck.NotALink) return false
        pending.value = uri
        return true
    }
}

/** How a pairing went. */
sealed interface PairingResult {
    data class Paired(val server: PairedServer) : PairingResult

    /** What went wrong, as [problem], and [reason]: that said for the user, with what to do about it. */
    data class Failed(val reason: String, val problem: PairingProblem = PairingProblem.ServerError) : PairingResult
}

/** Why a pairing failed, each with its own advice. */
enum class PairingProblem {
    /** The server turned the code down: used already, or older than its 10 minutes. */
    CodeRefused,

    /** Something answered, showing another certificate than the code names. */
    WrongCertificate,

    /** The computer answered, but nothing listens on the port. */
    NotListening,

    /** Nothing answered at any address. */
    NoAnswer,

    /** The server answered with another error. */
    ServerError,
}

/**
 * How connecting to one address failed, read off the platform's exception: a certificate turned
 * down by the pin, a connection refused, or anything else — a timeout, no route — which all mean
 * that nothing answered. The words are the platform's, so they are only looked through, never shown.
 */
internal fun pairingProblemOf(failure: Throwable): PairingProblem {
    val chain = generateSequence(failure) { it.cause }.take(8).toList()
    val names = chain.map { it::class.simpleName.orEmpty() }
    val messages = chain.map { it.message.orEmpty().lowercase() }
    return when {
        names.any { "SSL" in it || "Certificate" in it || "Handshake" in it } || messages.any { "certificate" in it } ->
            PairingProblem.WrongCertificate
        messages.any { "refused" in it } -> PairingProblem.NotListening
        else -> PairingProblem.NoAnswer
    }
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
    /**
     * Looks at the connection itself when the HTTP engine's words do not tell a refused port from
     * no answer, as Android's do not; null where it cannot tell. See `platformProbeConnection`.
     */
    private val probe: suspend (host: String, port: Int) -> PairingProblem? = { _, _ -> null },
) {
    suspend fun pair(link: PairingLink, deviceName: String): PairingResult {
        val previous = settings.getPairedServer()?.takeIf { it.serverId == link.serverId }
        val request = PairRequest(link.code, deviceName, previous?.deviceId, previous?.token)
        val client = newClient(CertificatePins { link.fingerprint })
        val computer = link.serverName.ifBlank { getString(Res.string.settings_the_computer) }
        val failures = mutableMapOf<String, PairingProblem>()
        return client.use {
            for (address in link.addresses) {
                val response = try {
                    client.post("https://${hostForUrl(address)}:${link.port}/api/pair") {
                        contentType(ContentType.Application.Json)
                        setBody(request)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures[address] = pairingProblemOf(e).let { guessed ->
                        if (guessed == PairingProblem.NoAnswer) probe(address, link.port) ?: guessed else guessed
                    }
                    // What the platform said, for whoever reads the log when a pairing fails.
                    val said = generateSequence<Throwable>(e) { it.cause }.take(8)
                        .joinToString(" <- ") { "${it::class.simpleName}: ${it.message}" }
                    println("[DEBUG_LOG][Pairing] $address:${link.port} ${failures[address]}: $said")
                    continue
                }
                if (response.status == HttpStatusCode.Forbidden) {
                    return@use PairingResult.Failed(getString(Res.string.data_pairing_code_refused, computer), PairingProblem.CodeRefused)
                }
                if (response.status != HttpStatusCode.OK) {
                    val said = response.bodyAsText().ifBlank { getString(Res.string.data_pairing_server_said, response.status.toString()) }
                    return@use PairingResult.Failed(getString(Res.string.data_pairing_server_error, computer, said), PairingProblem.ServerError)
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
            // The most telling failure decides what is said: something that answered with another
            // certificate, then a computer with nothing on the port, then no answer at all.
            val or = " ${getString(Res.string.data_or)} "
            fun where(problem: PairingProblem) = failures.filterValues { it == problem }.keys.joinToString(or)
            when {
                PairingProblem.WrongCertificate in failures.values -> PairingResult.Failed(
                    getString(Res.string.data_pairing_wrong_certificate, where(PairingProblem.WrongCertificate), computer),
                    PairingProblem.WrongCertificate,
                )
                PairingProblem.NotListening in failures.values -> PairingResult.Failed(
                    getString(Res.string.data_pairing_not_listening, where(PairingProblem.NotListening), link.port),
                    PairingProblem.NotListening,
                )
                else -> PairingResult.Failed(
                    getString(Res.string.data_pairing_no_answer, computer, link.addresses.joinToString(or), link.port),
                    PairingProblem.NoAnswer,
                )
            }
        }
    }
}
