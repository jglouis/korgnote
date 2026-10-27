package com.korgnote.common.pairing

import kotlinx.serialization.Serializable

/**
 * The port the server serves paired devices on, over TLS. [com.korgnote.common.SERVER_PORT] is the
 * plain one, which only the computer the server runs on can reach.
 */
const val SECURE_PORT = 8443

/**
 * Everything a device needs to pair with a server, as the QR code on the server's Devices page
 * carries it: which server ([serverId], which stays the same when its addresses change), where to
 * reach it, the SHA-256 [fingerprint] of the certificate it will show — the only one the device
 * will ever accept from it — and a one-time [code] that is exchanged for a token of the device's own.
 *
 * Written as a `korgnote://pair?…` link, so that it can be pasted as well as scanned.
 */
data class PairingLink(
    val serverId: String,
    val serverName: String,
    val addresses: List<String>,
    val port: Int,
    /** Lower-case hex, no separators. */
    val fingerprint: String,
    val code: String,
) {
    fun toUri(): String = buildString {
        append("$SCHEME://pair?v=1")
        append("&id=").append(encode(serverId))
        append("&name=").append(encode(serverName))
        append("&a=").append(addresses.joinToString(",") { encode(it) })
        append("&p=").append(port)
        append("&fp=").append(fingerprint)
        append("&c=").append(encode(code))
    }

    companion object {
        const val SCHEME = "korgnote"

        /** The link in [text] — trimmed, as a paste brings it — or null when it is not one. */
        fun parse(text: String): PairingLink? {
            val t = text.trim()
            val prefix = "$SCHEME://pair?"
            if (!t.startsWith(prefix, ignoreCase = true)) return null
            val query = t.substring(prefix.length).split('&').mapNotNull { part ->
                val at = part.indexOf('=')
                if (at <= 0) null else part.substring(0, at) to part.substring(at + 1)
            }.toMap()
            val serverId = query["id"]?.let(::decode)?.takeIf { it.isNotBlank() } ?: return null
            val addresses = query["a"].orEmpty().split(',').map(::decode).map { it.trim() }.filter { it.isNotEmpty() }
            val port = query["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val fingerprint = query["fp"]?.lowercase()?.takeIf { FINGERPRINT.matches(it) } ?: return null
            val code = query["c"]?.let(::decode)?.takeIf { it.isNotBlank() } ?: return null
            if (addresses.isEmpty()) return null
            return PairingLink(serverId, query["name"]?.let(::decode).orEmpty(), addresses, port, fingerprint, code)
        }

        private val FINGERPRINT = Regex("^[0-9a-f]{64}$")
    }
}

/** What a device sends to pair, over TLS to the pinned certificate. */
@Serializable
data class PairRequest(
    val code: String,
    /** What the Devices page calls it: the phone's own name, as its owner set it. */
    val deviceName: String,
    /**
     * The device's id from an earlier pairing with this server, given up for the new one, so that
     * scanning again — for new addresses — leaves one entry, not two. Honoured only with its token.
     */
    val replaces: String? = null,
    val replacesToken: String? = null,
)

@Serializable
data class PairResponse(
    val deviceId: String,
    /** Sent as `Authorization: Bearer …` from now on. The server keeps only its hash. */
    val token: String,
    val serverId: String,
    val serverName: String,
)

/** Percent-encodes everything but letters, digits and `-._~`, as a URI component. */
private fun encode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt() and 0xff
        val ch = c.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~") {
            append(ch)
        } else {
            append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
        }
    }
}

private fun decode(value: String): String {
    val bytes = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
        val ch = value[i]
        if (ch == '%' && i + 2 < value.length) {
            val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
            if (hex != null) {
                bytes += hex.toByte()
                i += 3
                continue
            }
        }
        if (ch == '+') bytes += ' '.code.toByte() else ch.toString().encodeToByteArray().forEach { bytes += it }
        i++
    }
    return bytes.toByteArray().decodeToString()
}

private const val HEX = "0123456789ABCDEF"
