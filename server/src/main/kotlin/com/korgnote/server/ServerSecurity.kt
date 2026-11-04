package com.korgnote.server

import com.korgnote.common.SERVER_PORT
import com.korgnote.common.pairing.SECURE_PORT
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.port
import org.slf4j.Logger
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * How the server exposes itself, from the environment.
 *
 * Two ports. The plain one ([httpPort]) is for the computer the server runs on: the web client, the
 * desktop app, the Devices page where devices are paired and revoked. It listens on loopback, so
 * nothing else can reach it — in the container it listens everywhere inside, the compose file
 * publishes it on the host's loopback only, and a connection from anywhere but the host is refused
 * by its address ([ServerAccess.thisComputer]). The secure one ([httpsPort]) is for the network: TLS
 * with the server's own certificate, and nothing but the sync API, for paired devices only.
 *
 * And what it serves: the notes in [notesDir], and the web client it finds with [findWebClient].
 */
data class ServerConfig(
    val httpHost: String = LOOPBACK,
    val httpPort: Int = SERVER_PORT,
    val httpsHost: String = ALL_INTERFACES,
    val httpsPort: Int = SECURE_PORT,
    /** The port a pairing QR code tells devices to use: [httpsPort] unless the container publishes it as another. */
    val advertisedPort: Int = httpsPort,
    val stateDir: File = File(System.getProperty("user.home"), ".korgnote-server"),
    /** What a device shows the server as once paired. */
    val serverName: String = "KorgNote",
    /** Addresses for the QR code that the server cannot find for itself, as in a container. */
    val advertisedAddresses: List<String> = emptyList(),
    /** A `KORGNOTE_TOKEN` left over from before pairing, which is now ignored; worth a line in the log. */
    val leftoverToken: Boolean = false,
    val notesDir: File = File(DEFAULT_NOTES_DIR),
    /** Stop when [notesDir] is missing or cannot be written to; see [REQUIRE_NOTES_DIR_VARIABLE]. */
    val requireNotesDir: Boolean = false,
    /** Where to look for the web client before the places a Gradle build leaves it. */
    val webClientDir: String? = null,
    /** False to serve the API only; see [DISABLE_WEB_VARIABLE]. */
    val serveWebClient: Boolean = true,
) {
    fun describe(): String =
        "Paired devices: https on $httpsHost:$httpsPort. This computer only: http on $httpHost:$httpPort " +
            "(web client, desktop app, and the Devices page at /devices). State in ${stateDir.absolutePath}." +
            if (leftoverToken) " $TOKEN_VARIABLE is set but no longer used: pair devices on the Devices page instead." else ""

    /**
     * The notes directory; an empty, read-only library when it does not exist.
     *
     * Built once, but it reads the disk on every request rather than caching contents — see
     * [FileStore].
     */
    fun openStore(log: Logger): FileStore {
        if (requireNotesDir) {
            check(notesDir.isDirectory) {
                "Notes directory '${notesDir.absolutePath}' does not exist and $REQUIRE_NOTES_DIR_VARIABLE is set. " +
                    "Is the volume mounted?"
            }
            // Write a file rather than ask canWrite(): through Docker Desktop's file sharing a
            // directory can report itself writable and still refuse the write.
            val writable = runCatching {
                File.createTempFile(".korgnote-write-check", null, notesDir).delete()
            }.isSuccess
            check(writable) {
                "Notes directory '${notesDir.absolutePath}' is not writable by this user. " +
                    "Run as the UID/GID that owns it."
            }
        }
        if (notesDir.isDirectory) {
            log.info("Serving .org files from ${notesDir.absolutePath}")
            return FileStore(notesDir.toPath())
        }
        log.warn(
            "Notes directory '${notesDir.absolutePath}' not found: serving an empty library, read-only and unwatched. " +
                "Create it, or set $NOTES_DIR_VARIABLE, and restart the server.",
        )
        return FileStore(notesDir.toPath(), readOnly = true)
    }

    /** The build of the web client to serve at `/`, if it should be served and one can be found. */
    fun findWebClient(log: Logger): File? {
        if (!serveWebClient) {
            log.info("Not serving the web client: $DISABLE_WEB_VARIABLE is set")
            return null
        }
        // The repository root and `server/`, which is what the working directory is under Gradle.
        val built = listOf("composeApp", "../composeApp").flatMap { module ->
            listOf("$module/build/dist/wasmJs/productionExecutable", "$module/build/dist/wasmJs/developmentExecutable")
        }
        val found = (listOfNotNull(webClientDir) + built).map(::File).firstOrNull { it.exists() }
        if (found != null) {
            log.info("Serving the web client from ${found.absolutePath}")
        } else {
            log.info("No web client to serve, looking from ${System.getProperty("user.dir")}")
        }
        return found
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val ALL_INTERFACES = "0.0.0.0"

        const val HTTP_HOST_VARIABLE = "KORGNOTE_HTTP_HOST"
        const val PORT_VARIABLE = "KORGNOTE_PORT"
        const val HTTPS_PORT_VARIABLE = "KORGNOTE_HTTPS_PORT"
        const val ADVERTISED_PORT_VARIABLE = "KORGNOTE_ADVERTISED_PORT"
        const val STATE_DIR_VARIABLE = "KORGNOTE_STATE_DIR"
        const val SERVER_NAME_VARIABLE = "KORGNOTE_SERVER_NAME"
        const val ADDRESSES_VARIABLE = "KORGNOTE_ADVERTISED_ADDRESSES"
        const val TOKEN_VARIABLE = "KORGNOTE_TOKEN"
        const val NOTES_DIR_VARIABLE = "NOTES_DIR"
        const val NOTES_DIR_PROPERTY = "notes.dir"
        const val WEB_DIR_VARIABLE = "WASM_DIR"
        const val WEB_DIR_PROPERTY = "wasm.dir"

        /** Serve the API only, even when a web client build is present (the container ships one). */
        const val DISABLE_WEB_VARIABLE = "KORGNOTE_DISABLE_WEB"

        /**
         * Set by the container image: a missing or unwritable notes directory stops the server. In a
         * container, serving an empty library instead would hide a typo in the volume path, or a UID
         * that cannot write to the mount, behind a server that looks healthy.
         */
        const val REQUIRE_NOTES_DIR_VARIABLE = "KORGNOTE_REQUIRE_NOTES_DIR"

        private const val DEFAULT_NOTES_DIR = "notes"

        /** What is set by a system property, `-Dnotes.dir=…`, wins over the environment. */
        fun fromEnvironment(
            property: (String) -> String? = System::getProperty,
            read: (String) -> String? = System::getenv,
        ): ServerConfig {
            fun value(name: String) = read(name)?.trim()?.takeIf { it.isNotEmpty() }
            fun propertyValue(name: String) = property(name)?.trim()?.takeIf { it.isNotEmpty() }
            fun isSet(name: String) = value(name)?.lowercase() in setOf("1", "true", "yes")
            fun expanded(path: String) = expandPath(path, read)
            val defaults = ServerConfig()
            val httpsPort = value(HTTPS_PORT_VARIABLE)?.toIntOrNull() ?: defaults.httpsPort
            return ServerConfig(
                httpHost = value(HTTP_HOST_VARIABLE) ?: defaults.httpHost,
                httpPort = value(PORT_VARIABLE)?.toIntOrNull() ?: defaults.httpPort,
                httpsPort = httpsPort,
                advertisedPort = value(ADVERTISED_PORT_VARIABLE)?.toIntOrNull() ?: httpsPort,
                stateDir = value(STATE_DIR_VARIABLE)?.let { File(expanded(it)) } ?: defaults.stateDir,
                serverName = value(SERVER_NAME_VARIABLE) ?: defaults.serverName,
                advertisedAddresses = value(ADDRESSES_VARIABLE)?.split(',', ' ')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                leftoverToken = value(TOKEN_VARIABLE) != null,
                notesDir = File(expanded(propertyValue(NOTES_DIR_PROPERTY) ?: value(NOTES_DIR_VARIABLE) ?: DEFAULT_NOTES_DIR)),
                requireNotesDir = isSet(REQUIRE_NOTES_DIR_VARIABLE),
                webClientDir = propertyValue(WEB_DIR_PROPERTY) ?: value(WEB_DIR_VARIABLE),
                serveWebClient = !isSet(DISABLE_WEB_VARIABLE),
            )
        }

        /** `~` at the start of [path], and any Windows-style `%NAME%` that [read] knows. */
        private fun expandPath(path: String, read: (String) -> String?): String {
            val fromHome = if (path == "~" || path.startsWith("~/") || path.startsWith("~\\")) {
                System.getProperty("user.home") + path.removePrefix("~")
            } else {
                path
            }
            return fromHome.replace(Regex("%([^%]+)%")) { read(it.groupValues[1]) ?: it.value }
        }
    }
}

/**
 * Who may use what, as the module enforces it: [devices] for the network, and the plain port for
 * this computer only. [securePort] is the port TLS arrives on; null where there is none, as under
 * the test host, which then stands for the computer itself.
 */
class ServerAccess(
    val devices: DeviceRegistry,
    val securePort: Int?,
    /** What a pairing QR code says about this server; null where devices cannot pair. */
    val pairing: PairingDetails?,
    /**
     * Addresses besides loopback that the plain port takes as this computer: in a container, the
     * network's gateway, which is where the host's published port arrives from.
     */
    val thisComputer: Set<String> = emptySet(),
) {
    companion object {
        /** No network side at all: what the test host and the API tests want. */
        fun localOnly(devices: DeviceRegistry = DeviceRegistry(file = null)) = ServerAccess(devices, securePort = null, pairing = null)
    }
}

/** The server's side of a pairing link, all but the one-time code and the addresses of the moment. */
class PairingDetails(
    val serverId: String,
    val serverName: String,
    val fingerprint: String,
    val port: Int,
    /** Addresses given at startup; the Devices page adds what the computer's own interfaces say, when it can see them. */
    val configuredAddresses: List<String>,
    /** This computer's own addresses; none inside a container. Given in tests. */
    private val interfaces: () -> List<String> = ::interfaceAddresses,
) {
    /** Set by `scripts/pair.sh`, which asks the host for its addresses: the newest a QR code was made with. */
    @Volatile
    var lastAddresses: List<String> = emptyList()

    /** Where devices may find this server, best guesses first. */
    fun addresses(): List<String> = (lastAddresses + configuredAddresses + interfaces()).distinct()

    /** What to do about knowing no address, which no QR code can be made without; null when one is known. */
    fun missingAddressAdvice(): String? = if (addresses().isNotEmpty()) null else NO_ADDRESS_ADVICE

    companion object {
        const val NO_ADDRESS_ADVICE =
            "The server does not know an address phones can reach it at, so a pairing code would lead nowhere. " +
                "Type this computer's address on your network (such as 192.168.1.20, in its Wi-Fi settings) below. " +
                "In a container, start it with ${ServerConfig.ADDRESSES_VARIABLE} set to that address, so it is known from the start."
    }
}

/**
 * Whether [text] can be where a phone finds this server: an IPv4 or IPv6 address, or a host name.
 * Anything else typed in the Devices page's address box — a URL, a port, a stray word — would only
 * put a code in the QR code that the phone cannot use.
 */
fun isPairingAddress(text: String): Boolean =
    IPV4.matches(text) && text.split('.').all { it.toInt() <= 255 } ||
        // IPv6 has at least two colons, which an IPv4 address with a port (`192.168.1.20:8443`) has not.
        (text.count { it == ':' } >= 2 && IPV6.matches(text)) ||
        (HOST_NAME.matches(text) && !text.all { it.isDigit() || it == '.' })

private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
private val IPV6 = Regex("^[0-9A-Fa-f:.]+(%[A-Za-z0-9]+)?$")
private val HOST_NAME = Regex("^(?=.{1,253}$)[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")

/**
 * This computer's IPv4 addresses on the networks it is on. None inside a container, whose
 * interfaces are the container's own: there the run script passes the host's in.
 */
internal fun interfaceAddresses(): List<String> {
    if (File("/.dockerenv").exists()) return emptyList()
    return runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress }
    }.getOrDefault(emptyList())
}

/**
 * Whether [host], from a request's `Host` header, names this computer: `localhost` or a loopback
 * address. A page served from any other
 * name — one that resolves to 127.0.0.1 to get past the browser's same-origin rule — is refused.
 */
internal fun namesThisComputer(host: String): Boolean {
    val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
    return h == "localhost" || h == "::1" || LOOPBACK_IPV4.matches(h)
}

// Exactly an address: `127.evil.example` starts the same way, and anyone can point it at 127.0.0.1.
private val LOOPBACK_IPV4 = Regex("^127(\\.\\d{1,3}){3}$")

/**
 * Whether a connection from [address] comes from this computer: loopback, or one of [alsoThisComputer].
 * The test host gives `localhost` as the address of every call, and is this computer too.
 */
internal fun connectsFromThisComputer(address: String, alsoThisComputer: Set<String>): Boolean {
    val a = address.trim().removePrefix("[").removeSuffix("]").lowercase()
    if (a == "localhost" || a in alsoThisComputer) return true
    // A literal address is parsed without a lookup; anything else is not one this side can trust.
    if (!a.all { it.isDigit() || it in "abcdef.:" }) return false
    return runCatching { InetAddress.getByName(a).isLoopbackAddress }.getOrDefault(false)
}

/**
 * The gateway of the default route in [routes], the text of `/proc/net/route`; null when it has
 * none. Addresses there are hexadecimal and little-endian: `010011AC` is 172.17.0.1.
 */
internal fun defaultGateway(routes: String): String? = routes.lineSequence()
    .drop(1)
    .map { it.trim().split(Regex("\\s+")) }
    .firstOrNull { it.size > 2 && it[1] == "00000000" && it[2] != "00000000" }
    ?.get(2)
    ?.toLongOrNull(16)
    ?.let { gateway -> (0 until 4).joinToString(".") { ((gateway shr (8 * it)) and 0xFF).toString() } }

/**
 * Inside a container, the default gateway, from `/proc/net/route`: the host's published port
 * reaches the server from there. Empty anywhere else.
 */
internal fun containerGateway(): Set<String> {
    if (!File("/.dockerenv").exists()) return emptySet()
    val routes = runCatching { File("/proc/net/route").readText() }.getOrNull() ?: return emptySet()
    return setOfNotNull(defaultGateway(routes))
}

/**
 * Why [call] may not go on, as a status and a sentence; null when it may.
 *
 * Over TLS, only the sync API answers, and only to a paired device's token — pairing itself
 * excepted, which is how a device gets one. The plain port answers this computer only, which its
 * loopback binding already ensures; a browser on this computer is held to it as well, since any page
 * it has open could otherwise send requests here: the `Host` must name this computer, which stops a
 * page reaching it through a name of its own (DNS rebinding), and a change must come from a page of
 * this server's own, which stops one changing notes from another site.
 */
internal fun refusalOf(call: ApplicationCall, access: ServerAccess): Pair<HttpStatusCode, String>? {
    val path = call.request.path()
    if (access.securePort != null && call.request.local.localPort == access.securePort) {
        if (path == "/api/pair") return null
        // What may be reached, not what may not: this is the path as it was sent, while routing ignores
        // empty segments and decodes the rest, so `/api//local/` and `/api/%6Cocal/` reach `/api/local/`.
        if (!path.startsWith("/api/v1/")) {
            return HttpStatusCode.NotFound to "Not found. The web client and the Devices page are on the computer the server runs on."
        }
        val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
        return if (token.isNullOrEmpty() || access.devices.authenticate(token) == null) {
            HttpStatusCode.Unauthorized to "This device is not paired, or was revoked. Pair it again from the Devices page."
        } else {
            null
        }
    }
    // Where the connection comes from, which the caller cannot write, before what it says about
    // itself: in a container this port listens on every interface inside, and another container on
    // the same network could otherwise claim to be localhost.
    if (!connectsFromThisComputer(call.request.local.remoteAddress, access.thisComputer)) {
        return HttpStatusCode.Forbidden to "This port serves the computer the server runs on only."
    }
    if (!namesThisComputer(call.request.host())) {
        return HttpStatusCode.Forbidden to "This port serves the computer the server runs on, by the name localhost."
    }
    val method = call.request.httpMethod
    if (method != HttpMethod.Get && method != HttpMethod.Head && method != HttpMethod.Options) {
        val origin = call.request.header(HttpHeaders.Origin)?.let { runCatching { Url(it) }.getOrNull() ?: Url("null://") }
        if (origin != null && !(origin.host.equals(call.request.host(), ignoreCase = true) && origin.port == call.request.port())) {
            return HttpStatusCode.Forbidden to "Changes are accepted from this server's own pages only."
        }
    }
    return null
}

