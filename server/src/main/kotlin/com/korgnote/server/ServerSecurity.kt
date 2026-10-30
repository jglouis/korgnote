package com.korgnote.server

import com.korgnote.common.SERVER_PORT
import com.korgnote.common.pairing.SECURE_PORT
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
) {
    fun describe(): String =
        "Paired devices: https on $httpsHost:$httpsPort. This computer only: http on $httpHost:$httpPort " +
            "(web client, desktop app, and the Devices page at /devices). State in ${stateDir.absolutePath}." +
            if (leftoverToken) " $TOKEN_VARIABLE is set but no longer used: pair devices on the Devices page instead." else ""

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

        fun fromEnvironment(read: (String) -> String? = System::getenv): ServerConfig {
            fun value(name: String) = read(name)?.trim()?.takeIf { it.isNotEmpty() }
            val defaults = ServerConfig()
            val httpsPort = value(HTTPS_PORT_VARIABLE)?.toIntOrNull() ?: defaults.httpsPort
            return ServerConfig(
                httpHost = value(HTTP_HOST_VARIABLE) ?: defaults.httpHost,
                httpPort = value(PORT_VARIABLE)?.toIntOrNull() ?: defaults.httpPort,
                httpsPort = httpsPort,
                advertisedPort = value(ADVERTISED_PORT_VARIABLE)?.toIntOrNull() ?: httpsPort,
                stateDir = value(STATE_DIR_VARIABLE)?.let { File(expandHome(it)) } ?: defaults.stateDir,
                serverName = value(SERVER_NAME_VARIABLE) ?: defaults.serverName,
                advertisedAddresses = value(ADDRESSES_VARIABLE)?.split(',', ' ')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                leftoverToken = value(TOKEN_VARIABLE) != null,
            )
        }

        private fun expandHome(path: String) =
            if (path == "~" || path.startsWith("~/")) System.getProperty("user.home") + path.removePrefix("~") else path
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
) {
    /** Set by `scripts/pair.sh`, which asks the host for its addresses: the newest a QR code was made with. */
    @Volatile
    var lastAddresses: List<String> = emptyList()

    /** Where devices may find this server, best guesses first. */
    fun addresses(): List<String> = (lastAddresses + configuredAddresses + interfaceAddresses()).distinct()
}

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
    return h == "localhost" || h == "::1" || h.startsWith("127.")
}

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
